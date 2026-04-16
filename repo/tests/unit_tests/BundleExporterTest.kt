package com.eaglepoint.libops.tests

import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.data.db.dao.RecordDao
import com.eaglepoint.libops.data.db.entity.AuditEventEntity
import com.eaglepoint.libops.data.db.entity.MasterRecordEntity
import com.eaglepoint.libops.data.db.entity.MasterRecordVersionEntity
import com.eaglepoint.libops.exports.BundleExporter
import com.eaglepoint.libops.exports.BundleSigner
import com.eaglepoint.libops.tests.fakes.FakeAuditDao
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Tests for [BundleExporter.exportSnapshot] covering file creation, manifest
 * structure, record serialization, audit inclusion/exclusion, and the
 * "export.generated" audit-event side-effect (§9.12, §16).
 *
 * Robolectric is required because [BundleSigner] uses [android.util.Base64]
 * when encoding the signature value into the manifest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BundleExporterTest {

    @get:Rule
    val tmpDir = TemporaryFolder()

    private val clockMs = 1_700_000_000_000L
    private lateinit var recordDao: StubRecordDao
    private lateinit var exportAuditDao: FakeAuditDao   // passed as BundleExporter.auditDao
    private lateinit var loggerAuditDao: FakeAuditDao   // backs the AuditLogger instance
    private lateinit var audit: AuditLogger
    private lateinit var exporter: BundleExporter
    private val keyProvider = BundleSigner.ephemeralKeyProvider()

    @Before
    fun setUp() {
        recordDao = StubRecordDao()
        exportAuditDao = FakeAuditDao()
        loggerAuditDao = FakeAuditDao()
        audit = AuditLogger(loggerAuditDao, clock = { clockMs })
        exporter = BundleExporter(
            recordDao = recordDao,
            auditDao = exportAuditDao,
            signer = BundleSigner(keyProvider),
            audit = audit,
            clock = { clockMs },
        )
    }

    @Test
    fun both_output_files_are_created() = runBlocking {
        val dir = tmpDir.newFolder("bundle_files")
        exporter.exportSnapshot(dir, creatorUserId = 1L, includeAudit = false)
        assertThat(File(dir, "content.json").exists()).isTrue()
        assertThat(File(dir, "manifest.json").exists()).isTrue()
    }

    @Test
    fun manifest_contains_required_fields_and_sha256_matches_result() = runBlocking {
        val dir = tmpDir.newFolder("manifest_check")
        val result = exporter.exportSnapshot(dir, creatorUserId = 42L, includeAudit = false)

        val manifest = JSONObject(File(dir, "manifest.json").readText())
        assertThat(manifest.getString("manifest_version")).isEqualTo("1.0")
        assertThat(manifest.getLong("creator_user_id")).isEqualTo(42L)

        val files = manifest.getJSONArray("content_files")
        assertThat(files.length()).isEqualTo(1)
        val fileEntry = files.getJSONObject(0)
        assertThat(fileEntry.getString("path")).isEqualTo("content.json")
        assertThat(fileEntry.getString("sha256")).isEqualTo(result.sha256)

        val sig = manifest.getJSONObject("signature")
        assertThat(sig.getString("value")).isNotEmpty()
        assertThat(sig.getString("algorithm")).isEqualTo(BundleSigner.ALGORITHM)
    }

    @Test
    fun all_inserted_records_appear_in_content_file() = runBlocking {
        recordDao.addRecord("Alpha")
        recordDao.addRecord("Beta")
        recordDao.addRecord("Gamma")

        val dir = tmpDir.newFolder("records_export")
        exporter.exportSnapshot(dir, creatorUserId = 1L, includeAudit = false)

        val content = JSONObject(File(dir, "content.json").readText())
        assertThat(content.getJSONArray("records").length()).isEqualTo(3)
    }

    @Test
    fun audit_section_present_when_include_audit_is_true() = runBlocking {
        exportAuditDao.insertEvent(
            AuditEventEntity(
                correlationId = "test-corr", userId = 1L,
                action = "test.action", targetType = "test", targetId = "1",
                severity = "info", reason = null, payloadJson = null,
                previousEventHash = null, eventHash = "fakehash", createdAt = clockMs,
            )
        )

        val dir = tmpDir.newFolder("audit_on")
        exporter.exportSnapshot(dir, creatorUserId = 1L, includeAudit = true)

        val content = JSONObject(File(dir, "content.json").readText())
        assertThat(content.has("audit")).isTrue()
        assertThat(content.getJSONArray("audit").length()).isGreaterThan(0)
    }

    @Test
    fun audit_section_absent_when_include_audit_is_false() = runBlocking {
        val dir = tmpDir.newFolder("audit_off")
        exporter.exportSnapshot(dir, creatorUserId = 1L, includeAudit = false)

        val content = JSONObject(File(dir, "content.json").readText())
        assertThat(content.has("audit")).isFalse()
    }

    @Test
    fun export_generates_audit_event_with_export_generated_action() = runBlocking {
        val dir = tmpDir.newFolder("audit_event")
        exporter.exportSnapshot(dir, creatorUserId = 7L, includeAudit = false)

        val events = loggerAuditDao.allEventsChronological()
        assertThat(events).hasSize(1)
        assertThat(events[0].action).isEqualTo("export.generated")
        assertThat(events[0].userId).isEqualTo(7L)
    }

    @Test
    fun empty_snapshot_produces_valid_bundle_with_zero_records() = runBlocking {
        val dir = tmpDir.newFolder("empty_bundle")
        val result = exporter.exportSnapshot(dir, creatorUserId = 1L, includeAudit = false)

        val content = JSONObject(File(dir, "content.json").readText())
        assertThat(content.getJSONArray("records").length()).isEqualTo(0)
        assertThat(result.sha256).isNotEmpty()
        assertThat(result.manifestPath).endsWith("manifest.json")
        assertThat(result.contentPath).endsWith("content.json")
    }
}

// ── Stub RecordDao ─────────────────────────────────────────────────────────────

/**
 * In-memory stub that exposes [addRecord] for test setup and implements
 * the paginated [search] called by [BundleExporter]. Other methods are
 * unused by the exporter and throw [UnsupportedOperationException].
 */
class StubRecordDao : RecordDao {
    private val records = mutableListOf<MasterRecordEntity>()
    private val ids = AtomicLong(0)

    fun addRecord(title: String): Long {
        val id = ids.incrementAndGet()
        records.add(
            MasterRecordEntity(
                id = id, title = title, titleNormalized = title.lowercase(),
                publisher = null, pubDate = null, format = null, category = "book",
                isbn10 = null, isbn13 = null, language = null, notes = null,
                status = "active", sourceProvenanceJson = null,
                createdByUserId = 1L, createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_000_000L,
            )
        )
        return id
    }

    override suspend fun search(prefix: String, q: String, limit: Int, offset: Int): List<MasterRecordEntity> =
        records.drop(offset).take(limit)

    override suspend fun insert(record: MasterRecordEntity): Long = throw UnsupportedOperationException()
    override suspend fun update(record: MasterRecordEntity): Int = throw UnsupportedOperationException()
    override suspend fun byId(id: Long): MasterRecordEntity? = throw UnsupportedOperationException()
    override suspend fun byIsbn13(isbn13: String): MasterRecordEntity? = throw UnsupportedOperationException()
    override suspend fun activeCount(): Int = throw UnsupportedOperationException()
    override suspend fun insertVersion(version: MasterRecordVersionEntity): Long = throw UnsupportedOperationException()
    override suspend fun versionsFor(recordId: Long): List<MasterRecordVersionEntity> = throw UnsupportedOperationException()
}
