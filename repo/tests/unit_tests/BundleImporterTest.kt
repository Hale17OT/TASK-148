package com.eaglepoint.libops.tests

import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.data.db.dao.DuplicateDao
import com.eaglepoint.libops.data.db.dao.RecordDao
import com.eaglepoint.libops.data.db.entity.DuplicateCandidateEntity
import com.eaglepoint.libops.data.db.entity.MasterRecordEntity
import com.eaglepoint.libops.data.db.entity.MasterRecordVersionEntity
import com.eaglepoint.libops.data.db.entity.MergeDecisionEntity
import com.eaglepoint.libops.imports.BundleImporter
import com.eaglepoint.libops.tests.fakes.FakeAuditDao
import com.eaglepoint.libops.tests.fakes.FakeImportDao
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Tests for [BundleImporter] covering rate-limiting enforcement,
 * verification failure paths, and bundle entity persistence.
 *
 * Note: tests that exercise the full BundleVerifier signature path require
 * `android.util.Base64` and belong in instrumented tests. This suite covers
 * the importer's own logic paths using minimal bundle files.
 */
class BundleImporterTest {

    @get:Rule
    val tmpDir = TemporaryFolder()

    private lateinit var importDao: FakeImportDao
    private lateinit var recordDao: StubRecordDao
    private lateinit var duplicateDao: StubDuplicateDao
    private lateinit var auditDao: FakeAuditDao
    private lateinit var audit: AuditLogger
    private lateinit var importer: BundleImporter
    private var clockMs = 1_700_000_000_000L

    @Before
    fun setUp() {
        importDao = FakeImportDao()
        recordDao = StubRecordDao()
        duplicateDao = StubDuplicateDao()
        auditDao = FakeAuditDao()
        audit = AuditLogger(auditDao) { clockMs }
        importer = BundleImporter(importDao, recordDao, duplicateDao, audit, clock = { clockMs })
    }

    @Test
    fun rate_limited_import_is_rejected() = runBlocking {
        val dir = tmpDir.newFolder("bundle")
        // Pass 30 (the limit) as recent count — rate limiter should block
        val result = importer.import(dir, fakePublicKey(), userId = 1L, userRecentImportsInWindow = 30)
        assertThat(result).isEqualTo(BundleImporter.ImportResult.RateLimited)
    }

    @Test
    fun rate_limit_at_29_allows_import() = runBlocking {
        val dir = tmpDir.newFolder("bundle-29")
        // 29 is below the 30 limit — should pass rate check (but fail verification since no manifest)
        val result = importer.import(dir, fakePublicKey(), userId = 1L, userRecentImportsInWindow = 29)
        // Should proceed past rate limit to verification, which fails because no manifest.json
        assertThat(result).isInstanceOf(BundleImporter.ImportResult.VerificationFailed::class.java)
    }

    @Test
    fun missing_manifest_fails_verification() = runBlocking {
        val dir = tmpDir.newFolder("bundle-empty")
        val result = importer.import(dir, fakePublicKey(), userId = 1L, userRecentImportsInWindow = 0)
        assertThat(result).isInstanceOf(BundleImporter.ImportResult.VerificationFailed::class.java)
        val msg = (result as BundleImporter.ImportResult.VerificationFailed).reason
        assertThat(msg).contains("manifest")
    }

    @Test
    fun missing_content_file_fails_verification() = runBlocking {
        // Manifest references content.json but that file doesn't exist
        val dir = tmpDir.newFolder("bundle-no-content")
        // Write manifest with explicit string to avoid org.json JVM compat issues
        val manifestJson = """
        {
          "manifest_version": "1.0",
          "content_files": [
            { "path": "content.json", "sha256": "abc" }
          ]
        }
        """.trimIndent()
        File(dir, "manifest.json").writeBytes(manifestJson.toByteArray(Charsets.UTF_8))
        val result = importer.import(dir, fakePublicKey(), userId = 1L, userRecentImportsInWindow = 0)
        assertThat(result).isInstanceOf(BundleImporter.ImportResult.VerificationFailed::class.java)
    }

    @Test
    fun rate_limit_audit_event_recorded() = runBlocking {
        val dir = tmpDir.newFolder("bundle-rl")
        importer.import(dir, fakePublicKey(), userId = 1L, userRecentImportsInWindow = 30)
        val events = auditDao.allEventsChronological()
        assertThat(events.any { it.action == "bundle_import.rate_limited" }).isTrue()
    }

    @Test
    fun verification_failure_audit_event_recorded() = runBlocking {
        val dir = tmpDir.newFolder("bundle-vf")
        // No manifest — will fail verification
        importer.import(dir, fakePublicKey(), userId = 1L, userRecentImportsInWindow = 0)
        val events = auditDao.allEventsChronological()
        assertThat(events.any { it.action == "bundle_import.rejected" }).isTrue()
    }

    /** Dummy RSA public key for tests that only reach the verification step. */
    private fun fakePublicKey(): java.security.PublicKey {
        val kpg = java.security.KeyPairGenerator.getInstance("RSA")
        kpg.initialize(1024)
        return kpg.generateKeyPair().public
    }

    // Minimal stubs for DAO interfaces used by BundleImporter
    class StubRecordDao : RecordDao {
        private val records = linkedMapOf<Long, MasterRecordEntity>()
        private val ids = AtomicLong(0)

        override suspend fun insert(record: MasterRecordEntity): Long {
            val id = ids.incrementAndGet(); records[id] = record.copy(id = id); return id
        }
        override suspend fun update(record: MasterRecordEntity): Int = 1
        override suspend fun byId(id: Long): MasterRecordEntity? = records[id]
        override suspend fun byIsbn13(isbn13: String): MasterRecordEntity? =
            records.values.firstOrNull { it.isbn13 == isbn13 }
        override suspend fun search(prefix: String, q: String, limit: Int, offset: Int): List<MasterRecordEntity> = emptyList()
        override suspend fun activeCount(): Int = records.size
        override suspend fun insertVersion(version: MasterRecordVersionEntity): Long = 1
        override suspend fun versionsFor(recordId: Long): List<MasterRecordVersionEntity> = emptyList()
    }

    class StubDuplicateDao : DuplicateDao {
        override suspend fun insert(candidate: DuplicateCandidateEntity): Long = 1
        override suspend fun update(candidate: DuplicateCandidateEntity): Int = 1
        override suspend fun insertDecision(decision: MergeDecisionEntity): Long = 1
        override suspend fun byId(id: Long): DuplicateCandidateEntity? = null
        override suspend fun listByStatus(status: String, limit: Int, offset: Int): List<DuplicateCandidateEntity> = emptyList()
        override suspend fun unresolvedOlderThan(olderThan: Long): Int = 0
    }
}
