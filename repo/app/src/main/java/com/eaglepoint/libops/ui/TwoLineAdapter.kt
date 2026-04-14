package com.eaglepoint.libops.ui

import android.graphics.PorterDuff
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.eaglepoint.libops.R

data class TwoLineRow(
    val id: String,
    val primary: String,
    val secondary: String,
    val chipLabel: String? = null,
    val chipTone: ChipTone = ChipTone.NEUTRAL,
)

enum class ChipTone { NEUTRAL, SUCCESS, WARNING, ERROR, INFO }

class TwoLineAdapter(
    private val onClick: (TwoLineRow) -> Unit = {},
) : ListAdapter<TwoLineRow, TwoLineAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_two_line, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = getItem(position)
        holder.primary.text = row.primary
        holder.secondary.text = row.secondary
        val chip = holder.chip
        if (row.chipLabel.isNullOrBlank()) {
            chip.visibility = View.GONE
        } else {
            chip.visibility = View.VISIBLE
            chip.text = row.chipLabel
            val ctx = chip.context
            val (bgColor, textColor) = when (row.chipTone) {
                ChipTone.SUCCESS -> ContextCompat.getColor(ctx, R.color.success_bg) to
                    ContextCompat.getColor(ctx, R.color.success)
                ChipTone.WARNING -> ContextCompat.getColor(ctx, R.color.warning_bg) to
                    ContextCompat.getColor(ctx, R.color.warning)
                ChipTone.ERROR -> ContextCompat.getColor(ctx, R.color.error_bg) to
                    ContextCompat.getColor(ctx, R.color.error)
                ChipTone.INFO -> ContextCompat.getColor(ctx, R.color.info_bg) to
                    ContextCompat.getColor(ctx, R.color.info)
                ChipTone.NEUTRAL -> ContextCompat.getColor(ctx, R.color.surface_variant) to
                    ContextCompat.getColor(ctx, R.color.on_surface_muted)
            }
            chip.background.mutate().setColorFilter(bgColor, PorterDuff.Mode.SRC_IN)
            chip.setTextColor(textColor)
        }
        holder.itemView.setOnClickListener { onClick(row) }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val primary: TextView = v.findViewById(R.id.primary)
        val secondary: TextView = v.findViewById(R.id.secondary)
        val chip: TextView = v.findViewById(R.id.chip)
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<TwoLineRow>() {
            override fun areItemsTheSame(oldItem: TwoLineRow, newItem: TwoLineRow) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: TwoLineRow, newItem: TwoLineRow) = oldItem == newItem
        }
    }
}

/** Map an entity status/outcome to its chip tone. */
fun chipToneFor(status: String): ChipTone = when (status) {
    "active", "succeeded", "accepted_all", "accepted_partial", "resolved", "completed" -> ChipTone.SUCCESS
    "failed", "terminal_failed", "rejected_all", "rejected_invalid_bundle",
    "rejected_validation_failure", "overdue", "rejected_with_errors" -> ChipTone.ERROR
    "retry_waiting", "paused_low_battery", "awaiting_merge_review", "acknowledged",
    "duplicate_pending", "escalated" -> ChipTone.WARNING
    "running", "queued", "scheduled", "validating", "staged", "open", "detected",
    "under_review" -> ChipTone.INFO
    else -> ChipTone.NEUTRAL
}
