package com.eaglepoint.libops.tests

import androidx.test.core.app.ApplicationProvider
import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.data.db.LibOpsDatabase
import com.eaglepoint.libops.domain.auth.Authorizer
import com.eaglepoint.libops.domain.auth.Capabilities
import com.eaglepoint.libops.domain.orchestration.ImportService
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

/**
 * Real-Room integration test for the import workflow triggered by
 * [com.eaglepoint.libops.ui.imports.ImportsActivity] (§9.12, §9.13).
 *
 * Exercises [ImportService.importCsv] / [ImportService.importJson] end-to-end
 * against real Room DAOs: batch creation, row persistence, final-state
 * derivation, and audit events. Complements the pure-JVM importer unit tests
 * with a real-persistence check.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ImportsOperationsIntegrationTest {

    private lateinit var db: LibOpsDatabase
    private lateinit var audit: AuditLogger
    private lateinit var service: ImportService
    private val userId = 7L

    @Before
    fun setUp() {
        db = LibOpsDatabase.inMemory(
            ApplicationProvider.getApplicationContext<android.content.Context>()
        )
        audit = AuditLogger(db.auditDao())
        service = ImportService(
            authz = Authorizer(setOf(Capabilities.IMPORTS_RUN)),
            importDao = db.importDao(),
            recordDao = db.recordDao(),
            duplicateDao = db.duplicateDao(),
            audit = audit,
        )
    }

    @After
    fun tearDown() { db.close() }

    private fun csvStream(csv: String) = ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8))

    // ── CSV import ────────────────────────────────────────────────────────────

    @Test
    fun csv_import_with_valid_row_persists_batch_and_record(): Unit = runBlocking {
        val csv = "title,format\nIntegration Book,paperback\n"
        val summary = service.importCsv(
            filename = "import.csv",
            source = csvStream(csv),
            userId = userId,
            userRecentImportsInWindow = 0,
        )

        assertThat(summary.finalState).isEqualTo("accepted_all")
        assertThat(summary.accepted).isEqualTo(1)

        // Verify batch persisted in real Room
        val batch = db.importDao().batchById(summary.batchId)
        assertThat(batch).isNotNull()
        assertThat(batch!!.acceptedRows).isEqualTo(1)
        assertThat(batch.createdByUserId).isEqualTo(userId)

        // Verify record landed in real master_records
        assertThat(db.recordDao().activeCount()).isEqualTo(1)

        // Verify audit trail in real audit_events
        val events = db.auditDao().allEventsChronological().map { it.action }
        assertThat(events).contains("import.received")
        assertThat(events).contains("import.completed")
    }

    @Test
    fun csv_import_with_missing_title_rejects_all_rows(): Unit = runBlocking {
        val csv = "title,format\n,paperback\n"
        val summary = service.importCsv(
            filename = "bad.csv",
            source = csvStream(csv),
            userId = userId,
            userRecentImportsInWindow = 0,
        )
        assertThat(summary.rejected).isEqualTo(1)
        assertThat(summary.finalState).isEqualTo("rejected_all")

        // Batch should still exist with rejected counter recorded
        val batch = db.importDao().batchById(summary.batchId)
        assertThat(batch!!.rejectedRows).isEqualTo(1)
        assertThat(db.recordDao().activeCount()).isEqualTo(0)
    }

    @Test
    fun csv_import_row_results_persisted_per_row(): Unit = runBlocking {
        val csv = "title,format\nBook 1,hardcover\nBook 2,ebook\nBook 3,paperback\n"
        val summary = service.importCsv(
            filename = "multi.csv",
            source = csvStream(csv),
            userId = userId,
            userRecentImportsInWindow = 0,
        )
        val rows = db.importDao().rowsFor(summary.batchId, limit = 100, offset = 0)
        assertThat(rows).hasSize(3)
        assertThat(rows.map { it.outcome }).containsExactly("accepted", "accepted", "accepted")
    }

    @Test
    fun csv_import_without_imports_run_capability_throws(): Unit = runBlocking {
        val unauthorized = ImportService(
            authz = Authorizer(emptySet()),
            importDao = db.importDao(),
            recordDao = db.recordDao(),
            duplicateDao = db.duplicateDao(),
            audit = audit,
        )
        try {
            unauthorized.importCsv(
                filename = "denied.csv",
                source = csvStream("title,format\nX,Y\n"),
                userId = userId,
                userRecentImportsInWindow = 0,
            )
            assertThat("expected SecurityException").isEmpty()
        } catch (_: SecurityException) {
            // expected
        }
        // No batch should have been created
        assertThat(db.importDao().batchCount()).isEqualTo(0)
    }

    // ── JSON import ───────────────────────────────────────────────────────────

    @Test
    fun json_import_with_valid_record_persists_to_real_room(): Unit = runBlocking {
        val json = """[{"title":"JSON Book","category":"book","format":"paperback"}]"""
        val summary = service.importJson(
            filename = "import.json",
            source = csvStream(json),
            userId = userId,
            userRecentImportsInWindow = 0,
        )
        assertThat(summary.finalState).isEqualTo("accepted_all")
        assertThat(summary.accepted).isEqualTo(1)
        assertThat(db.recordDao().activeCount()).isEqualTo(1)
    }

    @Test
    fun json_import_schema_version_is_captured_in_summary(): Unit = runBlocking {
        val json = """{"schema_version":"1.5","records":[{"title":"Schema Book","category":"other"}]}"""
        val summary = service.importJson(
            filename = "schema.json",
            source = csvStream(json),
            userId = userId,
            userRecentImportsInWindow = 0,
        )
        assertThat(summary.schemaVersion).isEqualTo("1.5")
    }

    // ── rate limiting ─────────────────────────────────────────────────────────

    @Test
    fun csv_import_at_rate_limit_throws_and_records_audit(): Unit = runBlocking {
        try {
            service.importCsv(
                filename = "throttled.csv",
                source = csvStream("title,format\nX,Y\n"),
                userId = userId,
                userRecentImportsInWindow = 30,  // DEFAULT_LIMIT
            )
            assertThat("expected IllegalStateException").isEmpty()
        } catch (_: IllegalStateException) {
            // expected
        }
        val events = db.auditDao().allEventsChronological()
        assertThat(events.any { it.action == "import.rate_limited" }).isTrue()
    }
}
