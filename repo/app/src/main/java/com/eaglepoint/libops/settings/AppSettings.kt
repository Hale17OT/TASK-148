package com.eaglepoint.libops.settings

import android.content.Context
import android.content.SharedPreferences
import com.eaglepoint.libops.domain.orchestration.BatteryAwareness
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Admin-configurable runtime settings (§15).
 *
 * - parallelism: 1..6, default 3
 * - batteryThresholdPct: 10..25, default 15
 *
 * Backed by SharedPreferences so changes survive process death and are
 * picked up immediately by the scheduler/worker via the [Snapshot] flow.
 */
class AppSettings private constructor(private val prefs: SharedPreferences) {

    data class Snapshot(val parallelism: Int, val batteryThresholdPct: Int)

    private val _flow = MutableStateFlow(loadFromPrefs())
    val flow: StateFlow<Snapshot> = _flow.asStateFlow()

    fun current(): Snapshot = _flow.value

    fun updateParallelism(value: Int) {
        val bounded = value.coerceIn(MIN_PARALLELISM, MAX_PARALLELISM)
        prefs.edit().putInt(KEY_PARALLELISM, bounded).apply()
        _flow.value = _flow.value.copy(parallelism = bounded)
    }

    fun updateBatteryThreshold(value: Int) {
        val bounded = value.coerceIn(BatteryAwareness.MIN_THRESHOLD, BatteryAwareness.MAX_THRESHOLD)
        prefs.edit().putInt(KEY_BATTERY_THRESHOLD, bounded).apply()
        _flow.value = _flow.value.copy(batteryThresholdPct = bounded)
    }

    private fun loadFromPrefs(): Snapshot {
        val p = prefs.getInt(KEY_PARALLELISM, DEFAULT_PARALLELISM)
            .coerceIn(MIN_PARALLELISM, MAX_PARALLELISM)
        val b = prefs.getInt(KEY_BATTERY_THRESHOLD, BatteryAwareness.DEFAULT_THRESHOLD_PCT)
            .coerceIn(BatteryAwareness.MIN_THRESHOLD, BatteryAwareness.MAX_THRESHOLD)
        return Snapshot(p, b)
    }

    companion object {
        const val PREFS_NAME = "libops.admin_settings"
        const val KEY_PARALLELISM = "parallelism"
        const val KEY_BATTERY_THRESHOLD = "battery_threshold"
        const val DEFAULT_PARALLELISM = 3
        const val MIN_PARALLELISM = 1
        const val MAX_PARALLELISM = 6

        @Volatile private var instance: AppSettings? = null

        fun get(context: Context): AppSettings = instance ?: synchronized(this) {
            instance ?: AppSettings(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            ).also { instance = it }
        }
    }
}
