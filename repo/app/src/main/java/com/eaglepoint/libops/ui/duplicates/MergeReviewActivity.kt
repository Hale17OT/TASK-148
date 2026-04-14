package com.eaglepoint.libops.ui.duplicates

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.eaglepoint.libops.LibOpsApp
import com.eaglepoint.libops.data.db.entity.MergeDecisionEntity
import com.eaglepoint.libops.databinding.ActivityMergeReviewBinding
import com.eaglepoint.libops.domain.auth.Capabilities
import com.eaglepoint.libops.domain.statemachine.DuplicateStateMachine
import com.eaglepoint.libops.observability.QueryTimer
import com.eaglepoint.libops.ui.AuthorizationGate
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Side-by-side guided merge (§9.6). Enforces:
 * - capability `duplicates.resolve`
 * - state machine transitions (detected → under_review → merged|dismissed|escalated)
 * - rationale min length 10 chars
 * - merge decision with full provenance persisted
 * - audit event written on every decision
 */
class MergeReviewActivity : FragmentActivity() {

    private lateinit var binding: ActivityMergeReviewBinding
    private var duplicateId: Long = -1L
    private var userId: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = AuthorizationGate.requireAccess(this, Capabilities.DUPLICATES_RESOLVE) ?: return
        binding = ActivityMergeReviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        userId = session.userId

        duplicateId = intent.getLongExtra("duplicateId", -1L)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.mergeButton.setOnClickListener { decide("merged") }
        binding.dismissButton.setOnClickListener { decide("dismissed") }
        binding.escalateButton.setOnClickListener { decide("escalated") }

        lifecycleScope.launch { load() }
    }

    private suspend fun load() {
        val app = application as LibOpsApp
        val queryTimer = QueryTimer(app.observabilityPipeline)
        val dup = withContext(Dispatchers.IO) {
            queryTimer.timed("query", "duplicateDao.byId") { app.db.duplicateDao().byId(duplicateId) }
        }?.takeIf { it.status in setOf("detected", "under_review", "escalated") } ?: run {
            Snackbar.make(binding.root, "Duplicate not found", Snackbar.LENGTH_SHORT).show()
            finish(); return
        }

        binding.scoreText.text = "Score ${"%.3f".format(dup.score)} via ${dup.algorithm}"

        val primary = dup.primaryRecordId?.let {
            withContext(Dispatchers.IO) { queryTimer.timed("query", "recordDao.byId") { app.db.recordDao().byId(it) } }
        }
        val staging = dup.candidateStagingRef

        binding.primaryBody.text = if (primary != null) {
            "Title: ${primary.title}\nPublisher: ${primary.publisher ?: "—"}\nISBN-13: ${primary.isbn13 ?: "—"}\nCategory: ${primary.category}\nStatus: ${primary.status}"
        } else "—"

        binding.candidateBody.text = if (staging != null) {
            "Staged import reference:\n$staging\n(Rationale should describe why the incoming row represents — or does not represent — the same work.)"
        } else "—"

        // If the duplicate is still `detected`, mark it under_review on open.
        if (dup.status == "detected" &&
            DuplicateStateMachine.canTransition("detected", "under_review")
        ) {
            withContext(Dispatchers.IO) {
                app.db.duplicateDao().update(dup.copy(status = "under_review"))
            }
        }
    }

    private fun decide(decision: String) {
        if (AuthorizationGate.revalidateSession(this, Capabilities.DUPLICATES_RESOLVE) == null) return
        val rationale = binding.rationaleInput.text?.toString()?.trim().orEmpty()
        if (rationale.length < 10) {
            binding.statusText.text = "Rationale must be at least 10 characters."
            return
        }
        val app = application as LibOpsApp
        lifecycleScope.launch {
            val dup = withContext(Dispatchers.IO) {
                app.db.duplicateDao().byId(duplicateId)
            } ?: return@launch

            if (!DuplicateStateMachine.canTransition(dup.status, decision)) {
                binding.statusText.text = "Illegal transition from ${dup.status} to $decision"
                return@launch
            }

            val now = System.currentTimeMillis()
            val provenance = JSONObject()
                .put("fromStatus", dup.status)
                .put("decidedAt", now)
                .put("operatorUserId", userId)
                .put("candidateStagingRef", dup.candidateStagingRef.orEmpty())
                .put("primaryRecordId", dup.primaryRecordId ?: JSONObject.NULL)
                .put("score", dup.score)
                .put("algorithm", dup.algorithm)
                .toString()

            withContext(Dispatchers.IO) {
                app.db.duplicateDao().update(
                    dup.copy(status = decision, reviewedByUserId = userId, reviewedAt = now),
                )
                app.db.duplicateDao().insertDecision(
                    MergeDecisionEntity(
                        duplicateId = dup.id,
                        operatorUserId = userId,
                        decision = decision,
                        rationale = rationale,
                        keptRecordId = if (decision == "merged") dup.primaryRecordId else null,
                        mergedFromRecordId = if (decision == "merged") dup.candidateRecordId else null,
                        provenanceJson = provenance,
                        decidedAt = now,
                    ),
                )
                app.auditLogger.record(
                    action = "duplicate.$decision",
                    targetType = "duplicate_candidate",
                    targetId = dup.id.toString(),
                    userId = userId,
                    reason = rationale.take(120),
                    payloadJson = provenance,
                )
            }
            Snackbar.make(binding.root, "Recorded: $decision", Snackbar.LENGTH_SHORT).show()
            finish()
        }
    }
}
