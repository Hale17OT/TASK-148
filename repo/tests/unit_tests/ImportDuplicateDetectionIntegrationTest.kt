package com.eaglepoint.libops.tests

import androidx.test.core.app.ApplicationProvider
import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.data.db.LibOpsDatabase
import com.eaglepoint.libops.data.db.entity.MasterRecordEntity
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
 * End-to-end integration test for the import → duplicate-detection → merge
 * queue pipeline (§9.7, §9.12, §10.5). Seeds a record with an ISBN-13, then
 * imports a second record with the same ISBN and asserts a duplicate
 * candidate lands in `duplicate_candidates` for subsequent merge review.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ImportDuplicateDetectionIntegrationTest {

    private lateinit var db: LibOpsDatabase
    private lateinit var audit: AuditLogger
    private lateinit var service: ImportService
    private val userId = 7L
    private val clockMs = 1_700_000_000_000L

    @Before
    fun setUp() = runBlocking {
        db = LibOpsDatabase.inMemory(
            ApplicationProvider.getApplicationContext<android.content.Context>()
        )
        audit = AuditLogger(db.auditDao(), clock = { clockMs })
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

    @Test
    fun importing_record_with_existing_isbn13_creates_duplicate_candidate(): Unit = runBlocking {
        // Seed an existing record with ISBN-13
        db.recordDao().insert(
            MasterRecordEntity(
                title = "The Great Novel", titleNormalized = "the great novel",
                publisher = "Acme Press", pubDate = null, format = "paperback",
                category = "book", isbn10 = null, isbn13 = "9780134685991",
                language = null, notes = null, status = "active",
                sourceProvenanceJson = null, createdByUserId = 1L,
                createdAt = clockMs, updatedAt = clockMs,
            ),
        )

        // Import a record with the same ISBN-13 — should trigger duplicate detection
        val json = """[{"title":"The Great Novel","isbn13":"9780134685991","publisher":"Acme Press","format":"paperback","category":"book"}]"""
        val summary = service.importJson(
            filename = "dup.json",
            source = ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)),
            userId = userId,
            userRecentImportsInWindow = 0,
        )

        assertThat(summary.duplicatesSurfaced).isAtLeast(1)
        assertThat(summary.finalState).isEqualTo("awaiting_merge_review")

        // Verify a duplicate candidate landed in real Room
        val candidates = db.duplicateDao().listByStatus("detected", limit = 100, offset = 0)
        assertThat(candidates).isNotEmpty()
        assertThat(candidates[0].algorithm).isIn(listOf("jaro_winkler", "isbn10_exact"))
    }

    @Test
    fun importing_record_with_unique_isbn_does_not_create_duplicate(): Unit = runBlocking {
        db.recordDao().insert(
            MasterRecordEntity(
                title = "Existing", titleNormalized = "existing",
                publisher = null, pubDate = null, format = "paperback",
                category = "book", isbn10 = null, isbn13 = "9780134685991",
                language = null, notes = null, status = "active",
                sourceProvenanceJson = null, createdByUserId = 1L,
                createdAt = clockMs, updatedAt = clockMs,
            ),
        )
        val json = """[{"title":"Brand New","isbn13":"9781491950296","format":"hardcover","category":"book"}]"""
        val summary = service.importJson(
            filename = "new.json",
            source = ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)),
            userId = userId,
            userRecentImportsInWindow = 0,
        )
        assertThat(summary.duplicatesSurfaced).isEqualTo(0)
        assertThat(summary.finalState).isEqualTo("accepted_all")
    }
}
