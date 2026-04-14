package com.eaglepoint.libops.ui.alerts

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.eaglepoint.libops.LibOpsApp
import com.eaglepoint.libops.observability.QueryTimer
import com.eaglepoint.libops.data.db.entity.AlertAcknowledgementEntity
import com.eaglepoint.libops.data.db.entity.AlertEntity
import com.eaglepoint.libops.data.db.entity.AlertResolutionEntity
import com.eaglepoint.libops.databinding.ActivityFeatureBinding
import com.eaglepoint.libops.domain.alerts.AlertPolicy
import com.eaglepoint.libops.domain.auth.Authorizer
import com.eaglepoint.libops.domain.auth.Capabilities
import com.eaglepoint.libops.domain.statemachine.AlertStateMachine
import com.eaglepoint.libops.ui.AuthorizationGate
import com.eaglepoint.libops.ui.FeatureScreenHelper
import com.eaglepoint.libops.ui.TwoLineRow
import com.eaglepoint.libops.ui.chipToneFor
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AlertsActivity : FragmentActivity() {

    private lateinit var binding: ActivityFeatureBinding
    private lateinit var helper: FeatureScreenHelper
    private var canAck = false
    private var canResolve = false
    private var userId: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = AuthorizationGate.requireAccess(this, Capabilities.ALERTS_READ) ?: return
        binding = ActivityFeatureBinding.inflate(layoutInflater)
        setContentView(binding.root)
        helper = FeatureScreenHelper(this, binding)

        val authz = Authorizer(session.capabilities)
        canAck = authz.has(Capabilities.ALERTS_ACKNOWLEDGE)
        canResolve = authz.has(Capabilities.ALERTS_RESOLVE)
        userId = session.userId

        helper.setup(
            eyebrow = "Risk",
            title = "Alerts",
            subtitle = "Closed-loop: open → acknowledged → resolved. Tap to progress.",
            fabLabel = if (canAck) "Seed demo alert" else null,
            onRowClick = { row -> cycle(row.id.removePrefix("alert-").toLongOrNull() ?: return@setup) },
            onFabClick = { seedAlert() },
        )
        lifecycleScope.launch { refresh() }
    }

    override fun onResume() {
        super.onResume()
        if (::helper.isInitialized) lifecycleScope.launch { refresh() }
    }

    private suspend fun refresh() {
        val app = application as LibOpsApp
        val queryTimer = QueryTimer(app.observabilityPipeline)
        val open = withContext(Dispatchers.IO) { queryTimer.timed("query", "alertDao.listByStatus:open") { app.db.alertDao().listByStatus("open", 50, 0) } }
        val ack = withContext(Dispatchers.IO) { queryTimer.timed("query", "alertDao.listByStatus:ack") { app.db.alertDao().listByStatus("acknowledged", 50, 0) } }
        val overdue = withContext(Dispatchers.IO) { queryTimer.timed("query", "alertDao.listByStatus:overdue") { app.db.alertDao().listByStatus("overdue", 50, 0) } }
        val resolved = withContext(Dispatchers.IO) { queryTimer.timed("query", "alertDao.listByStatus:resolved") { app.db.alertDao().listByStatus("resolved", 50, 0) } }
        val rows = (open + overdue + ack + resolved).map { a ->
            TwoLineRow(
                id = "alert-${a.id}",
                primary = a.title,
                secondary = "${a.severity.uppercase()} • ${a.category.replace('_', ' ')} • due ${prettyDue(a.dueAt)}",
                chipLabel = a.status.replace('_', ' '),
                chipTone = chipToneFor(a.status),
            )
        }
        helper.submit(
            rows,
            emptyTitle = "No alerts yet",
            emptyBody = "Anomalies create tickets here. Seed one to exercise the SLA flow.",
        )
    }

    private fun prettyDue(epoch: Long): String {
        val delta = epoch - System.currentTimeMillis()
        val hours = delta / 3_600_000
        return when {
            delta < 0 -> "overdue by ${-hours}h"
            hours < 24 -> "in ${hours}h"
            else -> "in ${hours / 24}d"
        }
    }

    private fun seedAlert() {
        if (AuthorizationGate.revalidateSession(this, Capabilities.ALERTS_ACKNOWLEDGE) == null) return
        val app = application as LibOpsApp
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                val id = app.db.alertDao().insert(
                    AlertEntity(
                        category = "job_failure",
                        severity = "warn",
                        title = "Demo alert ${now % 10_000}",
                        body = "Seeded for SLA demo",
                        status = "open",
                        ownerUserId = userId,
                        correlationId = null,
                        dueAt = now + AlertPolicy.SLA_MILLIS,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                app.auditLogger.record("alert.created", "alert", targetId = id.toString(), userId = userId)
            }
            refresh()
            Snackbar.make(binding.root, "Seeded demo alert", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun cycle(alertId: Long) {
        if (AuthorizationGate.revalidateSession(this, Capabilities.ALERTS_ACKNOWLEDGE) == null) return
        val app = application as LibOpsApp
        lifecycleScope.launch {
            val queryTimer = QueryTimer(app.observabilityPipeline)
            val alert = withContext(Dispatchers.IO) { queryTimer.timed("query", "alertDao.byId") { app.db.alertDao().byId(alertId) } } ?: return@launch
            val now = System.currentTimeMillis()
            when (alert.status) {
                "open" -> {
                    if (!canAck) { Snackbar.make(binding.root, "Missing alerts.acknowledge", Snackbar.LENGTH_SHORT).show(); return@launch }
                    if (!AlertStateMachine.canTransition("open", "acknowledged")) return@launch
                    withContext(Dispatchers.IO) {
                        app.db.alertDao().update(alert.copy(status = "acknowledged", updatedAt = now, version = alert.version + 1))
                        app.db.alertDao().insertAck(AlertAcknowledgementEntity(alertId = alertId, userId = userId, acknowledgedAt = now, note = null))
                        app.auditLogger.record("alert.acknowledged", "alert", targetId = alertId.toString(), userId = userId)
                    }
                }
                "acknowledged", "overdue" -> {
                    if (!canResolve) { Snackbar.make(binding.root, "Missing alerts.resolve", Snackbar.LENGTH_SHORT).show(); return@launch }
                    val note = "Resolved via UI at ${java.time.Instant.ofEpochMilli(now)}"
                    val errs = AlertPolicy.validateResolution(alert.status, note)
                    if (errs.isNotEmpty()) { Snackbar.make(binding.root, errs.joinToString { it.message }, Snackbar.LENGTH_LONG).show(); return@launch }
                    withContext(Dispatchers.IO) {
                        app.db.alertDao().update(alert.copy(status = "resolved", updatedAt = now, version = alert.version + 1))
                        app.db.alertDao().insertResolution(AlertResolutionEntity(alertId = alertId, userId = userId, resolvedAt = now, note = note))
                        app.auditLogger.record("alert.resolved", "alert", targetId = alertId.toString(), userId = userId)
                    }
                }
                else -> Snackbar.make(binding.root, "No next step from ${alert.status}", Snackbar.LENGTH_SHORT).show()
            }
            refresh()
        }
    }
}
