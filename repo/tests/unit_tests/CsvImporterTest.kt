package com.eaglepoint.libops.tests

import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.data.db.dao.DuplicateDao
import com.eaglepoint.libops.data.db.dao.RecordDao
import com.eaglepoint.libops.data.db.entity.DuplicateCandidateEntity
import com.eaglepoint.libops.data.db.entity.MasterRecordEntity
import com.eaglepoint.libops.data.db.entity.MasterRecordVersionEntity
import com.eaglepoint.libops.data.db.entity.MergeDecisionEntity
import com.eaglepoint.libops.imports.CsvImporter
import com.eaglepoint.libops.tests.fakes.FakeAuditDao
import com.eaglepoint.libops.tests.fakes.FakeImportDao
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Tests for [CsvImporter.import] covering header validation, row parsing,
 * record validation, rate limiting, final-state derivation, and audit events
 * (§9.12, §9.13).
 *
 * Robolectric is required because [CsvImporter.errorsToJson] uses
 * [org.json.JSONArray] / [org.json.JSONObject], whose Android stub jar
 * implementations return null from toString().
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class CsvImporterTest {

    private lateinit var importDao: FakeImportDao
    private lateinit var auditDao: FakeAuditDao
    private lateinit var recordDao: CsvTestRecordStub
    private lateinit var duplicateDao: CsvTestDuplicateStub
    private lateinit var importer: CsvImporter

    private val clockMs = 1_700_000_000_000L

    @Before
    fun setUp() {
        importDao = FakeImportDao()
        auditDao = FakeAuditDao()
        recordDao = CsvTestRecordStub()
        duplicateDao = CsvTestDuplicateStub()
        importer = CsvImporter(
            importDao = importDao,
            recordDao = recordDao,
            duplicateDao = duplicateDao,
            audit = AuditLogger(auditDao, clock = { clockMs }),
            clock = { clockMs },
        )
    }

    private fun streamOf(csv: String): ByteArrayInputStream =
        ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8))

    // ── rate limiting ──────────────────────────────────────────────────────────

    @Test(expected = IllegalStateException::class)
    fun rate_limit_at_30_throws_illegal_state(): Unit = runBlocking {
        importer.import("test.csv", streamOf("title\n"), userId = 1L, userRecentImportsInWindow = 30)
    }

    // ── header validation ──────────────────────────────────────────────────────

    @Test
    fun missing_title_header_produces_rejected_validation_failure(): Unit = runBlocking {
        val csv = "publisher,isbn13\nSome Pub,978-0-00-000000-2\n"
        val summary = importer.import("no_title.csv", streamOf(csv), userId = 1L, userRecentImportsInWindow = 0)
        assertThat(summary.finalState).isEqualTo("rejected_validation_failure")
    }

    @Test
    fun empty_input_produces_rejected_validation_failure(): Unit = runBlocking {
        val summary = importer.import("empty.csv", streamOf(""), userId = 1L, userRecentImportsInWindow = 0)
        assertThat(summary.finalState).isEqualTo("rejected_validation_failure")
    }

    // ── valid records ──────────────────────────────────────────────────────────

    @Test
    fun single_valid_row_produces_accepted_all(): Unit = runBlocking {
        val csv = "title,format\nGreat Book,paperback\n"
        val summary = importer.import("one.csv", streamOf(csv), userId = 1L, userRecentImportsInWindow = 0)
        assertThat(summary.finalState).isEqualTo("accepted_all")
        assertThat(summary.accepted).isEqualTo(1)
        assertThat(summary.rejected).isEqualTo(0)
    }

    @Test
    fun multiple_valid_rows_all_accepted(): Unit = runBlocking {
        val csv = "title,format,publisher\nBook A,hardcover,Pub1\nBook B,paperback,Pub2\nBook C,ebook,Pub3\n"
        val summary = importer.import("multi.csv", streamOf(csv), userId = 1L, userRecentImportsInWindow = 0)
        assertThat(summary.finalState).isEqualTo("accepted_all")
        assertThat(summary.accepted).isEqualTo(3)
    }

    // ── row without title value ────────────────────────────────────────────────

    @Test
    fun row_with_blank_title_is_rejected(): Unit = runBlocking {
        val csv = "title,publisher\n,SomePub\n"
        val summary = importer.import("blank.csv", streamOf(csv), userId = 1L, userRecentImportsInWindow = 0)
        assertThat(summary.rejected).isEqualTo(1)
        assertThat(summary.accepted).isEqualTo(0)
        assertThat(summary.finalState).isEqualTo("rejected_all")
    }

    // ── accepted_partial ───────────────────────────────────────────────────────

    @Test
    fun mix_of_valid_and_invalid_rows_produces_accepted_partial(): Unit = runBlocking {
        val csv = "title,format,publisher\nGood Book,paperback,Pub\n,,MissingTitle\n"
        val summary = importer.import("mixed.csv", streamOf(csv), userId = 1L, userRecentImportsInWindow = 0)
        assertThat(summary.finalState).isEqualTo("accepted_partial")
        assertThat(summary.accepted).isEqualTo(1)
        assertThat(summary.rejected).isEqualTo(1)
    }

    // ── header-only (no data rows) ─────────────────────────────────────────────

    @Test
    fun header_only_csv_produces_rejected_all(): Unit = runBlocking {
        val csv = "title\n"
        val summary = importer.import("header_only.csv", streamOf(csv), userId = 1L, userRecentImportsInWindow = 0)
        assertThat(summary.finalState).isEqualTo("rejected_all")
        assertThat(summary.accepted).isEqualTo(0)
        assertThat(summary.rejected).isEqualTo(0)
    }

    // ── audit events ───────────────────────────────────────────────────────────

    @Test
    fun successful_import_records_audit_events(): Unit = runBlocking {
        val csv = "title,format\nAudit Book,paperback\n"
        importer.import("audit.csv", streamOf(csv), userId = 5L, userRecentImportsInWindow = 0)
        val events = auditDao.allEventsChronological()
        assertThat(events.any { it.action == "import.received" }).isTrue()
        assertThat(events.any { it.action == "import.completed" && it.userId == 5L }).isTrue()
    }
}

// ── Test doubles ──────────────────────────────────────────────────────────────

class CsvTestRecordStub : RecordDao {
    private val records = mutableListOf<MasterRecordEntity>()
    private val ids = AtomicLong(0)

    override suspend fun insert(record: MasterRecordEntity): Long {
        val id = ids.incrementAndGet(); records.add(record.copy(id = id)); return id
    }
    override suspend fun update(record: MasterRecordEntity): Int = 1
    override suspend fun byId(id: Long): MasterRecordEntity? = records.firstOrNull { it.id == id }
    override suspend fun byIsbn13(isbn13: String): MasterRecordEntity? = null
    override suspend fun search(prefix: String, q: String, limit: Int, offset: Int): List<MasterRecordEntity> = emptyList()
    override suspend fun activeCount(): Int = records.size
    override suspend fun insertVersion(version: MasterRecordVersionEntity): Long = ids.incrementAndGet()
    override suspend fun versionsFor(recordId: Long): List<MasterRecordVersionEntity> = emptyList()
}

class CsvTestDuplicateStub : DuplicateDao {
    override suspend fun insert(candidate: DuplicateCandidateEntity): Long = 1
    override suspend fun update(candidate: DuplicateCandidateEntity): Int = 0
    override suspend fun insertDecision(decision: MergeDecisionEntity): Long = 0
    override suspend fun byId(id: Long): DuplicateCandidateEntity? = null
    override suspend fun listByStatus(status: String, limit: Int, offset: Int): List<DuplicateCandidateEntity> = emptyList()
    override suspend fun unresolvedOlderThan(olderThan: Long): Int = 0
}
