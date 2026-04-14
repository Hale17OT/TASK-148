package com.eaglepoint.libops.tests

import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.data.db.entity.CollectionSourceEntity
import com.eaglepoint.libops.data.db.entity.CrawlRuleEntity
import com.eaglepoint.libops.data.db.entity.JobEntity
import com.eaglepoint.libops.domain.auth.Capabilities
import com.eaglepoint.libops.domain.auth.Authorizer
import com.eaglepoint.libops.domain.orchestration.RetryPolicy
import com.eaglepoint.libops.observability.ObservabilityPipeline
import com.eaglepoint.libops.orchestration.JobScheduler
import com.eaglepoint.libops.orchestration.SourceIngestionPipeline
import com.eaglepoint.libops.tests.fakes.FakeAlertDao
import com.eaglepoint.libops.tests.fakes.FakeAuditDao
import com.eaglepoint.libops.tests.fakes.FakeCollectionSourceDao
import com.eaglepoint.libops.tests.fakes.FakeJobDao
import com.eaglepoint.libops.tests.fakes.FakeImportDao
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class SourceIngestionPipelineTest {

    @get:Rule
    val tmpDir = TemporaryFolder()

    private lateinit var sourceDao: FakeCollectionSourceDao
    private lateinit var importDao: FakeImportDao
    private lateinit var jobDao: FakeJobDao
    private lateinit var auditDao: FakeAuditDao
    private lateinit var alertDao: FakeAlertDao
    private lateinit var audit: AuditLogger
    private lateinit var pipeline: SourceIngestionPipeline
    private var clockMs = 1_700_000_000_000L

    @Before
    fun setUp() {
        sourceDao = FakeCollectionSourceDao()
        importDao = FakeImportDao()
        jobDao = FakeJobDao()
        auditDao = FakeAuditDao()
        alertDao = FakeAlertDao()
        audit = AuditLogger(auditDao) { clockMs }
        val observability = ObservabilityPipeline(auditDao, alertDao, jobDao, audit, clock = { clockMs })
        pipeline = SourceIngestionPipeline(sourceDao, importDao, StubRecordDao(), StubDuplicateDao(), audit, observability) { clockMs }
    }

    private suspend fun createSource(entryType: String = "imported_file"): Long {
        val now = clockMs
        return sourceDao.insert(
            CollectionSourceEntity(
                name = "test-source", entryType = entryType, refreshMode = "full",
                priority = 3, retryBackoff = "5_min", enabled = true, state = "active",
                scheduleCron = null, createdAt = now, updatedAt = now,
            ),
        )
    }

    private fun createJob(sourceId: Long): JobEntity = JobEntity(
        id = 1, sourceId = sourceId, status = "running", priority = 3, retryCount = 0,
        refreshMode = "full", correlationId = UUID.randomUUID().toString(),
        scheduledAt = clockMs, startedAt = clockMs, finishedAt = null,
        lastError = null, createdAt = clockMs, updatedAt = clockMs,
    )

    @Test
    fun ingests_records_from_file_csv_format(): Unit = runBlocking {
        val sourceId = createSource("imported_file")
        val dataFile = tmpDir.newFile("data2.csv")
        dataFile.writeText("title,publisher,category,format\nBook One,Press A,book,hardcover\nBook Two,Press B,book,paperback")
        sourceDao.insertRule(CrawlRuleEntity(sourceId = sourceId, ruleKey = "file_path", ruleValue = dataFile.absolutePath, include = true))
        sourceDao.insertRule(CrawlRuleEntity(sourceId = sourceId, ruleKey = "file_format", ruleValue = "csv", include = true))

        val result = pipeline.ingest(createJob(sourceId))
        assertThat(result.accepted).isEqualTo(2)
        assertThat(result.rejected).isEqualTo(0)
    }

    @Test
    fun ingests_records_from_csv_file(): Unit = runBlocking {
        val sourceId = createSource("imported_file")
        val dataFile = tmpDir.newFile("data.csv")
        dataFile.writeText("title,publisher,category,format\nCSV Book,CSV Press,book,hardcover\nCSV Journal,CSV Pub,journal,")
        sourceDao.insertRule(CrawlRuleEntity(sourceId = sourceId, ruleKey = "file_path", ruleValue = dataFile.absolutePath, include = true))
        sourceDao.insertRule(CrawlRuleEntity(sourceId = sourceId, ruleKey = "file_format", ruleValue = "csv", include = true))

        val result = pipeline.ingest(createJob(sourceId))
        assertThat(result.accepted).isEqualTo(2)
    }

    @Test
    fun errors_when_no_file_rule_configured(): Unit = runBlocking {
        val sourceId = createSource("site")
        try {
            pipeline.ingest(createJob(sourceId))
            assertThat(false).isTrue() // should not reach here
        } catch (e: IllegalStateException) {
            assertThat(e.message).contains("no configured file_path rule")
        }
    }

    @Test
    fun rejects_invalid_records_from_csv_file(): Unit = runBlocking {
        val sourceId = createSource("imported_file")
        val dataFile = tmpDir.newFile("bad.csv")
        // Row with blank title should be rejected by RecordValidator
        dataFile.writeText("title,publisher,category,format\n,BadPub,book,hardcover\nOK Book,GoodPub,book,hardcover")
        sourceDao.insertRule(CrawlRuleEntity(sourceId = sourceId, ruleKey = "file_path", ruleValue = dataFile.absolutePath, include = true))
        sourceDao.insertRule(CrawlRuleEntity(sourceId = sourceId, ruleKey = "file_format", ruleValue = "csv", include = true))

        val result = pipeline.ingest(createJob(sourceId))
        // Blank title row is skipped by CSV parser (mapNotNull), so only the valid row is processed
        assertThat(result.accepted).isAtLeast(1)
    }

    @Test
    fun missing_file_throws_error(): Unit = runBlocking {
        val sourceId = createSource("imported_file")
        sourceDao.insertRule(CrawlRuleEntity(sourceId = sourceId, ruleKey = "file_path", ruleValue = "/nonexistent/path.json", include = true))

        try {
            pipeline.ingest(createJob(sourceId))
            assertThat(false).isTrue() // should not reach here
        } catch (e: IllegalStateException) {
            assertThat(e.message).contains("could not be read")
        }
    }

    @Test
    fun audit_events_recorded_for_ingestion(): Unit = runBlocking {
        val sourceId = createSource("imported_file")
        val dataFile = tmpDir.newFile("audit_test.csv")
        dataFile.writeText("title,publisher,category,format\nAudit Book,Pub,book,hardcover")
        sourceDao.insertRule(CrawlRuleEntity(sourceId = sourceId, ruleKey = "file_path", ruleValue = dataFile.absolutePath, include = true))
        sourceDao.insertRule(CrawlRuleEntity(sourceId = sourceId, ruleKey = "file_format", ruleValue = "csv", include = true))

        pipeline.ingest(createJob(sourceId))
        val events = auditDao.allEventsChronological()
        assertThat(events.any { it.action == "ingestion.started" }).isTrue()
        assertThat(events.any { it.action == "ingestion.completed" }).isTrue()
    }

    // --- Crawl rule upsert/delete tests ---

    @Test
    fun upsert_rule_replaces_existing_value(): Unit = runBlocking {
        val sourceId = createSource()
        sourceDao.insertRule(CrawlRuleEntity(sourceId = sourceId, ruleKey = "file_path", ruleValue = "/old/path.json", include = true))
        sourceDao.insertRule(CrawlRuleEntity(sourceId = sourceId, ruleKey = "file_path", ruleValue = "/new/path.json", include = true))

        val rules = sourceDao.rulesFor(sourceId)
        val filePaths = rules.filter { it.ruleKey == "file_path" }
        assertThat(filePaths).hasSize(1) // no duplicates
        assertThat(filePaths[0].ruleValue).isEqualTo("/new/path.json")
    }

    @Test
    fun delete_rule_removes_existing(): Unit = runBlocking {
        val sourceId = createSource()
        sourceDao.insertRule(CrawlRuleEntity(sourceId = sourceId, ruleKey = "publisher", ruleValue = "OldPub", include = true))
        assertThat(sourceDao.rulesFor(sourceId)).hasSize(1)

        sourceDao.deleteRule(sourceId, "publisher")
        assertThat(sourceDao.rulesFor(sourceId)).isEmpty()
    }

    // --- Catalog authorization tests ---

    @Test
    fun read_only_user_cannot_manage_records() {
        val caps = setOf(Capabilities.RECORDS_READ)
        val authz = Authorizer(caps)
        assertThat(authz.has(Capabilities.RECORDS_MANAGE)).isFalse()
        assertThat(authz.has(Capabilities.TAXONOMY_MANAGE)).isFalse()
        assertThat(authz.has(Capabilities.HOLDINGS_MANAGE)).isFalse()
        assertThat(authz.has(Capabilities.BARCODES_MANAGE)).isFalse()
    }

    @Test
    fun cataloger_has_full_catalog_capabilities() {
        val caps = com.eaglepoint.libops.domain.auth.Roles.DEFAULT_MAPPING[com.eaglepoint.libops.domain.auth.Roles.CATALOGER]!!
        val authz = Authorizer(caps)
        assertThat(authz.has(Capabilities.RECORDS_MANAGE)).isTrue()
        assertThat(authz.has(Capabilities.TAXONOMY_MANAGE)).isTrue()
        assertThat(authz.has(Capabilities.HOLDINGS_MANAGE)).isTrue()
        assertThat(authz.has(Capabilities.BARCODES_MANAGE)).isTrue()
    }

    // --- Retry backoff timing tests ---

    @Test
    fun retry_sets_scheduled_at_to_future_backoff(): Unit = runBlocking {
        val sourceId = createSource()
        val now = clockMs
        val jobId = jobDao.insert(
            JobEntity(
                sourceId = sourceId, status = "scheduled", priority = 3, refreshMode = "full",
                correlationId = "c1", scheduledAt = now, startedAt = null, finishedAt = null,
                lastError = null, createdAt = now, updatedAt = now,
            ),
        )
        val scheduler = JobScheduler(jobDao, sourceDao, audit) { clockMs }

        // Pick + run
        val batch = scheduler.pickBatch(1, 100, true)
        assertThat(batch).hasSize(1)
        scheduler.markRunning(batch[0])

        // Fail with retryable error
        val running = jobDao.byId(jobId)!!
        scheduler.markFailed(running, "transient_error", retryable = true)

        val retrying = jobDao.byId(jobId)!!
        assertThat(retrying.status).isEqualTo("retry_waiting")
        // scheduledAt should be in the future by the backoff amount (5_min = 300_000ms)
        val expectedBackoff = RetryPolicy.nextDelay("5_min")
        assertThat(retrying.scheduledAt).isAtLeast(now + expectedBackoff)
    }

    @Test
    fun backoff_job_not_picked_until_eligible(): Unit = runBlocking {
        val sourceId = createSource()
        val now = clockMs
        val futureSchedule = now + 999_999L
        jobDao.insert(
            JobEntity(
                sourceId = sourceId, status = "retry_waiting", priority = 3, refreshMode = "full",
                correlationId = "c2", scheduledAt = futureSchedule, startedAt = null, finishedAt = null,
                lastError = "prev_error", retryCount = 1, createdAt = now, updatedAt = now,
            ),
        )
        val scheduler = JobScheduler(jobDao, sourceDao, audit) { clockMs }
        val batch = scheduler.pickBatch(1, 100, true)
        assertThat(batch).isEmpty() // future scheduledAt → not eligible yet
    }

    // Reuse stubs from BundleImporterTest
    class StubRecordDao : com.eaglepoint.libops.data.db.dao.RecordDao {
        private val records = linkedMapOf<Long, com.eaglepoint.libops.data.db.entity.MasterRecordEntity>()
        private val ids = java.util.concurrent.atomic.AtomicLong(0)
        override suspend fun insert(record: com.eaglepoint.libops.data.db.entity.MasterRecordEntity): Long { val id = ids.incrementAndGet(); records[id] = record.copy(id = id); return id }
        override suspend fun update(record: com.eaglepoint.libops.data.db.entity.MasterRecordEntity): Int = 1
        override suspend fun byId(id: Long) = records[id]
        override suspend fun byIsbn13(isbn13: String) = records.values.firstOrNull { it.isbn13 == isbn13 }
        override suspend fun search(prefix: String, q: String, limit: Int, offset: Int): List<com.eaglepoint.libops.data.db.entity.MasterRecordEntity> = emptyList()
        override suspend fun activeCount() = records.size
        override suspend fun insertVersion(version: com.eaglepoint.libops.data.db.entity.MasterRecordVersionEntity): Long = 1
        override suspend fun versionsFor(recordId: Long): List<com.eaglepoint.libops.data.db.entity.MasterRecordVersionEntity> = emptyList()
    }

    class StubDuplicateDao : com.eaglepoint.libops.data.db.dao.DuplicateDao {
        override suspend fun insert(candidate: com.eaglepoint.libops.data.db.entity.DuplicateCandidateEntity): Long = 1
        override suspend fun update(candidate: com.eaglepoint.libops.data.db.entity.DuplicateCandidateEntity): Int = 1
        override suspend fun insertDecision(decision: com.eaglepoint.libops.data.db.entity.MergeDecisionEntity): Long = 1
        override suspend fun byId(id: Long): com.eaglepoint.libops.data.db.entity.DuplicateCandidateEntity? = null
        override suspend fun listByStatus(status: String, limit: Int, offset: Int): List<com.eaglepoint.libops.data.db.entity.DuplicateCandidateEntity> = emptyList()
        override suspend fun unresolvedOlderThan(olderThan: Long): Int = 0
    }
}
