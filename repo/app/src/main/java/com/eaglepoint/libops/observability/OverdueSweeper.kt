package com.eaglepoint.libops.observability

import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.data.db.dao.AlertDao
import com.eaglepoint.libops.data.db.entity.AlertAcknowledgementEntity
import com.eaglepoint.libops.domain.alerts.AlertPolicy
import com.eaglepoint.libops.domain.statemachine.AlertStateMachine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Periodic sweeper that enforces the 7-day SLA (§9.14, §10.7).
 *
 * Transitions:
 *  - open alerts past their due date -> overdue (via open -> acknowledged -> overdue
 *    is the state machine path, but the PRD also requires direct overdue marking
 *    for unacknowledged alerts; the state machine §10.7 does not define
 *    open -> overdue, so we first acknowledge then mark overdue)
 *  - acknowledged alerts past due -> overdue
 *
 * Each transition is audit-logged. The sweeper is called from the periodic
 * [CollectionRunWorker] tick so it runs even when no user is active.
 */
class OverdueSweeper(
    private val alertDao: AlertDao,
    private val auditLogger: AuditLogger,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val observability: ObservabilityPipeline? = null,
) {

    private val queryTimer: QueryTimer? = observability?.let { QueryTimer(it) }

    data class SweepResult(val transitioned: Int)

    suspend fun sweep(): SweepResult = withContext(Dispatchers.IO) {
        val now = clock()
        var transitioned = 0

        // Sweep acknowledged -> overdue
        val acknowledged = queryTimer?.let { it.timed("query", "alertDao.listByStatus:acknowledged") { alertDao.listByStatus("acknowledged", 500, 0) } } ?: alertDao.listByStatus("acknowledged", 500, 0)
        for (alert in acknowledged) {
            if (AlertPolicy.isOverdue(alert.createdAt, now)) {
                if (AlertStateMachine.canTransition("acknowledged", "overdue")) {
                    alertDao.update(
                        alert.copy(
                            status = "overdue",
                            updatedAt = now,
                            version = alert.version + 1,
                        )
                    )
                    auditLogger.record(
                        action = "alert.overdue_sweep",
                        targetType = "alert",
                        targetId = alert.id.toString(),
                        reason = "sla_breached_after_ack",
                        severity = AuditLogger.Severity.WARN,
                    )
                    transitioned++
                }
            }
        }

        // Sweep open -> overdue (open alerts that were never acknowledged within SLA)
        // AlertStateMachine allows open -> acknowledged, so for unacknowledged
        // overdue alerts we auto-acknowledge then transition to overdue.
        val open = queryTimer?.let { it.timed("query", "alertDao.listByStatus:open") { alertDao.listByStatus("open", 500, 0) } } ?: alertDao.listByStatus("open", 500, 0)
        for (alert in open) {
            if (AlertPolicy.isOverdue(alert.createdAt, now)) {
                // Auto-acknowledge first (open -> acknowledged is valid per §10.7)
                if (AlertStateMachine.canTransition("open", "acknowledged")) {
                    alertDao.update(
                        alert.copy(
                            status = "acknowledged",
                            updatedAt = now,
                            version = alert.version + 1,
                        )
                    )
                    alertDao.insertAck(
                        AlertAcknowledgementEntity(
                            alertId = alert.id,
                            userId = SYSTEM_ACTOR_ID,
                            acknowledgedAt = now,
                            note = "Auto-acknowledged by overdue sweep: SLA breached while unacknowledged",
                        )
                    )
                    // Then transition acknowledged -> overdue
                    val refreshed = (queryTimer?.let { it.timed("query", "alertDao.byId") { alertDao.byId(alert.id) } } ?: alertDao.byId(alert.id)) ?: continue
                    if (AlertStateMachine.canTransition("acknowledged", "overdue")) {
                        alertDao.update(
                            refreshed.copy(
                                status = "overdue",
                                updatedAt = now,
                                version = refreshed.version + 1,
                            )
                        )
                        auditLogger.record(
                            action = "alert.overdue_sweep",
                            targetType = "alert",
                            targetId = alert.id.toString(),
                            reason = "sla_breached_unacknowledged",
                            severity = AuditLogger.Severity.CRITICAL,
                        )
                        transitioned++
                    }
                }
            }
        }

        SweepResult(transitioned)
    }

    companion object {
        /** Sentinel userId for system-initiated actions (no human operator). */
        const val SYSTEM_ACTOR_ID = 0L
    }
}
