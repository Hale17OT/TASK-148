package com.eaglepoint.libops.tests

import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.data.db.dao.DuplicateDao
import com.eaglepoint.libops.data.db.dao.RecordDao
import com.eaglepoint.libops.data.db.entity.DuplicateCandidateEntity
import com.eaglepoint.libops.data.db.entity.MasterRecordEntity
import com.eaglepoint.libops.data.db.entity.MasterRecordVersionEntity
import com.eaglepoint.libops.data.db.entity.MergeDecisionEntity
import com.eaglepoint.libops.domain.auth.Authorizer
import com.eaglepoint.libops.domain.auth.Capabilities
import com.eaglepoint.libops.domain.orchestration.ImportService
import com.eaglepoint.libops.tests.fakes.FakeAuditDao
import com.eaglepoint.libops.tests.fakes.FakeImportDao
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyPairGenerator

/**
 * Tests for [ImportService] verifying that [Capabilities.IMPORTS_RUN] is
 * enforced for every entry point before any importer code is invoked (§11).
 *
 * Pure JVM — the [SecurityException] is thrown at the authorization boundary
 * before any importer or DAO is accessed.
 */
class ImportServiceTest {

    private lateinit var importDao: FakeImportDao
    private lateinit var auditDao: FakeAuditDao

    @Before
    fun setUp() {
        importDao = FakeImportDao()
        auditDao = FakeAuditDao()
    }

    private fun serviceWithout(cap: String = Capabilities.IMPORTS_RUN): ImportService =
        ImportService(
            authz = Authorizer(emptySet()),
            importDao = importDao,
            recordDao = ImportSvcNoopRecordDao(),
            duplicateDao = ImportSvcNoopDuplicateDao(),
            audit = AuditLogger(auditDao),
        )

    // ── authorization boundary: all three methods throw without IMPORTS_RUN ──

    @Test(expected = SecurityException::class)
    fun import_csv_without_imports_run_throws_security_exception(): Unit = runBlocking {
        serviceWithout().importCsv(
            filename = "test.csv",
            source = ByteArrayInputStream(ByteArray(0)),
            userId = 1L,
            userRecentImportsInWindow = 0,
        )
    }

    @Test(expected = SecurityException::class)
    fun import_json_without_imports_run_throws_security_exception(): Unit = runBlocking {
        serviceWithout().importJson(
            filename = "test.json",
            source = ByteArrayInputStream(ByteArray(0)),
            userId = 1L,
            userRecentImportsInWindow = 0,
        )
    }

    @Test(expected = SecurityException::class)
    fun import_bundle_without_imports_run_throws_security_exception(): Unit = runBlocking {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(1024)
        val publicKey = kpg.generateKeyPair().public
        serviceWithout().importBundle(
            bundleDir = File(System.getProperty("java.io.tmpdir"), "nonexistent_bundle"),
            trustedKeys = listOf(publicKey),
            userId = 1L,
            userRecentImportsInWindow = 0,
        )
    }

    // ── inner no-op stubs: thrown before DAOs are ever reached ────────────────

    private class ImportSvcNoopRecordDao : RecordDao {
        override suspend fun insert(record: MasterRecordEntity): Long = throw UnsupportedOperationException()
        override suspend fun update(record: MasterRecordEntity): Int = throw UnsupportedOperationException()
        override suspend fun byId(id: Long): MasterRecordEntity? = throw UnsupportedOperationException()
        override suspend fun byIsbn13(isbn13: String): MasterRecordEntity? = throw UnsupportedOperationException()
        override suspend fun search(prefix: String, q: String, limit: Int, offset: Int): List<MasterRecordEntity> = throw UnsupportedOperationException()
        override suspend fun activeCount(): Int = throw UnsupportedOperationException()
        override suspend fun insertVersion(version: MasterRecordVersionEntity): Long = throw UnsupportedOperationException()
        override suspend fun versionsFor(recordId: Long): List<MasterRecordVersionEntity> = throw UnsupportedOperationException()
    }

    private class ImportSvcNoopDuplicateDao : DuplicateDao {
        override suspend fun insert(candidate: DuplicateCandidateEntity): Long = throw UnsupportedOperationException()
        override suspend fun update(candidate: DuplicateCandidateEntity): Int = throw UnsupportedOperationException()
        override suspend fun insertDecision(decision: MergeDecisionEntity): Long = throw UnsupportedOperationException()
        override suspend fun byId(id: Long): DuplicateCandidateEntity? = throw UnsupportedOperationException()
        override suspend fun listByStatus(status: String, limit: Int, offset: Int): List<DuplicateCandidateEntity> = throw UnsupportedOperationException()
        override suspend fun unresolvedOlderThan(olderThan: Long): Int = throw UnsupportedOperationException()
    }
}
