package com.eaglepoint.libops.ui

import android.view.View
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.eaglepoint.libops.databinding.ActivityFeatureBinding

/**
 * Configures the shared `activity_feature.xml` chrome (toolbar, header,
 * empty state, FAB) so every feature Activity ends up with consistent
 * look & behavior. Keeps each Activity focused on its business logic.
 */
class FeatureScreenHelper(
    private val activity: FragmentActivity,
    val binding: ActivityFeatureBinding,
) {
    val adapter: TwoLineAdapter = TwoLineAdapter { binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK) }

    fun setup(
        eyebrow: String,
        title: String,
        subtitle: String,
        fabLabel: String?,
        fabEnabled: Boolean = true,
        onRowClick: (TwoLineRow) -> Unit = {},
        onFabClick: () -> Unit = {},
    ) {
        binding.toolbar.title = ""
        binding.toolbar.setNavigationOnClickListener { activity.onBackPressedDispatcher.onBackPressed() }
        binding.headerEyebrow.text = eyebrow
        binding.headerTitle.text = title
        binding.headerSubtitle.text = subtitle
        binding.itemList.layoutManager = LinearLayoutManager(activity)

        // Rewire adapter with actual row click handler
        val realAdapter = TwoLineAdapter(onRowClick)
        binding.itemList.adapter = realAdapter
        rewiredAdapter = realAdapter

        if (fabLabel != null) {
            binding.primaryFab.text = fabLabel
            binding.primaryFab.visibility = View.VISIBLE
            binding.primaryFab.isEnabled = fabEnabled
            binding.primaryFab.alpha = if (fabEnabled) 1.0f else 0.5f
            binding.primaryFab.setOnClickListener { if (fabEnabled) onFabClick() }
        } else {
            binding.primaryFab.visibility = View.GONE
        }
    }

    private var rewiredAdapter: TwoLineAdapter = adapter

    /** Publish rows; shows empty state if [rows] is empty. */
    fun submit(rows: List<TwoLineRow>, emptyTitle: String, emptyBody: String) {
        rewiredAdapter.submitList(rows)
        if (rows.isEmpty()) {
            binding.emptyState.visibility = View.VISIBLE
            binding.emptyTitle.text = emptyTitle
            binding.emptyBody.text = emptyBody
            binding.itemList.visibility = View.GONE
        } else {
            binding.emptyState.visibility = View.GONE
            binding.itemList.visibility = View.VISIBLE
        }
    }
}
