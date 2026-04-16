package com.eaglepoint.libops.tests

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.eaglepoint.libops.domain.orchestration.BatteryAwareness
import com.eaglepoint.libops.settings.AppSettings
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [AppSettings] covering default values, clamping behaviour for
 * [AppSettings.updateParallelism] and [AppSettings.updateBatteryThreshold],
 * and StateFlow emission after each mutation (§15).
 *
 * Each test creates a fresh [AppSettings] instance via the private constructor
 * using reflection — bypassing the singleton so tests remain independent of
 * each other without requiring static-state resets between methods.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AppSettingsTest {

    private lateinit var context: Context
    private lateinit var settings: AppSettings

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Bypass the public singleton factory so each test gets fresh SharedPreferences.
        val prefs: SharedPreferences = context.getSharedPreferences(
            "test_appsettings_${System.nanoTime()}",
            Context.MODE_PRIVATE,
        )
        val ctor = AppSettings::class.java.getDeclaredConstructor(SharedPreferences::class.java)
            .apply { isAccessible = true }
        settings = ctor.newInstance(prefs) as AppSettings
    }

    // ── defaults ──────────────────────────────────────────────────────────────

    @Test
    fun default_parallelism_equals_three() {
        assertThat(settings.current().parallelism).isEqualTo(AppSettings.DEFAULT_PARALLELISM)
    }

    @Test
    fun default_battery_threshold_equals_fifteen_percent() {
        assertThat(settings.current().batteryThresholdPct).isEqualTo(BatteryAwareness.DEFAULT_THRESHOLD_PCT)
    }

    // ── updateParallelism clamping ─────────────────────────────────────────────

    @Test
    fun update_parallelism_below_min_clamps_to_one() {
        settings.updateParallelism(0)
        assertThat(settings.current().parallelism).isEqualTo(AppSettings.MIN_PARALLELISM)
    }

    @Test
    fun update_parallelism_above_max_clamps_to_six() {
        settings.updateParallelism(99)
        assertThat(settings.current().parallelism).isEqualTo(AppSettings.MAX_PARALLELISM)
    }

    @Test
    fun update_parallelism_valid_value_accepted() {
        settings.updateParallelism(4)
        assertThat(settings.current().parallelism).isEqualTo(4)
    }

    @Test
    fun update_parallelism_min_boundary_accepted() {
        settings.updateParallelism(AppSettings.MIN_PARALLELISM)
        assertThat(settings.current().parallelism).isEqualTo(AppSettings.MIN_PARALLELISM)
    }

    @Test
    fun update_parallelism_max_boundary_accepted() {
        settings.updateParallelism(AppSettings.MAX_PARALLELISM)
        assertThat(settings.current().parallelism).isEqualTo(AppSettings.MAX_PARALLELISM)
    }

    // ── updateBatteryThreshold clamping ────────────────────────────────────────

    @Test
    fun update_battery_threshold_below_min_clamps_to_ten() {
        settings.updateBatteryThreshold(0)
        assertThat(settings.current().batteryThresholdPct).isEqualTo(BatteryAwareness.MIN_THRESHOLD)
    }

    @Test
    fun update_battery_threshold_above_max_clamps_to_twenty_five() {
        settings.updateBatteryThreshold(100)
        assertThat(settings.current().batteryThresholdPct).isEqualTo(BatteryAwareness.MAX_THRESHOLD)
    }

    @Test
    fun update_battery_threshold_valid_value_accepted() {
        settings.updateBatteryThreshold(20)
        assertThat(settings.current().batteryThresholdPct).isEqualTo(20)
    }

    // ── StateFlow ─────────────────────────────────────────────────────────────

    @Test
    fun flow_reflects_updated_parallelism() {
        settings.updateParallelism(5)
        assertThat(settings.flow.value.parallelism).isEqualTo(5)
    }

    @Test
    fun flow_reflects_updated_battery_threshold() {
        settings.updateBatteryThreshold(18)
        assertThat(settings.flow.value.batteryThresholdPct).isEqualTo(18)
    }

    @Test
    fun flow_snapshot_is_consistent_with_current() {
        settings.updateParallelism(2)
        settings.updateBatteryThreshold(12)
        val snap = settings.current()
        assertThat(settings.flow.value).isEqualTo(snap)
    }
}
