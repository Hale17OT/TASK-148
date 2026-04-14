package com.eaglepoint.libops.tests

import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.data.db.entity.CollectionSourceEntity
import com.eaglepoint.libops.data.db.entity.CrawlRuleEntity
import com.eaglepoint.libops.data.db.entity.JobEntity
import com.eaglepoint.libops.domain.orchestration.RetryPolicy
import com.eaglepoint.libops.orchestration.JobScheduler
import com.eaglepoint.libops.tests.fakes.FakeAuditDao
import com.eaglepoint.libops.tests.fakes.FakeCollectionSourceDao
import com.eaglepoint.libops.tests.fakes.FakeJobDao
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Integration tests for the worker pipeline: stale-state handling,
 * battery pause transitions, and scheduler lifecycle paths.
 */
class WorkerIntegrationTest {

    private lateinit var sourceDao: FakeCollectionSourceDao
    private lateinit var jobDao: FakeJobDao
    private lateinit var auditDao: FakeAuditDao
    private lateinit var audit: AuditLogger
    private lateinit var scheduler: JobScheduler
    private var clockMs = 1_700_000_000_000L

    @Before
    fun setUp() {
        sourceDao = FakeCollectionSourceDao()
        jobDao = FakeJobDao()
        auditDao = FakeAuditDao()
        audit = AuditLogger(auditDao) { clockMs }
        scheduler = JobScheduler(jobDao, sourceDao, audit) { clockMs }
    }

    private suspend fun createActiveSource(): Long {
        return sourceDao.insert(
            CollectionSourceEntity(
                name = "test-src", entryType = "imported_file", refreshMode = "full",
                priority = 3, retryBackoff = "5_min", enabled = true, state = "active",
                scheduleCron = null, createdAt = clockMs, updatedAt = clockMs,
            ),
        )
    }

    // --- Stale state handling ---

    @Test
    fun mark_succeeded_works_after_reload(): Unit = runBlocking {
        val sourceId = createActiveSource()
        val jobId = jobDao.insert(
            JobEntity(
                sourceId = sourceId, status = "scheduled", priority = 3,
                refreshMode = "full", correlationId = UUID.randomUUID().toString(),
                scheduledAt = clockMs, startedAt = null, finishedAt = null,
                lastError = null, createdAt = clockMs, updatedAt = clockMs,
            ),
        )
        // Pick batch promotes to queued
        val batch = scheduler.pickBatch(1, 100, true)
        assertThat(batch).hasSize(1)
        assertThat(batch[0].status).isEqualTo("queued")

        // Mark running
        scheduler.markRunning(batch[0])

        // Reload gives us the running state
        val running = scheduler.reload(jobId)!!
        assertThat(running.status).isEqualTo("running")

        // Mark succeeded from the running state — no illegal transition
        scheduler.markSucceeded(running)

        val final_ = jobDao.byId(jobId)!!
        assertThat(final_.status).isEqualTo("succeeded")
    }

    @Test
    fun mark_failed_works_after_reload(): Unit = runBlocking {
        val sourceId = createActiveSource()
        val jobId = jobDao.insert(
            JobEntity(
                sourceId = sourceId, status = "scheduled", priority = 3,
                refreshMode = "full", correlationId = UUID.randomUUID().toString(),
                scheduledAt = clockMs, startedAt = null, finishedAt = null,
                lastError = null, createdAt = clockMs, updatedAt = clockMs,
            ),
        )
        val batch = scheduler.pickBatch(1, 100, true)
        scheduler.markRunning(batch[0])
        val running = scheduler.reload(jobId)!!

        scheduler.markFailed(running, "test_error", retryable = false)
        val final_ = jobDao.byId(jobId)!!
        assertThat(final_.status).isEqualTo("terminal_failed")
    }

    @Test
    fun stale_job_object_would_fail_without_reload(): Unit = runBlocking {
        val sourceId = createActiveSource()
        jobDao.insert(
            JobEntity(
                sourceId = sourceId, status = "scheduled", priority = 3,
                refreshMode = "full", correlationId = UUID.randomUUID().toString(),
                scheduledAt = clockMs, startedAt = null, finishedAt = null,
                lastError = null, createdAt = clockMs, updatedAt = clockMs,
            ),
        )
        val batch = scheduler.pickBatch(1, 100, true)
        val staleJob = batch[0] // still has status="queued"
        scheduler.markRunning(staleJob)

        // Trying to mark succeeded on the stale "queued" object would throw
        // because queued -> succeeded is not a valid state machine transition.
        // This test proves the reload pattern is necessary.
        try {
            scheduler.markSucceeded(staleJob) // staleJob.status is "queued"
            assertThat(false).isTrue() // should not reach here
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("Illegal job transition: queued -> succeeded")
        }
    }

    // --- Battery pause transitions ---

    @Test
    fun low_battery_pauses_queued_jobs(): Unit = runBlocking {
        val sourceId = createActiveSource()
        val jobId = jobDao.insert(
            JobEntity(
                sourceId = sourceId, status = "queued", priority = 3,
                refreshMode = "full", correlationId = UUID.randomUUID().toString(),
                scheduledAt = clockMs, startedAt = null, finishedAt = null,
                lastError = null, createdAt = clockMs, updatedAt = clockMs,
            ),
        )

        // Pick with low battery — should pause the queued job
        val batch = scheduler.pickBatch(1, batteryPct = 5, charging = false)
        assertThat(batch).isEmpty()

        val paused = jobDao.byId(jobId)!!
        assertThat(paused.status).isEqualTo("paused_low_battery")

        // Audit event recorded
        val events = auditDao.allEventsChronological()
        assertThat(events.any { it.action == "job.paused_low_battery" }).isTrue()
    }

    @Test
    fun battery_recovery_resumes_paused_jobs(): Unit = runBlocking {
        val sourceId = createActiveSource()
        val jobId = jobDao.insert(
            JobEntity(
                sourceId = sourceId, status = "paused_low_battery", priority = 3,
                refreshMode = "full", correlationId = UUID.randomUUID().toString(),
                scheduledAt = clockMs, startedAt = null, finishedAt = null,
                lastError = null, createdAt = clockMs, updatedAt = clockMs,
            ),
        )

        // Pick with good battery — should resume the paused job
        val batch = scheduler.pickBatch(1, batteryPct = 80, charging = false)
        assertThat(batch).isNotEmpty()

        val resumed = jobDao.byId(jobId)!!
        assertThat(resumed.status).isIn(listOf("queued", "running"))
    }

    // --- Retry backoff verification ---

    @Test
    fun retry_backoff_sets_future_scheduled_at(): Unit = runBlocking {
        val sourceId = createActiveSource()
        val now = clockMs
        val jobId = jobDao.insert(
            JobEntity(
                sourceId = sourceId, status = "scheduled", priority = 3,
                refreshMode = "full", correlationId = UUID.randomUUID().toString(),
                scheduledAt = now, startedAt = null, finishedAt = null,
                lastError = null, createdAt = now, updatedAt = now,
            ),
        )
        val batch = scheduler.pickBatch(1, 100, true)
        scheduler.markRunning(batch[0])
        val running = scheduler.reload(jobId)!!

        scheduler.markFailed(running, "transient_error", retryable = true)

        val retrying = jobDao.byId(jobId)!!
        assertThat(retrying.status).isEqualTo("retry_waiting")
        val expectedBackoff = RetryPolicy.nextDelay("5_min")
        assertThat(retrying.scheduledAt).isAtLeast(now + expectedBackoff)
    }

    @Test
    fun backoff_job_excluded_from_pick_until_eligible(): Unit = runBlocking {
        val sourceId = createActiveSource()
        val futureTime = clockMs + 999_999
        jobDao.insert(
            JobEntity(
                sourceId = sourceId, status = "retry_waiting", priority = 3,
                refreshMode = "full", correlationId = UUID.randomUUID().toString(),
                scheduledAt = futureTime, startedAt = null, finishedAt = null,
                lastError = "prev", retryCount = 1, createdAt = clockMs, updatedAt = clockMs,
            ),
        )
        val batch = scheduler.pickBatch(1, 100, true)
        assertThat(batch).isEmpty()
    }
}
