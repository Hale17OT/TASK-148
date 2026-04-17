package com.eaglepoint.libops.tests

import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.data.db.entity.AlertEntity
import com.eaglepoint.libops.observability.OverdueSweeper
import com.eaglepoint.libops.tests.fakes.FakeAlertDao
import com.eaglepoint.libops.tests.fakes.FakeAuditDao
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

/**
 * Tests for [OverdueSweeper.sweep] — the periodic sweep that transitions
 * alerts past their 7-day SLA to `overdue`, auto-acknowledging open alerts
 * along the way (§9.14, §10.7).
 */
class OverdueSweeperTest {

    private lateinit var alertDao: FakeAlertDao
    private lateinit var auditDao: FakeAuditDao
    private lateinit var audit: AuditLogger
    private lateinit var sweeper: OverdueSweeper

    private val now = 1_700_000_000_000L
    private val eightDaysAgo = now - 8L * 24 * 60 * 60 * 1000L
    private val oneDayAgo = now - 1L * 24 * 60 * 60 * 1000L

    @Before
    fun setUp() {
        alertDao = FakeAlertDao()
        auditDao = FakeAuditDao()
        audit = AuditLogger(auditDao, clock = { now })
        sweeper = OverdueSweeper(alertDao, audit, clock = { now })
    }

    private fun alert(id: Long, status: String, createdAt: Long) = AlertEntity(
        id = id, category = "job_failure", severity = "warn",
        title = "Test alert", body = "body", status = status,
        ownerUserId = null, correlationId = null,
        dueAt = createdAt + 7L * 24 * 60 * 60 * 1000L,
        createdAt = createdAt, updatedAt = createdAt, version = 1,
    )

    // ── no transitions ─────────────────────────────────────────────────────────

    @Test
    fun recent_open_alert_is_not_transitioned(): Unit = runBlocking {
        alertDao.insert(alert(0L, "open", oneDayAgo))
        val result = sweeper.sweep()
        assertThat(result.transitioned).isEqualTo(0)
    }

    @Test
    fun recent_acknowledged_alert_is_not_transitioned(): Unit = runBlocking {
        alertDao.insert(alert(0L, "acknowledged", oneDayAgo))
        val result = sweeper.sweep()
        assertThat(result.transitioned).isEqualTo(0)
    }

    @Test
    fun empty_alert_list_returns_zero_transitions(): Unit = runBlocking {
        val result = sweeper.sweep()
        assertThat(result.transitioned).isEqualTo(0)
    }

    // ── acknowledged → overdue ─────────────────────────────────────────────────

    @Test
    fun acknowledged_alert_past_sla_transitions_to_overdue(): Unit = runBlocking {
        alertDao.insert(alert(0L, "acknowledged", eightDaysAgo))
        val result = sweeper.sweep()
        assertThat(result.transitioned).isEqualTo(1)

        val finalAlert = alertDao.all()[0]
        assertThat(finalAlert.status).isEqualTo("overdue")
    }

    @Test
    fun acknowledged_sweep_records_audit_event(): Unit = runBlocking {
        alertDao.insert(alert(0L, "acknowledged", eightDaysAgo))
        sweeper.sweep()

        val events = auditDao.allEventsChronological()
        assertThat(events.any { it.action == "alert.overdue_sweep" }).isTrue()
    }

    // ── open → acknowledged → overdue (auto-ack path) ──────────────────────────

    @Test
    fun open_alert_past_sla_is_auto_acknowledged_then_overdue(): Unit = runBlocking {
        alertDao.insert(alert(0L, "open", eightDaysAgo))
        val result = sweeper.sweep()
        assertThat(result.transitioned).isEqualTo(1)

        val finalAlert = alertDao.all()[0]
        assertThat(finalAlert.status).isEqualTo("overdue")
    }

    @Test
    fun auto_acknowledged_overdue_sweep_is_critical_severity(): Unit = runBlocking {
        alertDao.insert(alert(0L, "open", eightDaysAgo))
        sweeper.sweep()

        val events = auditDao.allEventsChronological()
        val sweepEvent = events.firstOrNull { it.action == "alert.overdue_sweep" }
        assertThat(sweepEvent).isNotNull()
        assertThat(sweepEvent!!.severity).isEqualTo("critical")
    }

    // ── mixed batch ────────────────────────────────────────────────────────────

    @Test
    fun mixed_recent_and_overdue_alerts_only_transitions_overdue_ones(): Unit = runBlocking {
        alertDao.insert(alert(0L, "open", oneDayAgo))          // recent — stays open
        alertDao.insert(alert(0L, "acknowledged", eightDaysAgo)) // overdue — transitions
        alertDao.insert(alert(0L, "open", eightDaysAgo))       // overdue — auto-ack + transitions

        val result = sweeper.sweep()
        assertThat(result.transitioned).isEqualTo(2)

        val statuses = alertDao.all().map { it.status }.toSet()
        assertThat(statuses).contains("open")
        assertThat(statuses).contains("overdue")
    }
}
