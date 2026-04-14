package com.eaglepoint.libops.orchestration

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.eaglepoint.libops.LibOpsApp
import com.eaglepoint.libops.data.db.entity.JobEntity
import com.eaglepoint.libops.domain.orchestration.RetryPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Periodic worker: reads eligible jobs from the DB and processes them serially
 * within this worker invocation. Work respects:
 *
 * - §9.4 priority + time + retry + id ordering (via [JobScheduler.pickBatch])
 * - §9.5 battery-aware pause (via [BatteryGuard])
 * - §15 failure isolation: a crash on one job does not fail the worker
 *
 * Jobs are idempotent per chunk: on a fresh worker invocation after process
 * death we re-evaluate `nextRunnable` rather than resuming an orphaned job.
 * Restart safety is preserved because incomplete runs remain in `running`
 * and get surfaced on the Job detail screen; the scheduler picks them up
 * again on the next enqueue cycle.
 */
class CollectionRunWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as LibOpsApp
        val scheduler = app.jobScheduler
        val battery = BatteryGuard.current(applicationContext)
        val settings = app.settings.current()

        // Run observability anomaly evaluation on each tick
        try {
            app.observabilityPipeline.evaluateAnomalies()
        } catch (_: Throwable) {
            // Anomaly evaluation failures must not block job processing
        }

        // Run overdue alert sweep on each tick
        try {
            app.overdueSweeper.sweep()
        } catch (_: Throwable) {
            // Sweep failures must not block job processing
        }

        // Compute quality score snapshots periodically
        try {
            app.observabilityPipeline.computeQualityScores()
        } catch (_: Throwable) {
            // Quality score failures must not block job processing
        }

        val batch = scheduler.pickBatch(
            parallelism = settings.parallelism,
            batteryPct = battery.percent,
            charging = battery.charging,
            batteryThresholdPct = settings.batteryThresholdPct,
        )
        if (batch.isEmpty()) return@withContext Result.success()

        val pipeline = SourceIngestionPipeline(
            sourceDao = app.db.collectionSourceDao(),
            importDao = app.db.importDao(),
            recordDao = app.db.recordDao(),
            duplicateDao = app.db.duplicateDao(),
            audit = app.auditLogger,
            observability = app.observabilityPipeline,
        )

        // Execute picked batch concurrently with bounded semaphore (§9.4 parallelism)
        val semaphore = Semaphore(settings.parallelism.coerceIn(1, 6))
        coroutineScope {
            batch.map { job ->
                async {
                    semaphore.withPermit {
                        processOne(scheduler, pipeline, job)
                    }
                }
            }.awaitAll()
        }
        Result.success()
    }

    private suspend fun processOne(
        scheduler: JobScheduler,
        pipeline: SourceIngestionPipeline,
        job: JobEntity,
    ) {
        val app = applicationContext as LibOpsApp
        val attempt = scheduler.markRunning(job)
        // Reload the job from DB to get the authoritative "running" state.
        // The original `job` object still carries the pre-transition status
        // (queued/scheduled), which would cause illegal state machine
        // transitions in markSucceeded/markFailed.
        val runningJob = scheduler.reload(job.id) ?: job.copy(status = "running", startedAt = attempt.startedAt)
        try {
            pipeline.ingest(runningJob)
            scheduler.markSucceeded(runningJob)
        } catch (retryable: RetryableException) {
            app.observabilityPipeline.recordException("CollectionRunWorker", retryable, correlationId = job.correlationId)
            scheduler.markFailed(runningJob, retryable.message ?: "transient", retryable = true)
        } catch (fatal: Throwable) {
            app.observabilityPipeline.recordException("CollectionRunWorker", fatal, correlationId = job.correlationId)
            scheduler.markFailed(runningJob, fatal.message ?: fatal.javaClass.simpleName, retryable = false)
        }
    }

    class RetryableException(message: String) : RuntimeException(message)

    companion object {
        const val UNIQUE_NAME = "libops.collection_run_worker"
        val DEFAULT_RETRY_KIND = RetryPolicy.RetryKind.TRANSIENT_IO
    }
}
