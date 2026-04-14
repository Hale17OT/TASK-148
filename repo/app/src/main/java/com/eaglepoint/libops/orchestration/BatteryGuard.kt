package com.eaglepoint.libops.orchestration

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Samples current battery percentage + charging state for the scheduler.
 * Uses a sticky broadcast for an instant read without a listener.
 */
object BatteryGuard {

    data class Sample(val percent: Int, val charging: Boolean)

    fun current(context: Context): Sample {
        val intent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return Sample(100, true)
        val level = intent!!.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level < 0 || scale <= 0) 100 else (level * 100 / scale)
        return Sample(pct, charging)
    }
}
