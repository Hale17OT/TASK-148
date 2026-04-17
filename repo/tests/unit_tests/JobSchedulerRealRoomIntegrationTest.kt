package com.eaglepoint.libops.tests

import androidx.test.core.app.ApplicationProvider
import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.data.db.LibOpsDatabase
import com.eaglepoint.libops.data.db.entity.CollectionSourceEntity
import com.eaglepoint.libops.data.db.entity.JobEntity
import com.eaglepoint.libops.orchestration.JobScheduler
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * End-to-end real-Room integration test for [JobScheduler] — replaces the
 * fake-DAO-based [JobSchedulerRetryTest] and [WorkerIntegrationTest] paths
 * with a live Room database so the real SQL ordering, indices, and state
 * transitions are exercised (§9.4, §9.5).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class JobSchedulerRealRoomIntegrationTest {

    private lateinit var db: LibOpsDatabase
    private lateinit var audit: AuditLogger
    private lateinit var scheduler: JobScheduler
    private val clockMs = 1_700_000_000_000L

    @Before
    fun setUp() {
        db = LibOpsDatabase.inMemory(
            ApplicationProvider.getApplicationContext<android.content.Context>()
        )
        audit = AuditLogger(db.auditDao(), clock = { clockMs })
        scheduler = JobScheduler(
            jobDao = db.jobDao(),
            sourceDao = db.collectionSourceDao(),
            audit = audit,
            clock = { clockMs },
        )
    }

    @After
    fun tearDown() { db.close() }

    private suspend fun newSource(): Long = db.collectionSourceDao().insert(
        CollectionSourceEntity(
            name = "src", entryType = "imported_file", refreshMode = "full",
            priority = 3, retryBackoff = "5_min", enabled = true,
            state = "active", scheduleCron = null,
            createdAt = clockMs, updatedAt = clockMs,
        ),
    )

    private suspend fun newJob(sourceId: Long, priority: Int = 3, status: String = "scheduled"): Long =
        db.jobDao().insert(
            JobEntity(
                sourceId = sourceId, status = status, priority = priority,
                refreshMode = "full", correlationId = UUID.randomUUID().toString(),
                scheduledAt = clockMs, startedAt = null, finishedAt = null,
                lastError = null, createdAt = clockMs, updatedAt = clockMs,
            ),
        )

    @Test
    fun pick_batch_orders_by_priority_against_real_sql(): Unit = runBlocking {
        val src = newSource()
        newJob(src, priority = 1)
        newJob(src, priority = 5)
        newJob(src, priority = 3)

        val batch = scheduler.pickBatch(parallelism = 3, batteryPct = 100, charging = true)
        assertThat(batch).hasSize(3)
        assertThat(batch[0].priority).isEqualTo(5)  // highest first
    }

    @Test
    fun mark_running_then_succeeded_transitions_via_real_sql(): Unit = runBlocking {
        val src = newSource()
        val jobId = newJob(src)

        val batch = scheduler.pickBatch(parallelism = 1, batteryPct = 100, charging = true)
        assertThat(batch).hasSize(1)

        scheduler.markRunning(batch[0])
        val running = scheduler.reload(jobId)!!
        assertThat(running.status).isEqualTo("running")

        scheduler.markSucceeded(running)
        val done = db.jobDao().byId(jobId)!!
        assertThat(done.status).isEqualTo("succeeded")
    }

    @Test
    fun mark_failed_with_retry_sets_retry_waiting_in_real_sql(): Unit = runBlocking {
        val src = newSource()
        val jobId = newJob(src)
        val batch = scheduler.pickBatch(parallelism = 1, batteryPct = 100, charging = true)
        scheduler.markRunning(batch[0])
        val running = scheduler.reload(jobId)!!

        scheduler.markFailed(running, error = "network", retryable = true)

        val retrying = db.jobDao().byId(jobId)!!
        assertThat(retrying.status).isEqualTo("retry_waiting")
        assertThat(retrying.retryCount).isEqualTo(1)
        assertThat(retrying.scheduledAt).isGreaterThan(clockMs)  // pushed forward by backoff
    }

    @Test
    fun low_battery_prevents_pickup_and_pauses_queued_jobs(): Unit = runBlocking {
        val src = newSource()
        val jobId = db.jobDao().insert(
            JobEntity(
                sourceId = src, status = "queued", priority = 3,
                refreshMode = "full", correlationId = UUID.randomUUID().toString(),
                scheduledAt = clockMs, startedAt = null, finishedAt = null,
                lastError = null, createdAt = clockMs, updatedAt = clockMs,
            ),
        )

        val batch = scheduler.pickBatch(parallelism = 1, batteryPct = 5, charging = false)
        assertThat(batch).isEmpty()

        val paused = db.jobDao().byId(jobId)!!
        assertThat(paused.status).isEqualTo("paused_low_battery")
    }

    @Test
    fun charging_resumes_previously_paused_job(): Unit = runBlocking {
        val src = newSource()
        val jobId = db.jobDao().insert(
            JobEntity(
                sourceId = src, status = "paused_low_battery", priority = 3,
                refreshMode = "full", correlationId = UUID.randomUUID().toString(),
                scheduledAt = clockMs, startedAt = null, finishedAt = null,
                lastError = null, createdAt = clockMs, updatedAt = clockMs,
            ),
        )

        val batch = scheduler.pickBatch(parallelism = 1, batteryPct = 50, charging = true)
        assertThat(batch).isNotEmpty()

        val resumed = db.jobDao().byId(jobId)!!
        assertThat(resumed.status).isIn(listOf("queued", "running"))
    }

    @Test
    fun pick_batch_audit_events_land_in_real_audit_table(): Unit = runBlocking {
        val src = newSource()
        // pickBatch only pauses 'queued' jobs, not 'scheduled'. Use queued here.
        newJob(src, status = "queued")

        scheduler.pickBatch(parallelism = 1, batteryPct = 5, charging = false)
        val events = db.auditDao().allEventsChronological()
        assertThat(events.any { it.action == "job.paused_low_battery" }).isTrue()
    }
}
