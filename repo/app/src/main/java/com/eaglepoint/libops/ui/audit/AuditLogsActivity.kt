package com.eaglepoint.libops.ui.audit

import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.eaglepoint.libops.LibOpsApp
import com.eaglepoint.libops.observability.QueryTimer
import com.eaglepoint.libops.databinding.ActivityFeatureBinding
import com.eaglepoint.libops.domain.audit.AuditChain
import com.eaglepoint.libops.domain.auth.Capabilities
import com.eaglepoint.libops.ui.AuthorizationGate
import com.eaglepoint.libops.ui.ChipTone
import com.eaglepoint.libops.ui.FeatureScreenHelper
import com.eaglepoint.libops.ui.TwoLineRow
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AuditLogsActivity : FragmentActivity() {

    private lateinit var binding: ActivityFeatureBinding
    private lateinit var helper: FeatureScreenHelper

    // Active filter state
    private var filterUserId: Long? = null
    private var filterCorrelationId: String? = null
    private var filterTimeRangeHours: Long = 24 * 30 // default 30 days

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AuthorizationGate.requireAccess(this, Capabilities.AUDIT_READ) ?: return
        binding = ActivityFeatureBinding.inflate(layoutInflater)
        setContentView(binding.root)
        helper = FeatureScreenHelper(this, binding)

        helper.setup(
            eyebrow = "Observability",
            title = "Audit Logs",
            subtitle = filterSummary(),
            fabLabel = "Actions",
            onFabClick = { showActionMenu() },
        )

        lifecycleScope.launch { refresh() }
    }

    override fun onResume() {
        super.onResume()
        if (::helper.isInitialized) lifecycleScope.launch { refresh() }
    }

    private fun filterSummary(): String {
        val parts = mutableListOf<String>()
        val rangeLabel = when {
            filterTimeRangeHours <= 1 -> "last 1h"
            filterTimeRangeHours <= 24 -> "last ${filterTimeRangeHours}h"
            else -> "last ${filterTimeRangeHours / 24}d"
        }
        parts.add(rangeLabel)
        if (filterUserId != null) parts.add("user=$filterUserId")
        if (filterCorrelationId != null) parts.add("corr=${filterCorrelationId!!.take(12)}")
        return parts.joinToString(" | ")
    }

    private fun showActionMenu() {
        val items = arrayOf("Set filters", "Clear filters", "Verify hash chain")
        AlertDialog.Builder(this)
            .setTitle("Audit actions")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> showFilterDialog()
                    1 -> clearFilters()
                    2 -> verifyChain()
                }
            }.show()
    }

    private fun showFilterDialog() {
        val dp16 = (16 * resources.displayMetrics.density).toInt()
        val dp8 = (8 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp16, dp16, dp16, dp8)
        }

        val labelStyle = { text: String ->
            TextView(this).apply {
                this.text = text
                textSize = 12f
                setPadding(0, dp8, 0, dp8 / 2)
            }
        }

        layout.addView(labelStyle("Time range"))
        val timeRangeInput = EditText(this).apply {
            hint = "Hours (e.g. 24 = last day, 720 = last 30d)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(filterTimeRangeHours.toString())
        }
        layout.addView(timeRangeInput)

        layout.addView(labelStyle("User ID (blank = all)"))
        val userIdInput = EditText(this).apply {
            hint = "e.g. 1"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(filterUserId?.toString().orEmpty())
        }
        layout.addView(userIdInput)

        layout.addView(labelStyle("Correlation ID prefix (blank = all)"))
        val corrInput = EditText(this).apply {
            hint = "e.g. a1b2c3d4"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setText(filterCorrelationId.orEmpty())
        }
        layout.addView(corrInput)

        AlertDialog.Builder(this)
            .setTitle("Filter audit events")
            .setView(layout)
            .setPositiveButton("Apply") { _, _ ->
                val hours = timeRangeInput.text.toString().toLongOrNull()
                if (hours != null && hours > 0) filterTimeRangeHours = hours
                filterUserId = userIdInput.text.toString().toLongOrNull()
                val corr = corrInput.text.toString().trim()
                filterCorrelationId = corr.ifEmpty { null }
                binding.headerSubtitle.text = filterSummary()
                lifecycleScope.launch { refresh() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearFilters() {
        filterUserId = null
        filterCorrelationId = null
        filterTimeRangeHours = 24 * 30
        binding.headerSubtitle.text = filterSummary()
        lifecycleScope.launch { refresh() }
        Snackbar.make(binding.root, "Filters cleared", Snackbar.LENGTH_SHORT).show()
    }

    private suspend fun refresh() {
        val app = application as LibOpsApp
        val queryTimer = QueryTimer(app.observabilityPipeline)
        val now = System.currentTimeMillis()
        val fromMs = now - filterTimeRangeHours * 60 * 60 * 1000
        val events = withContext(Dispatchers.IO) {
            queryTimer.timed("query", "auditDao.search") {
                app.db.auditDao().search(
                    userId = filterUserId,
                    correlationPrefix = filterCorrelationId,
                    fromMs = fromMs,
                    toMs = now,
                    limit = 300,
                    offset = 0,
                )
            }
        }
        val rows = events.map { e ->
            TwoLineRow(
                id = "audit-${e.id}",
                primary = e.action,
                secondary = "user=${e.userId ?: "\u2014"} \u2022 target=${e.targetType}/${e.targetId.orEmpty()} \u2022 corr=${e.correlationId.take(8)}",
                chipLabel = e.severity,
                chipTone = when (e.severity) {
                    "critical" -> ChipTone.ERROR
                    "warn" -> ChipTone.WARNING
                    else -> ChipTone.NEUTRAL
                },
            )
        }
        helper.submit(
            rows,
            emptyTitle = "No events match",
            emptyBody = "Try adjusting your filters or expanding the time range.",
        )
    }

    private fun verifyChain() {
        val app = application as LibOpsApp
        val queryTimer = QueryTimer(app.observabilityPipeline)
        lifecycleScope.launch {
            val events = withContext(Dispatchers.IO) {
                queryTimer.timed("query", "auditDao.allEventsChronological") { app.db.auditDao().allEventsChronological() }
            }
            val stored = events.map { e ->
                AuditChain.StoredEvent(
                    AuditChain.EventFields(
                        correlationId = e.correlationId,
                        userId = e.userId,
                        action = e.action,
                        targetType = e.targetType,
                        targetId = e.targetId,
                        severity = e.severity,
                        reason = e.reason,
                        payloadJson = e.payloadJson,
                        createdAt = e.createdAt,
                    ),
                    e.eventHash,
                )
            }
            val result = AuditChain.verify(stored)
            val message = if (result.ok)
                "Hash chain OK \u2014 ${events.size} events verified"
            else
                "INTEGRITY FAILURE at index ${result.brokenAtIndex}"
            Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
        }
    }
}
