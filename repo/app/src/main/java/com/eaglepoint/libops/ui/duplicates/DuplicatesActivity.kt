package com.eaglepoint.libops.ui.duplicates

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.eaglepoint.libops.LibOpsApp
import com.eaglepoint.libops.observability.QueryTimer
import com.eaglepoint.libops.databinding.ActivityFeatureBinding
import com.eaglepoint.libops.domain.auth.Capabilities
import com.eaglepoint.libops.ui.AuthorizationGate
import com.eaglepoint.libops.ui.ChipTone
import com.eaglepoint.libops.ui.FeatureScreenHelper
import com.eaglepoint.libops.ui.TwoLineRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DuplicatesActivity : FragmentActivity() {

    private lateinit var binding: ActivityFeatureBinding
    private lateinit var helper: FeatureScreenHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AuthorizationGate.requireAccess(this, Capabilities.DUPLICATES_READ) ?: return
        binding = ActivityFeatureBinding.inflate(layoutInflater)
        setContentView(binding.root)
        helper = FeatureScreenHelper(this, binding)

        helper.setup(
            eyebrow = "Quality",
            title = "Duplicate Review",
            subtitle = "Tap a candidate to compare side-by-side and decide.",
            fabLabel = null,
            onRowClick = { row ->
                val id = row.id.removePrefix("dup-").toLongOrNull() ?: return@setup
                startActivity(Intent(this, MergeReviewActivity::class.java).putExtra("duplicateId", id))
            },
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
        val detected = withContext(Dispatchers.IO) { queryTimer.timed("query", "duplicateDao.listByStatus:detected") { app.db.duplicateDao().listByStatus("detected", 50, 0) } }
        val underReview = withContext(Dispatchers.IO) { queryTimer.timed("query", "duplicateDao.listByStatus:under_review") { app.db.duplicateDao().listByStatus("under_review", 50, 0) } }
        val escalated = withContext(Dispatchers.IO) { queryTimer.timed("query", "duplicateDao.listByStatus:escalated") { app.db.duplicateDao().listByStatus("escalated", 50, 0) } }
        val all = detected + underReview + escalated
        val rows = all.map { d ->
            TwoLineRow(
                id = "dup-${d.id}",
                primary = "Candidate #${d.id}",
                secondary = "score=${"%.3f".format(d.score)} • ${d.algorithm} • primary=${d.primaryRecordId ?: "—"} • incoming=${d.candidateStagingRef ?: "—"}",
                chipLabel = d.status.replace('_', ' '),
                chipTone = when (d.status) {
                    "detected" -> ChipTone.WARNING
                    "under_review" -> ChipTone.INFO
                    "escalated" -> ChipTone.ERROR
                    else -> ChipTone.NEUTRAL
                },
            )
        }
        helper.submit(
            rows,
            emptyTitle = "No duplicates pending",
            emptyBody = "Run an import to surface potential duplicates; resolved decisions live in audit logs.",
        )
    }
}
