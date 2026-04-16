package com.eaglepoint.libops.tests

import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.data.db.dao.DuplicateDao
import com.eaglepoint.libops.data.db.dao.RecordDao
import com.eaglepoint.libops.data.db.entity.DuplicateCandidateEntity
import com.eaglepoint.libops.data.db.entity.MasterRecordEntity
import com.eaglepoint.libops.data.db.entity.MasterRecordVersionEntity
import com.eaglepoint.libops.data.db.entity.MergeDecisionEntity
import com.eaglepoint.libops.imports.JsonImporter
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
 * Tests for [JsonImporter.import] covering JSON parsing branches, validation,
 * rate-limiting, duplicate detection surfacing, and final-state derivation
 * (§9.7, §9.13).
 *
 * Robolectric is required because [JsonImporter] and its error-serialization
 * path use [org.json.JSONArray] / [org.json.JSONObject], which need a real
 * implementation (the Android unit-test stub jar returns null from toString()).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class JsonImporterTest {

    private lateinit var importDao: FakeImportDao
    private lateinit var auditDao: FakeAuditDao
    private lateinit var recordDao: JsonImportRecordStub
    private lateinit var duplicateDao: JsonImportDuplicateStub
    private lateinit var importer: JsonImporter

    private val clockMs = 1_700_000_000_000L

    @Before
    fun setUp() {
        importDao = FakeImportDao()
        auditDao = FakeAuditDao()
        recordDao = JsonImportRecordStub()
        duplicateDao = JsonImportDuplicateStub()
        importer = JsonImporter(
            importDao = importDao,
            recordDao = recordDao,
            duplicateDao = duplicateDao,
            audit = AuditLogger(auditDao, clock = { clockMs }),
            clock = { clockMs },
        )
    }

    private fun streamOf(json: String): ByteArrayInputStream =
        ByteArrayInputStream(json.toByteArray(Charsets.UTF_8))

    // ── rate limiting ──────────────────────────────────────────────────────────

    @Test(expected = IllegalStateException::class)
    fun rate_limit_exceeded_throws_illegal_state(): Unit = runBlocking {
        // ImportRateLimiter.DEFAULT_LIMIT = 30 — passing 30 exceeds the limit
        importer.import("test.json", streamOf("[]"), userId = 1L, userRecentImportsInWindow = 30)
    }

    @Test
    fun rate_limit_exceeded_records_audit_event(): Unit = runBlocking {
        try {
            importer.import("test.json", streamOf("[]"), userId = 7L, userRecentImportsInWindow = 30)
        } catch (_: IllegalStateException) {}
        val events = auditDao.allEventsChronological()
        assertThat(events.any { it.action == "import.rate_limited" && it.userId == 7L }).isTrue()
    }

    // ── invalid JSON ───────────────────────────────────────────────────────────

    @Test
    fun invalid_json_produces_rejected_invalid_bundle(): Unit = runBlocking {
        val summary = importer.import("bad.json", streamOf("{{{not json"), userId = 1L, userRecentImportsInWindow = 0)
        assertThat(summary.finalState).isEqualTo("rejected_invalid_bundle")
        assertThat(summary.accepted).isEqualTo(0)
        assertThat(summary.batchId).isGreaterThan(0L)
    }

    // ── missing records key ────────────────────────────────────────────────────

    @Test
    fun object_without_records_key_produces_rejected_validation_failure(): Unit = runBlocking {
        val summary = importer.import(
            "missing_records.json",
            streamOf("""{"schema_version":"1.0","title":"oops"}"""),
            userId = 1L, userRecentImportsInWindow = 0,
        )
        assertThat(summary.finalState).isEqualTo("rejected_validation_failure")
        assertThat(summary.schemaVersion).isEqualTo("1.0")
    }

    // ── non-array non-object root ──────────────────────────────────────────────

    @Test
    fun string_root_produces_rejected_validation_failure(): Unit = runBlocking {
        val summary = importer.import("str.json", streamOf("\"hello\""), userId = 1L, userRecentImportsInWindow = 0)
        assertThat(summary.finalState).isEqualTo("rejected_validation_failure")
    }

    // ── empty array ────────────────────────────────────────────────────────────

    @Test
    fun empty_json_array_produces_rejected_all(): Unit = runBlocking {
        val summary = importer.import("empty.json", streamOf("[]"), userId = 1L, userRecentImportsInWindow = 0)
        assertThat(summary.finalState).isEqualTo("rejected_all")
        assertThat(summary.accepted).isEqualTo(0)
        assertThat(summary.rejected).isEqualTo(0)
        assertThat(summary.schemaVersion).isNull()
    }

    // ── valid record — array format ────────────────────────────────────────────

    @Test
    fun valid_single_record_in_array_produces_accepted_all(): Unit = runBlocking {
        val json = """[{"title":"Great Book","category":"book","format":"paperback"}]"""
        val summary = importer.import("one.json", streamOf(json), userId = 1L, userRecentImportsInWindow = 0)
        assertThat(summary.finalState).isEqualTo("accepted_all")
        assertThat(summary.accepted).isEqualTo(1)
        assertThat(summary.rejected).isEqualTo(0)
        assertThat(recordDao.insertedRecords).hasSize(1)
        assertThat(recordDao.insertedRecords[0].title).isEqualTo("Great Book")
    }

    // ── valid record — object format with schema_version ──────────────────────

    @Test
    fun object_format_with_schema_version_accepted(): Unit = runBlocking {
        val json = """{"schema_version":"2.1","records":[{"title":"Wrapped Book","category":"other"}]}"""
        val summary = importer.import("wrapped.json", streamOf(json), userId = 1L, userRecentImportsInWindow = 0)
        assertThat(summary.finalState).isEqualTo("accepted_all")
        assertThat(summary.accepted).isEqualTo(1)
        assertThat(summary.schemaVersion).isEqualTo("2.1")
    }

    // ── row without title ──────────────────────────────────────────────────────

    @Test
    fun row_without_title_is_rejected(): Unit = runBlocking {
        val json = """[{"publisher":"Some Publisher","isbn13":"9780000000002"}]"""
        val summary = importer.import("no_title.json", streamOf(json), userId = 1L, userRecentImportsInWindow = 0)
        assertThat(summary.rejected).isEqualTo(1)
        assertThat(summary.accepted).isEqualTo(0)
        assertThat(summary.finalState).isEqualTo("rejected_all")
    }

    // ── accepted_partial ───────────────────────────────────────────────────────

    @Test
    fun mix_of_valid_and_invalid_rows_produces_accepted_partial(): Unit = runBlocking {
        val json = """[
            {"title":"Valid Book","category":"book","format":"paperback"},
            {"publisher":"No Title Here"}
        ]"""
        val summary = importer.import("mixed.json", streamOf(json), userId = 1L, userRecentImportsInWindow = 0)
        assertThat(summary.finalState).isEqualTo("accepted_partial")
        assertThat(summary.accepted).isEqualTo(1)
        assertThat(summary.rejected).isEqualTo(1)
    }

    // ── audit events ───────────────────────────────────────────────────────────

    @Test
    fun successful_import_records_received_and_completed_audit_events(): Unit = runBlocking {
        val json = """[{"title":"Audit Book","format":"hardcover"}]"""
        importer.import("audit.json", streamOf(json), userId = 5L, userRecentImportsInWindow = 0)

        val events = auditDao.allEventsChronological()
        assertThat(events.any { it.action == "import.received" }).isTrue()
        assertThat(events.any { it.action == "import.completed" && it.userId == 5L }).isTrue()
    }
}

// ── Test doubles ──────────────────────────────────────────────────────────────

/**
 * In-memory [RecordDao] stub. Tracks inserted records for assertion;
 * [byIsbn13] returns null by default so duplicate detection finds no match.
 */
class JsonImportRecordStub : RecordDao {
    val insertedRecords = mutableListOf<MasterRecordEntity>()
    private val ids = AtomicLong(0)

    override suspend fun insert(record: MasterRecordEntity): Long {
        val id = ids.incrementAndGet()
        insertedRecords.add(record.copy(id = id))
        return id
    }

    override suspend fun update(record: MasterRecordEntity): Int { return 1 }
    override suspend fun byId(id: Long): MasterRecordEntity? = insertedRecords.firstOrNull { it.id == id }
    override suspend fun byIsbn13(isbn13: String): MasterRecordEntity? = null
    override suspend fun search(prefix: String, q: String, limit: Int, offset: Int): List<MasterRecordEntity> = emptyList()
    override suspend fun activeCount(): Int = insertedRecords.size
    override suspend fun insertVersion(version: MasterRecordVersionEntity): Long = ids.incrementAndGet()
    override suspend fun versionsFor(recordId: Long): List<MasterRecordVersionEntity> = emptyList()
}

/**
 * In-memory [DuplicateDao] stub. Tracks inserted candidates for assertion.
 */
class JsonImportDuplicateStub : DuplicateDao {
    val inserted = mutableListOf<DuplicateCandidateEntity>()
    private val ids = AtomicLong(0)

    override suspend fun insert(candidate: DuplicateCandidateEntity): Long {
        val id = ids.incrementAndGet()
        inserted.add(candidate.copy(id = id))
        return id
    }

    override suspend fun update(candidate: DuplicateCandidateEntity): Int = 0
    override suspend fun insertDecision(decision: MergeDecisionEntity): Long = 0L
    override suspend fun byId(id: Long): DuplicateCandidateEntity? = null
    override suspend fun listByStatus(status: String, limit: Int, offset: Int): List<DuplicateCandidateEntity> = emptyList()
    override suspend fun unresolvedOlderThan(olderThan: Long): Int = 0
}
