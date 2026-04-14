package com.eaglepoint.libops.observability

import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.data.db.dao.AlertDao
import com.eaglepoint.libops.data.db.dao.AuditDao
import com.eaglepoint.libops.data.db.dao.JobDao
import com.eaglepoint.libops.data.db.entity.AlertEntity
import com.eaglepoint.libops.data.db.entity.ExceptionEventEntity
import com.eaglepoint.libops.data.db.entity.PerformanceSampleEntity
import com.eaglepoint.libops.data.db.entity.PolicyViolationEntity
import com.eaglepoint.libops.data.db.dao.DuplicateDao
import com.eaglepoint.libops.data.db.dao.ImportDao
import com.eaglepoint.libops.data.db.dao.QualityScoreDao
import com.eaglepoint.libops.data.db.dao.UserDao
import com.eaglepoint.libops.data.db.entity.QualityScoreSnapshotEntity
import com.eaglepoint.libops.domain.alerts.AlertPolicy
import com.eaglepoint.libops.domain.alerts.AnomalyThresholds
import com.eaglepoint.libops.domain.quality.QualityScore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Wires exception, performance, and anomaly pipelines (§15).
 *
 * - [recordException]: persists stack traces for offline troubleshooting
 * - [recordPerformanceSample]: persists query/work/decode timing
 * - [recordViolation]: persists policy breaches
 * - [evaluateAnomalies]: checks thresholds and auto-creates alerts
 */
class ObservabilityPipeline(
    private val auditDao: AuditDao,
    private val alertDao: AlertDao,
    private val jobDao: JobDao,
    private val auditLogger: AuditLogger,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val userDao: UserDao? = null,
    private val qualityScoreDao: QualityScoreDao? = null,
    private val duplicateDao: DuplicateDao? = null,
    private val importDao: ImportDao? = null,
) {

    /**
     * Internal timing helper for DAO reads within ObservabilityPipeline itself.
     * Uses System.nanoTime() and calls [recordPerformanceSample] directly to
     * avoid a circular dependency with [QueryTimer].
     */
    private suspend fun <T> timedQuery(label: String, block: suspend () -> T): T {
        val start = System.nanoTime()
        val result = block()
        val durationMs = (System.nanoTime() - start) / 1_000_000L
        recordPerformanceSample("query", label, durationMs)
        return result
    }

    suspend fun recordException(
        component: String,
        throwable: Throwable,
        correlationId: String = UUID.randomUUID().toString(),
    ): Long = withContext(Dispatchers.IO) {
        val entity = ExceptionEventEntity(
            correlationId = correlationId,
            message = throwable.message ?: throwable.javaClass.simpleName,
            stackTrace = throwable.stackTraceToString().take(4000),
            component = component,
            createdAt = clock(),
        )
        val id = auditDao.insertException(entity)
        auditLogger.record(
            action = "observability.exception",
            targetType = "exception_event",
            targetId = id.toString(),
            reason = "${component}: ${throwable.javaClass.simpleName}",
            severity = AuditLogger.Severity.WARN,
            correlationId = correlationId,
        )
        id
    }

    suspend fun recordPerformanceSample(
        kind: String,
        label: String,
        durationMs: Long,
        slowThresholdMs: Long = AnomalyThresholds.SLOW_QUERY_MS,
    ): Long = withContext(Dispatchers.IO) {
        val wasSlow = durationMs > slowThresholdMs
        val entity = PerformanceSampleEntity(
            kind = kind,
            label = label,
            durationMs = durationMs,
            wasSlow = wasSlow,
            createdAt = clock(),
        )
        val id = auditDao.insertSample(entity)
        if (wasSlow) {
            auditLogger.record(
                action = "observability.slow_operation",
                targetType = "performance_sample",
                targetId = id.toString(),
                reason = "$kind:$label took ${durationMs}ms (threshold=${slowThresholdMs}ms)",
                severity = AuditLogger.Severity.WARN,
            )
        }
        id
    }

    suspend fun recordViolation(
        userId: Long?,
        policyKey: String,
        severity: String,
        reason: String,
        correlationId: String? = null,
    ): Long = withContext(Dispatchers.IO) {
        val entity = PolicyViolationEntity(
            userId = userId,
            policyKey = policyKey,
            severity = severity,
            reason = reason,
            correlationId = correlationId,
            createdAt = clock(),
        )
        val id = auditDao.insertViolation(entity)
        auditLogger.record(
            action = "observability.violation",
            targetType = "policy_violation",
            targetId = id.toString(),
            userId = userId,
            reason = "$policyKey: $reason",
            severity = when (severity) {
                "high" -> AuditLogger.Severity.CRITICAL
                "medium" -> AuditLogger.Severity.WARN
                else -> AuditLogger.Severity.INFO
            },
        )
        id
    }

    /**
     * Evaluate anomaly thresholds and auto-create alerts when breached.
     * Should be called periodically (e.g. from the collection run worker tick).
     */
    suspend fun evaluateAnomalies(): List<Long> = withContext(Dispatchers.IO) {
        val createdAlertIds = mutableListOf<Long>()
        val now = clock()

        // 1. Job failure rate anomaly — computed from bounded recent terminal-attempt window (last 50)
        val recentAttempts = timedQuery("jobDao.recentTerminalAttempts") { jobDao.recentTerminalAttempts(AnomalyThresholds.JOB_FAILURE_WINDOW_ATTEMPTS) }
        val totalAttempts = recentAttempts.size
        val failedAttempts = recentAttempts.count { it.outcome == "failed" }
        if (AnomalyThresholds.jobFailuresTriggerAlert(failedAttempts, totalAttempts)) {
            val id = createAnomalyAlert(
                category = "job_failure",
                severity = "critical",
                title = "Job failure rate exceeded ${(AnomalyThresholds.JOB_FAILURE_RATE * 100).toInt()}%",
                body = "Failed: $failedAttempts / $totalAttempts recent attempts (last ${AnomalyThresholds.JOB_FAILURE_WINDOW_ATTEMPTS} window) exceed the ${(AnomalyThresholds.JOB_FAILURE_RATE * 100).toInt()}% threshold.",
                now = now,
            )
            if (id != null) createdAlertIds.add(id)
        }

        // 2. Overdue alerts global threshold
        val overdueCount = timedQuery("alertDao.overdueCount") { alertDao.overdueCount() }
        if (overdueCount >= AnomalyThresholds.OVERDUE_ALERTS_GLOBAL) {
            val id = createAnomalyAlert(
                category = "overdue_alerts",
                severity = "warn",
                title = "$overdueCount alerts are overdue (threshold: ${AnomalyThresholds.OVERDUE_ALERTS_GLOBAL})",
                body = "Global overdue alert count ($overdueCount) has reached the escalation threshold.",
                now = now,
            )
            if (id != null) createdAlertIds.add(id)
        }

        // 3. Slow Room query fraction anomaly — check if slow queries exceed threshold
        val oneHourAgo = now - 60 * 60 * 1000
        val slowCount = timedQuery("auditDao.slowSampleCountSince") { auditDao.slowSampleCountSince("query", oneHourAgo) }
        val totalSamples = timedQuery("auditDao.totalSampleCountSince") { auditDao.totalSampleCountSince("query", oneHourAgo) }
        if (AnomalyThresholds.slowQueriesTriggerAlert(slowCount, totalSamples)) {
            val percentiles = computePercentiles("query", oneHourAgo)
            val id = createAnomalyAlert(
                category = "slow_query",
                severity = "warn",
                title = "Slow query rate ${slowCount}/${totalSamples} exceeds ${(AnomalyThresholds.SLOW_QUERY_FRACTION * 100).toInt()}% threshold",
                body = "Query latency percentiles — p50: ${percentiles.p50}ms, p95: ${percentiles.p95}ms, p99: ${percentiles.p99}ms. ${slowCount} of ${totalSamples} queries exceeded ${AnomalyThresholds.SLOW_QUERY_MS}ms in the last hour.",
                now = now,
            )
            if (id != null) createdAlertIds.add(id)
        }

        if (createdAlertIds.isNotEmpty()) {
            auditLogger.record(
                action = "observability.anomalies_evaluated",
                targetType = "alert",
                reason = "created_alerts=${createdAlertIds.size}",
                payloadJson = "{\"alertIds\":${createdAlertIds}}",
            )
        }

        createdAlertIds
    }

    data class Percentiles(val p50: Long, val p95: Long, val p99: Long)

    /** Compute p50/p95/p99 latency percentiles from sorted performance samples. */
    suspend fun computePercentiles(kind: String, since: Long): Percentiles {
        val samples = auditDao.perfSamplesSince(kind, since) // already sorted by durationMs ASC
        if (samples.isEmpty()) return Percentiles(0, 0, 0)
        val durations = samples.map { it.durationMs }
        fun percentile(pct: Double): Long {
            val idx = ((pct / 100.0) * (durations.size - 1)).toInt().coerceIn(0, durations.size - 1)
            return durations[idx]
        }
        return Percentiles(p50 = percentile(50.0), p95 = percentile(95.0), p99 = percentile(99.0))
    }

    private suspend fun createAnomalyAlert(
        category: String,
        severity: String,
        title: String,
        body: String,
        now: Long,
    ): Long? {
        // Avoid duplicate alerts: skip if an open/acknowledged alert with the same category exists
        val existing = timedQuery("alertDao.listByStatus:open") { alertDao.listByStatus("open", 100, 0) } + timedQuery("alertDao.listByStatus:acknowledged") { alertDao.listByStatus("acknowledged", 100, 0) }
        if (existing.any { it.category == category }) return null

        val alert = AlertEntity(
            category = category,
            severity = severity,
            title = title,
            body = body,
            status = "open",
            ownerUserId = null,
            correlationId = UUID.randomUUID().toString(),
            dueAt = now + AlertPolicy.SLA_MILLIS,
            createdAt = now,
            updatedAt = now,
        )
        val id = alertDao.insert(alert)
        auditLogger.record(
            action = "alert.created_auto",
            targetType = "alert",
            targetId = id.toString(),
            reason = "anomaly:$category",
        )
        return id
    }

    /**
     * Compute and persist quality score snapshots for all users.
     * Should be called periodically (e.g. daily from the worker tick).
     */
    suspend fun computeQualityScores(): Int = withContext(Dispatchers.IO) {
        val users = userDao ?: return@withContext 0
        val qDao = qualityScoreDao ?: return@withContext 0
        val dupDao = duplicateDao ?: return@withContext 0

        val now = clock()
        val thirtyDaysAgo = now - 30L * 24 * 60 * 60 * 1000
        val sevenDaysAgo = now - 7L * 24 * 60 * 60 * 1000
        val allUsers = users.listAll()
        var snapshotsWritten = 0

        val impDao = importDao

        for (user in allUsers) {
            val violations = timedQuery("auditDao.countViolationsSince") { auditDao.countViolationsSince(user.id, thirtyDaysAgo) }
            val overdueAlerts = timedQuery("alertDao.overdueForUserSince") { alertDao.overdueForUserSince(user.id, thirtyDaysAgo) }
            val unresolvedDups = dupDao.unresolvedOlderThan(sevenDaysAgo)
            val validationFailures = impDao?.rejectedRowsForUserSince(user.id, thirtyDaysAgo) ?: 0

            val inputs = QualityScore.Inputs(
                validationFailures30d = validationFailures,
                policyViolations30d = violations,
                overdueAlerts30d = overdueAlerts,
                unresolvedDuplicates7d = unresolvedDups,
            )
            val score = QualityScore.compute(inputs)

            qDao.insert(
                QualityScoreSnapshotEntity(
                    userId = user.id,
                    score = score,
                    validationFailures30d = inputs.validationFailures30d,
                    policyViolations30d = inputs.policyViolations30d,
                    overdueAlerts30d = inputs.overdueAlerts30d,
                    unresolvedDuplicates7d = inputs.unresolvedDuplicates7d,
                    capturedAt = now,
                ),
            )
            snapshotsWritten++
        }
        snapshotsWritten
    }
}
