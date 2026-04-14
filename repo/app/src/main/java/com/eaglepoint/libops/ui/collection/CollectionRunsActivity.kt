package com.eaglepoint.libops.ui.collection

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.eaglepoint.libops.LibOpsApp
import com.eaglepoint.libops.databinding.ActivityFeatureBinding
import com.eaglepoint.libops.domain.auth.Authorizer
import com.eaglepoint.libops.domain.auth.Capabilities
import com.eaglepoint.libops.orchestration.JobScheduler
import com.eaglepoint.libops.ui.AuthorizationGate
import com.eaglepoint.libops.ui.FeatureScreenHelper
import com.eaglepoint.libops.ui.TwoLineRow
import com.eaglepoint.libops.ui.chipToneFor
import com.eaglepoint.libops.observability.QueryTimer
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CollectionRunsActivity : FragmentActivity() {

    private lateinit var binding: ActivityFeatureBinding
    private lateinit var helper: FeatureScreenHelper
    private var canManageSources = false
    private var canRunJobs = false
    private var userId: Long = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = AuthorizationGate.requireAccess(this, Capabilities.JOBS_READ) ?: return
        binding = ActivityFeatureBinding.inflate(layoutInflater)
        setContentView(binding.root)
        helper = FeatureScreenHelper(this, binding)

        val authz = Authorizer(session.capabilities)
        canManageSources = authz.has(Capabilities.SOURCES_MANAGE)
        canRunJobs = authz.has(Capabilities.JOBS_RUN)
        userId = session.userId

        helper.setup(
            eyebrow = "Orchestration",
            title = "Collection Runs",
            subtitle = "Sources, queued jobs, retries — tap a source to edit.",
            fabLabel = if (canManageSources) "New source" else null,
            onRowClick = { row ->
                val sourceId = row.id.removePrefix("src-").toLongOrNull() ?: return@setup
                startActivity(Intent(this, SourceEditorActivity::class.java).putExtra("sourceId", sourceId))
            },
            onFabClick = {
                startActivity(Intent(this, SourceEditorActivity::class.java))
            },
        )

        // Long-press FAB to manually enqueue a run for the first active source.
        binding.primaryFab.setOnLongClickListener {
            if (!canRunJobs) {
                Snackbar.make(binding.root, "Missing jobs.run", Snackbar.LENGTH_SHORT).show()
                return@setOnLongClickListener true
            }
            enqueueFirstActive()
            true
        }

        lifecycleScope.launch { refresh() }
    }

    override fun onResume() {
        super.onResume()
        if (::helper.isInitialized) lifecycleScope.launch { refresh() }
    }

    private suspend fun refresh() {
        val app = application as LibOpsApp
        val queryTimer = QueryTimer(app.observabilityPipeline)
        val sources = withContext(Dispatchers.IO) {
            queryTimer.timed("query", "collectionSourceDao.listAll") { app.db.collectionSourceDao().listAll() }
        }
        val runnable = withContext(Dispatchers.IO) {
            queryTimer.timed("query", "jobDao.nextRunnable") { app.db.jobDao().nextRunnable(100, System.currentTimeMillis()) }
        }
        val rows = mutableListOf<TwoLineRow>()
        sources.forEach { s ->
            rows += TwoLineRow(
                id = "src-${s.id}",
                primary = s.name,
                secondary = "${s.entryType} • ${s.refreshMode} • priority ${s.priority} • retry ${s.retryBackoff}",
                chipLabel = s.state,
                chipTone = chipToneFor(s.state),
            )
        }
        runnable.forEach { j ->
            rows += TwoLineRow(
                id = "job-${j.id}",
                primary = "Job #${j.id}",
                secondary = "source=${j.sourceId} • priority ${j.priority} • retry ${j.retryCount}",
                chipLabel = j.status.replace('_', ' '),
                chipTone = chipToneFor(j.status),
            )
        }
        helper.submit(
            rows,
            emptyTitle = "No sources yet",
            emptyBody = if (canManageSources)
                "Tap \u201CNew source\u201D to configure your first collection source."
            else
                "Ask a Collection Manager to configure a source.",
        )
    }

    private fun enqueueFirstActive() {
        if (AuthorizationGate.revalidateSession(this, Capabilities.JOBS_RUN) == null) return
        val app = application as LibOpsApp
        lifecycleScope.launch {
            val queryTimer = QueryTimer(app.observabilityPipeline)
            val active = withContext(Dispatchers.IO) {
                queryTimer.timed("query", "collectionSourceDao.listByState") { app.db.collectionSourceDao().listByState("active") }.firstOrNull()
            }
            if (active == null) {
                Snackbar.make(binding.root, "No active sources to enqueue", Snackbar.LENGTH_SHORT).show()
                return@launch
            }
            val res = app.jobScheduler.enqueueManual(active.id, userId)
            val text = when (res) {
                is JobScheduler.EnqueueResult.Enqueued -> "Enqueued job #${res.jobId} for ${active.name}"
                is JobScheduler.EnqueueResult.SourceNotActive -> "Source state: ${res.state}"
                JobScheduler.EnqueueResult.SourceNotFound -> "Source not found"
            }
            Snackbar.make(binding.root, text, Snackbar.LENGTH_SHORT).show()
            refresh()
        }
    }
}
