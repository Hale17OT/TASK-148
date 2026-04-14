package com.eaglepoint.libops.tests

import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.data.db.entity.AlertEntity
import com.eaglepoint.libops.data.db.entity.ImportBatchEntity
import com.eaglepoint.libops.data.db.entity.ImportRowResultEntity
import com.eaglepoint.libops.data.db.entity.JobAttemptEntity
import com.eaglepoint.libops.data.db.entity.UserEntity
import com.eaglepoint.libops.domain.alerts.AlertPolicy
import com.eaglepoint.libops.domain.alerts.AnomalyThresholds
import com.eaglepoint.libops.domain.quality.QualityScore
import com.eaglepoint.libops.observability.ObservabilityPipeline
import com.eaglepoint.libops.observability.OverdueSweeper
import com.eaglepoint.libops.tests.fakes.FakeAlertDao
import com.eaglepoint.libops.tests.fakes.FakeAuditDao
import com.eaglepoint.libops.tests.fakes.FakeImportDao
import com.eaglepoint.libops.tests.fakes.FakeJobDao
import com.eaglepoint.libops.tests.fakes.FakeUserDao
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

class ObservabilityPipelineTest {

    private lateinit var auditDao: FakeAuditDao
    private lateinit var alertDao: FakeAlertDao
    private lateinit var jobDao: FakeJobDao
    private lateinit var audit: AuditLogger
    private lateinit var pipeline: ObservabilityPipeline
    private lateinit var sweeper: OverdueSweeper
    private var clockMs = 1_700_000_000_000L

    @Before
    fun setUp() {
        auditDao = FakeAuditDao()
        alertDao = FakeAlertDao()
        jobDao = FakeJobDao()
        audit = AuditLogger(auditDao) { clockMs }
        pipeline = ObservabilityPipeline(auditDao, alertDao, jobDao, audit, clock = { clockMs })
        sweeper = OverdueSweeper(alertDao, audit, clock = { clockMs })
    }

    @Test
    fun no_alert_when_fewer_than_50_terminal_attempts() = runBlocking {
        // Seed 10 failed attempts — below the 50-attempt minimum window
        for (i in 1..10) {
            jobDao.insertAttempt(
                JobAttemptEntity(
                    jobId = 1, attemptNumber = i, outcome = "failed",
                    startedAt = clockMs - 10_000 + i, finishedAt = clockMs,
                    errorMessage = "err", correlationId = "c$i",
                ),
            )
        }
        val alerts = pipeline.evaluateAnomalies()
        assertThat(alerts).isEmpty()
    }

    @Test
    fun alert_created_when_failure_rate_exceeds_threshold() = runBlocking {
        // Seed 50 attempts: 40 failed + 10 succeeded = 80% failure rate
        for (i in 1..40) {
            jobDao.insertAttempt(
                JobAttemptEntity(
                    jobId = i.toLong(), attemptNumber = 1, outcome = "failed",
                    startedAt = clockMs - 60_000 + i, finishedAt = clockMs,
                    errorMessage = "err", correlationId = "c$i",
                ),
            )
        }
        for (i in 41..50) {
            jobDao.insertAttempt(
                JobAttemptEntity(
                    jobId = i.toLong(), attemptNumber = 1, outcome = "succeeded",
                    startedAt = clockMs - 60_000 + i, finishedAt = clockMs,
                    errorMessage = null, correlationId = "c$i",
                ),
            )
        }
        val alerts = pipeline.evaluateAnomalies()
        assertThat(alerts).hasSize(1)
        val alert = alertDao.byId(alerts[0])!!
        assertThat(alert.category).isEqualTo("job_failure")
        assertThat(alert.status).isEqualTo("open")
    }

    @Test
    fun no_alert_when_failure_rate_within_threshold() = runBlocking {
        // Seed 50 attempts: 5 failed + 45 succeeded = 10% (below 20%)
        for (i in 1..5) {
            jobDao.insertAttempt(
                JobAttemptEntity(
                    jobId = i.toLong(), attemptNumber = 1, outcome = "failed",
                    startedAt = clockMs - 60_000 + i, finishedAt = clockMs,
                    errorMessage = "err", correlationId = "c$i",
                ),
            )
        }
        for (i in 6..50) {
            jobDao.insertAttempt(
                JobAttemptEntity(
                    jobId = i.toLong(), attemptNumber = 1, outcome = "succeeded",
                    startedAt = clockMs - 60_000 + i, finishedAt = clockMs,
                    errorMessage = null, correlationId = "c$i",
                ),
            )
        }
        val alerts = pipeline.evaluateAnomalies()
        assertThat(alerts).isEmpty()
    }

    @Test
    fun pending_and_running_attempts_excluded_from_window() = runBlocking {
        // Seed 50 "running" attempts — none are terminal, window is empty
        for (i in 1..50) {
            jobDao.insertAttempt(
                JobAttemptEntity(
                    jobId = i.toLong(), attemptNumber = 1, outcome = "running",
                    startedAt = clockMs - 60_000 + i, finishedAt = null,
                    errorMessage = null, correlationId = "c$i",
                ),
            )
        }
        val alerts = pipeline.evaluateAnomalies()
        assertThat(alerts).isEmpty()
    }

    @Test
    fun duplicate_anomaly_alerts_are_suppressed() = runBlocking {
        // Seed high failure rate and trigger twice
        for (i in 1..50) {
            jobDao.insertAttempt(
                JobAttemptEntity(
                    jobId = i.toLong(), attemptNumber = 1, outcome = "failed",
                    startedAt = clockMs - 60_000 + i, finishedAt = clockMs,
                    errorMessage = "err", correlationId = "c$i",
                ),
            )
        }
        val first = pipeline.evaluateAnomalies()
        assertThat(first).hasSize(1)
        val second = pipeline.evaluateAnomalies()
        assertThat(second).isEmpty() // duplicate suppressed
    }

    @Test
    fun exception_recording_persists_and_audits() = runBlocking {
        val id = pipeline.recordException("test_component", RuntimeException("boom"))
        assertThat(id).isGreaterThan(0L)
        // Audit event should be written
        val events = auditDao.allEventsChronological()
        assertThat(events.any { it.action == "observability.exception" }).isTrue()
    }

    @Test
    fun performance_sample_flags_slow_operations() = runBlocking {
        val id = pipeline.recordPerformanceSample("query", "test_query", 500L, slowThresholdMs = 200L)
        assertThat(id).isGreaterThan(0L)
        val events = auditDao.allEventsChronological()
        assertThat(events.any { it.action == "observability.slow_operation" }).isTrue()
    }

    // --- OverdueSweeper tests ---

    @Test
    fun overdue_sweep_transitions_acknowledged_past_sla() = runBlocking {
        val pastSla = clockMs - AlertPolicy.SLA_MILLIS - 1000
        alertDao.insert(
            AlertEntity(
                category = "test", severity = "warn", title = "old ack",
                body = "", status = "acknowledged", ownerUserId = null,
                correlationId = null, dueAt = pastSla + AlertPolicy.SLA_MILLIS,
                createdAt = pastSla, updatedAt = pastSla,
            ),
        )
        val result = sweeper.sweep()
        assertThat(result.transitioned).isEqualTo(1)
        val all = alertDao.all()
        assertThat(all[0].status).isEqualTo("overdue")
    }

    @Test
    fun overdue_sweep_transitions_open_unacknowledged_past_sla() = runBlocking {
        val pastSla = clockMs - AlertPolicy.SLA_MILLIS - 1000
        alertDao.insert(
            AlertEntity(
                category = "test", severity = "warn", title = "old open",
                body = "", status = "open", ownerUserId = null,
                correlationId = null, dueAt = pastSla + AlertPolicy.SLA_MILLIS,
                createdAt = pastSla, updatedAt = pastSla,
            ),
        )
        val result = sweeper.sweep()
        assertThat(result.transitioned).isEqualTo(1)
        val all = alertDao.all()
        assertThat(all[0].status).isEqualTo("overdue")
    }

    @Test
    fun overdue_sweep_does_not_touch_alerts_within_sla() = runBlocking {
        val withinSla = clockMs - 1000 // created 1 second ago
        alertDao.insert(
            AlertEntity(
                category = "test", severity = "warn", title = "recent",
                body = "", status = "acknowledged", ownerUserId = null,
                correlationId = null, dueAt = withinSla + AlertPolicy.SLA_MILLIS,
                createdAt = withinSla, updatedAt = withinSla,
            ),
        )
        val result = sweeper.sweep()
        assertThat(result.transitioned).isEqualTo(0)
        assertThat(alertDao.all()[0].status).isEqualTo("acknowledged")
    }

    // --- Quality score with validation failures ---

    @Test
    fun quality_score_reflects_rejected_import_rows() = runBlocking {
        val userDao = FakeUserDao()
        val importDao = FakeImportDao()
        val qDao = FakeQualityScoreDao()
        val dupDao = com.eaglepoint.libops.tests.fakes.FakeAlertDao() // unused but needed
        // Reuse the existing fakes for audit/alert/job
        val scorePipeline = ObservabilityPipeline(
            auditDao, alertDao, jobDao, audit,
            clock = { clockMs },
            userDao = userDao,
            qualityScoreDao = qDao,
            duplicateDao = StubDuplicateDao(),
            importDao = importDao,
        )

        // Seed a user
        val userId = userDao.insert(UserEntity(
            username = "scorer", displayName = "Scorer",
            passwordHash = "h", passwordSalt = "s",
            kdfAlgorithm = "a", kdfIterations = 1, kdfMemoryKb = 0,
            status = "active", biometricEnabled = false,
            createdAt = clockMs, updatedAt = clockMs,
        ))

        // Seed import batches with rejected rows for this user
        val batchId = importDao.insertBatch(ImportBatchEntity(
            bundleId = null, filename = "test.csv", format = "csv",
            totalRows = 5, acceptedRows = 3, rejectedRows = 2,
            state = "completed", createdByUserId = userId,
            createdAt = clockMs - 1000, completedAt = clockMs,
        ))
        // 2 rejected rows
        importDao.insertRow(ImportRowResultEntity(
            batchId = batchId, rowIndex = 0, outcome = "rejected_with_errors",
            errorsJson = "[]", rawPayload = "", stagedRecordId = null, createdAt = clockMs - 500,
        ))
        importDao.insertRow(ImportRowResultEntity(
            batchId = batchId, rowIndex = 1, outcome = "rejected_with_errors",
            errorsJson = "[]", rawPayload = "", stagedRecordId = null, createdAt = clockMs - 400,
        ))
        // 1 accepted row (should not count)
        importDao.insertRow(ImportRowResultEntity(
            batchId = batchId, rowIndex = 2, outcome = "accepted",
            errorsJson = null, rawPayload = "", stagedRecordId = 1, createdAt = clockMs - 300,
        ))

        val count = scorePipeline.computeQualityScores()
        assertThat(count).isEqualTo(1)

        val snapshot = qDao.snapshots.last()
        assertThat(snapshot.userId).isEqualTo(userId)
        assertThat(snapshot.validationFailures30d).isEqualTo(2)
        // Score = 100 - (2 * 2) = 96
        assertThat(snapshot.score).isEqualTo(96)
    }

    @Test
    fun quality_score_is_100_with_no_failures() = runBlocking {
        val userDao = FakeUserDao()
        val importDao = FakeImportDao()
        val qDao = FakeQualityScoreDao()
        val scorePipeline = ObservabilityPipeline(
            auditDao, alertDao, jobDao, audit,
            clock = { clockMs },
            userDao = userDao,
            qualityScoreDao = qDao,
            duplicateDao = StubDuplicateDao(),
            importDao = importDao,
        )
        userDao.insert(UserEntity(
            username = "clean", displayName = "Clean",
            passwordHash = "h", passwordSalt = "s",
            kdfAlgorithm = "a", kdfIterations = 1, kdfMemoryKb = 0,
            status = "active", biometricEnabled = false,
            createdAt = clockMs, updatedAt = clockMs,
        ))

        scorePipeline.computeQualityScores()
        assertThat(qDao.snapshots.last().score).isEqualTo(100)
    }

    // --- Helpers ---

    class FakeQualityScoreDao : com.eaglepoint.libops.data.db.dao.QualityScoreDao {
        val snapshots = mutableListOf<com.eaglepoint.libops.data.db.entity.QualityScoreSnapshotEntity>()
        private val ids = java.util.concurrent.atomic.AtomicLong(0)

        override suspend fun insert(snapshot: com.eaglepoint.libops.data.db.entity.QualityScoreSnapshotEntity): Long {
            val id = ids.incrementAndGet()
            snapshots.add(snapshot.copy(id = id))
            return id
        }
        override suspend fun latestFor(userId: Long) = snapshots.filter { it.userId == userId }.maxByOrNull { it.capturedAt }
        override suspend fun recent(limit: Int) = snapshots.sortedByDescending { it.capturedAt }.take(limit)
    }

    // ── QueryTimer emission tests ──

    @Test
    fun queryTimer_emits_performance_sample() = runBlocking {
        val timer = com.eaglepoint.libops.observability.QueryTimer(pipeline)
        val result = timer.timed("query", "recordDao.search") { "found" }
        assertThat(result).isEqualTo("found")
        assertThat(auditDao.totalSampleCountSince("query", 0)).isEqualTo(1)
        val samples = auditDao.perfSamplesSince("query", 0)
        assertThat(samples).hasSize(1)
        assertThat(samples[0].kind).isEqualTo("query")
        assertThat(samples[0].label).isEqualTo("recordDao.search")
    }

    @Test
    fun queryTimer_multiple_calls_emit_multiple_samples() = runBlocking {
        val timer = com.eaglepoint.libops.observability.QueryTimer(pipeline)
        timer.timed("query", "recordDao.byId") { 42 }
        timer.timed("query", "recordDao.search") { listOf(1, 2, 3) }
        timer.timed("work", "import.validate") { true }
        assertThat(auditDao.totalSampleCountSince("query", 0)).isEqualTo(2)
        assertThat(auditDao.totalSampleCountSince("work", 0)).isEqualTo(1)
    }

    class StubDuplicateDao : com.eaglepoint.libops.data.db.dao.DuplicateDao {
        override suspend fun insert(c: com.eaglepoint.libops.data.db.entity.DuplicateCandidateEntity): Long = 1
        override suspend fun update(c: com.eaglepoint.libops.data.db.entity.DuplicateCandidateEntity): Int = 1
        override suspend fun insertDecision(d: com.eaglepoint.libops.data.db.entity.MergeDecisionEntity): Long = 1
        override suspend fun byId(id: Long): com.eaglepoint.libops.data.db.entity.DuplicateCandidateEntity? = null
        override suspend fun listByStatus(s: String, l: Int, o: Int): List<com.eaglepoint.libops.data.db.entity.DuplicateCandidateEntity> = emptyList()
        override suspend fun unresolvedOlderThan(t: Long): Int = 0
    }
}
