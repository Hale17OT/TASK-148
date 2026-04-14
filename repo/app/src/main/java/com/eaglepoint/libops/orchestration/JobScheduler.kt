package com.eaglepoint.libops.orchestration

import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.data.db.dao.CollectionSourceDao
import com.eaglepoint.libops.data.db.dao.JobDao
import com.eaglepoint.libops.observability.ObservabilityPipeline
import com.eaglepoint.libops.observability.QueryTimer
import com.eaglepoint.libops.data.db.entity.JobAttemptEntity
import com.eaglepoint.libops.data.db.entity.JobEntity
import com.eaglepoint.libops.domain.orchestration.BatteryAwareness
import com.eaglepoint.libops.domain.orchestration.JobOrdering
import com.eaglepoint.libops.domain.orchestration.RetryPolicy
import com.eaglepoint.libops.domain.statemachine.JobStateMachine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Selects runnable jobs (§9.4) and drives state transitions (§10.4).
 *
 * Failure isolation: one bad job is marked failed/terminal_failed, but the
 * scheduler continues processing other sources. No cross-source coupling.
 */
class JobScheduler(
    private val jobDao: JobDao,
    private val sourceDao: CollectionSourceDao,
    private val audit: AuditLogger,
    private val observability: ObservabilityPipeline? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val queryTimer: QueryTimer? = observability?.let { QueryTimer(it) }

    suspend fun enqueueManual(sourceId: Long, userId: Long): EnqueueResult = withContext(Dispatchers.IO) {
        val source = (queryTimer?.let { it.timed("query", "sourceDao.byId") { sourceDao.byId(sourceId) } } ?: sourceDao.byId(sourceId)) ?: return@withContext EnqueueResult.SourceNotFound
        if (source.state != "active") return@withContext EnqueueResult.SourceNotActive(source.state)
        val now = clock()
        val job = JobEntity(
            sourceId = source.id,
            status = "scheduled",
            priority = source.priority,
            retryCount = 0,
            refreshMode = source.refreshMode,
            correlationId = UUID.randomUUID().toString(),
            scheduledAt = now,
            startedAt = null,
            finishedAt = null,
            lastError = null,
            createdAt = now,
            updatedAt = now,
        )
        val id = jobDao.insert(job)
        audit.record(
            "job.enqueued_manual",
            "job",
            targetId = id.toString(),
            userId = userId,
            reason = "manual_trigger",
            correlationId = job.correlationId,
        )
        EnqueueResult.Enqueued(id)
    }

    suspend fun pickBatch(
        parallelism: Int,
        batteryPct: Int,
        charging: Boolean,
        batteryThresholdPct: Int = BatteryAwareness.DEFAULT_THRESHOLD_PCT,
    ): List<JobEntity> = withContext(Dispatchers.IO) {
        if (BatteryAwareness.shouldPause(batteryPct, charging, batteryThresholdPct)) {
            // Transition queued jobs to paused_low_battery for traceability
            val eligible = timedNextRunnable(100)
            for (job in eligible) {
                if (job.status == "queued") {
                    try {
                        transition(job, "paused_low_battery")
                        audit.record(
                            "job.paused_low_battery",
                            "job",
                            targetId = job.id.toString(),
                            reason = "battery=${batteryPct}%,threshold=${batteryThresholdPct}%",
                            severity = AuditLogger.Severity.INFO,
                            correlationId = job.correlationId,
                        )
                    } catch (_: IllegalArgumentException) {
                        // State machine may not allow the transition from all statuses
                    }
                }
            }
            return@withContext emptyList()
        }
        // Resume any previously paused jobs when battery is sufficient
        val paused = queryTimer?.let { it.timed("query", "jobDao.pausedJobs") { jobDao.pausedJobs() } } ?: jobDao.pausedJobs()
        for (job in paused) {
            if (BatteryAwareness.shouldResume(batteryPct, charging, batteryThresholdPct)) {
                try {
                    transition(job, "queued")
                    audit.record(
                        "job.resumed_from_battery_pause",
                        "job",
                        targetId = job.id.toString(),
                        reason = "battery=${batteryPct}%",
                        correlationId = job.correlationId,
                    )
                } catch (_: IllegalArgumentException) {
                    // transition may not be valid
                }
            }
        }
        val pool = timedNextRunnable(parallelism * 4)
        val candidates = pool.map {
            JobOrdering.Candidate(
                id = it.id,
                priority = it.priority,
                scheduledAt = it.scheduledAt,
                retryCount = it.retryCount,
            )
        }
        val picked = JobOrdering.sorted(candidates).take(parallelism).map { it.id }.toSet()
        // Promote "scheduled" → "queued" and "retry_waiting" → "queued" up front.
        // The state machine (§10.4) requires this intermediate transition before
        // markRunning() can move queued → running.
        pool.filter { it.id in picked }.map { job ->
            if (job.status == "scheduled" || job.status == "retry_waiting") {
                transition(job, "queued")
                (queryTimer?.let { it.timed("query", "jobDao.byId") { jobDao.byId(job.id) } } ?: jobDao.byId(job.id)) ?: job.copy(status = "queued")
            } else job
        }
    }

    /** Reload current persisted state for a job. */
    suspend fun reload(jobId: Long): JobEntity? = withContext(Dispatchers.IO) {
        queryTimer?.let { it.timed("query", "jobDao.byId") { jobDao.byId(jobId) } } ?: jobDao.byId(jobId)
    }

    suspend fun markRunning(job: JobEntity): JobAttemptEntity = withContext(Dispatchers.IO) {
        // If caller passed an up-to-date "queued" job, go straight to running.
        // If they still have a stale "scheduled" handle, promote first so the
        // state machine is satisfied.
        val fresh = (queryTimer?.let { it.timed("query", "jobDao.byId") { jobDao.byId(job.id) } } ?: jobDao.byId(job.id)) ?: job
        if (fresh.status == "scheduled") transition(fresh, "queued")
        val readyToRun = (queryTimer?.let { it.timed("query", "jobDao.byId") { jobDao.byId(job.id) } } ?: jobDao.byId(job.id)) ?: fresh
        transition(readyToRun, "running")
        val attemptNo = (queryTimer?.let { it.timed("query", "jobDao.attemptsFor") { jobDao.attemptsFor(job.id) } } ?: jobDao.attemptsFor(job.id)).size + 1
        val attempt = JobAttemptEntity(
            jobId = job.id,
            attemptNumber = attemptNo,
            outcome = "running",
            startedAt = clock(),
            finishedAt = null,
            errorMessage = null,
            correlationId = job.correlationId,
        )
        val id = jobDao.insertAttempt(attempt)
        attempt.copy(id = id)
    }

    suspend fun markSucceeded(job: JobEntity) = withContext(Dispatchers.IO) {
        transition(job, "succeeded", startedAt = job.startedAt, finishedAt = clock())
        audit.record(
            "job.succeeded",
            "job",
            targetId = job.id.toString(),
            correlationId = job.correlationId,
        )
    }

    suspend fun markFailed(job: JobEntity, error: String, retryable: Boolean) = withContext(Dispatchers.IO) {
        // Policy: max 3 retries, i.e. up to 4 attempts total (1 original + 3 retries).
        // State machine (§10.4): running → {succeeded, failed, retry_waiting, ...}.
        // `terminal_failed` is only reachable via `failed → terminal_failed`, so we
        // go through the intermediate `failed` state on the non-retry path.
        val canRetry = retryable && job.retryCount < RetryPolicy.MAX_RETRIES

        if (canRetry) {
            val source = queryTimer?.let { it.timed("query", "sourceDao.byId") { sourceDao.byId(job.sourceId) } } ?: sourceDao.byId(job.sourceId)
            val backoff = source?.retryBackoff ?: "5_min"
            val nextEligible = clock() + RetryPolicy.nextDelay(backoff)
            transition(
                job,
                to = "retry_waiting",
                retryCount = job.retryCount + 1,
                lastError = error,
                finishedAt = null,
                scheduledAt = nextEligible,
            )
            audit.record(
                "job.retry_waiting",
                "job",
                targetId = job.id.toString(),
                reason = error.take(120),
                severity = AuditLogger.Severity.WARN,
                correlationId = job.correlationId,
            )
        } else {
            // Two-step transition: running → failed → terminal_failed.
            transition(job, to = "failed", lastError = error)
            val now = clock()
            val refreshed = (queryTimer?.let { it.timed("query", "jobDao.byId") { jobDao.byId(job.id) } } ?: jobDao.byId(job.id)) ?: job.copy(status = "failed")
            transition(refreshed, to = "terminal_failed", lastError = error, finishedAt = now)
            audit.record(
                "job.terminal_failed",
                "job",
                targetId = job.id.toString(),
                reason = error.take(120),
                severity = AuditLogger.Severity.CRITICAL,
                correlationId = job.correlationId,
            )
        }
    }

    suspend fun markPausedLowBattery(job: JobEntity) = withContext(Dispatchers.IO) {
        transition(job, "paused_low_battery")
    }

    private suspend fun timedNextRunnable(limit: Int): List<JobEntity> {
        return if (queryTimer != null) {
            queryTimer.timed("query", "jobDao.nextRunnable") { jobDao.nextRunnable(limit, clock()) }
        } else {
            jobDao.nextRunnable(limit, clock())
        }
    }

    private suspend fun transition(
        job: JobEntity,
        to: String,
        retryCount: Int = job.retryCount,
        lastError: String? = job.lastError,
        startedAt: Long? = job.startedAt ?: (if (to == "running") clock() else null),
        finishedAt: Long? = job.finishedAt,
        scheduledAt: Long = job.scheduledAt,
    ) {
        require(JobStateMachine.canTransition(job.status, to)) {
            "Illegal job transition: ${job.status} -> $to"
        }
        jobDao.updateStatus(
            id = job.id,
            status = to,
            retryCount = retryCount,
            lastError = lastError,
            startedAt = startedAt,
            finishedAt = finishedAt,
            now = clock(),
            scheduledAt = scheduledAt,
        )
    }

    sealed interface EnqueueResult {
        data class Enqueued(val jobId: Long) : EnqueueResult
        data object SourceNotFound : EnqueueResult
        data class SourceNotActive(val state: String) : EnqueueResult
    }
}
