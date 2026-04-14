package com.eaglepoint.libops.tests

import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.data.db.entity.CollectionSourceEntity
import com.eaglepoint.libops.data.db.entity.JobEntity
import com.eaglepoint.libops.orchestration.JobScheduler
import com.eaglepoint.libops.tests.fakes.FakeAuditDao
import com.eaglepoint.libops.tests.fakes.FakeCollectionSourceDao
import com.eaglepoint.libops.tests.fakes.FakeJobDao
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

/**
 * Retry semantics per PRD §9.3: max 3 retries. That is 4 total attempts
 * (initial + 3 retries), and the third retry's failure terminates.
 */
class JobSchedulerRetryTest {

    private lateinit var jobDao: FakeJobDao
    private lateinit var sourceDao: FakeCollectionSourceDao
    private lateinit var audit: AuditLogger
    private lateinit var scheduler: JobScheduler
    private var clockMs = 1_700_000_000_000L

    @Before
    fun setUp() {
        runBlocking {
            jobDao = FakeJobDao()
            sourceDao = FakeCollectionSourceDao()
            audit = AuditLogger(FakeAuditDao()) { clockMs }
            scheduler = JobScheduler(jobDao, sourceDao, audit) { clockMs }

            sourceDao.insert(
                CollectionSourceEntity(
                    name = "Test",
                    entryType = "site",
                    refreshMode = "incremental",
                    priority = 3,
                    retryBackoff = "1_min",
                    enabled = true,
                    state = "active",
                    scheduleCron = null,
                    createdAt = clockMs,
                    updatedAt = clockMs,
                ),
            )
        }
    }

    @Test
    fun three_retries_then_terminal_fail() = runBlocking {
        val enq = scheduler.enqueueManual(sourceId = 1, userId = 99)
        val jobId = (enq as JobScheduler.EnqueueResult.Enqueued).jobId
        var job = jobDao.byId(jobId)!!

        // Attempt 1: fails → retry_waiting, retryCount becomes 1
        scheduler.markRunning(job)
        scheduler.markFailed(jobDao.byId(jobId)!!, "t1", retryable = true)
        assertThat(jobDao.byId(jobId)!!.status).isEqualTo("retry_waiting")
        assertThat(jobDao.byId(jobId)!!.retryCount).isEqualTo(1)

        // Attempt 2: fails → retry_waiting, retryCount becomes 2
        job = jobDao.byId(jobId)!!.copy(status = "queued") // simulate scheduler promotion
        jobDao.update(job)
        scheduler.markRunning(jobDao.byId(jobId)!!)
        scheduler.markFailed(jobDao.byId(jobId)!!, "t2", retryable = true)
        assertThat(jobDao.byId(jobId)!!.status).isEqualTo("retry_waiting")
        assertThat(jobDao.byId(jobId)!!.retryCount).isEqualTo(2)

        // Attempt 3: fails → retry_waiting, retryCount becomes 3
        job = jobDao.byId(jobId)!!.copy(status = "queued")
        jobDao.update(job)
        scheduler.markRunning(jobDao.byId(jobId)!!)
        scheduler.markFailed(jobDao.byId(jobId)!!, "t3", retryable = true)
        assertThat(jobDao.byId(jobId)!!.status).isEqualTo("retry_waiting")
        assertThat(jobDao.byId(jobId)!!.retryCount).isEqualTo(3)

        // Attempt 4: fails → terminal_failed (no more retries allowed)
        job = jobDao.byId(jobId)!!.copy(status = "queued")
        jobDao.update(job)
        scheduler.markRunning(jobDao.byId(jobId)!!)
        scheduler.markFailed(jobDao.byId(jobId)!!, "t4", retryable = true)
        assertThat(jobDao.byId(jobId)!!.status).isEqualTo("terminal_failed")
    }

    @Test
    fun non_retryable_error_is_terminal_on_first_failure() = runBlocking {
        val enq = scheduler.enqueueManual(sourceId = 1, userId = 99)
        val jobId = (enq as JobScheduler.EnqueueResult.Enqueued).jobId
        scheduler.markRunning(jobDao.byId(jobId)!!)
        scheduler.markFailed(jobDao.byId(jobId)!!, "invalid_signature", retryable = false)
        assertThat(jobDao.byId(jobId)!!.status).isEqualTo("terminal_failed")
    }
}
