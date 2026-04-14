package com.eaglepoint.libops.ui.analytics

import android.os.Bundle
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.eaglepoint.libops.LibOpsApp
import com.eaglepoint.libops.R
import com.eaglepoint.libops.analytics.AnalyticsRepository
import com.eaglepoint.libops.databinding.ActivityAnalyticsBinding
import com.eaglepoint.libops.domain.auth.Capabilities
import com.eaglepoint.libops.observability.QueryTimer
import com.eaglepoint.libops.ui.AuthorizationGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AnalyticsActivity : FragmentActivity() {

    private lateinit var binding: ActivityAnalyticsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AuthorizationGate.requireAccess(this, Capabilities.ANALYTICS_READ) ?: return
        binding = ActivityAnalyticsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        lifecycleScope.launch { refresh() }
    }

    private suspend fun refresh() {
        val app = application as LibOpsApp
        val queryTimer = QueryTimer(app.observabilityPipeline)
        val repo = AnalyticsRepository(app.db)
        val dashboard = withContext(Dispatchers.IO) {
            queryTimer.timed("query", "analyticsRepository.compute") { repo.compute() }
        }

        bindKpi(binding.kpiConfigured.root, "Configured", dashboard.configuredSources.toString(), "Sources defined")
        bindKpi(binding.kpiQueued.root, "Queued", dashboard.queuedJobs.toString(), "Awaiting execution")
        bindKpi(binding.kpiProcessed.root, "Processed", dashboard.processedJobsLast24h.toString(), "Terminal outcomes")
        bindKpi(binding.kpiAccepted.root, "Accepted", dashboard.acceptedRecords.toString(), "Active records")
        bindKpi(binding.kpiDuplicates.root, "Dup. pending", dashboard.duplicatesPending.toString(), "Older than 7d")
        bindKpi(binding.kpiQuality.root, "Quality", dashboard.avgQualityScore.toString(), "Avg score 0\u2013100")
        bindKpi(binding.kpiSpend.root, "Acq. spend", dashboard.acquisitionSpend.toString(), "Import batches run")
        bindKpi(binding.kpiOrdered.root, "Items ordered", dashboard.itemsOrdered.toString(), "Rows submitted")
        bindKpi(binding.kpiReturned.root, "Items returned", dashboard.itemsReturned.toString(), "Rows rejected")
        bindKpi(binding.kpiOpenAlerts.root, "Open alerts", dashboard.openAlerts.toString(), "Require attention")
        bindKpi(binding.kpiOverdue.root, "Overdue", dashboard.overdueAlerts.toString(), "Past SLA")
    }

    private fun bindKpi(root: android.view.View, label: String, value: String, subtext: String) {
        root.findViewById<TextView>(R.id.kpiLabel).text = label
        root.findViewById<TextView>(R.id.kpiValue).text = value
        root.findViewById<TextView>(R.id.kpiSubtext).text = subtext
    }
}
