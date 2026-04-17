package com.eaglepoint.libops.tests

import androidx.test.core.app.ApplicationProvider
import com.eaglepoint.libops.orchestration.BatteryGuard
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [BatteryGuard.current] — the sticky-broadcast battery reader used
 * by the scheduler to enforce battery-aware execution (§9.5).
 *
 * Under Robolectric, [Context.registerReceiver] with a null receiver returns
 * null (no sticky broadcast), which exercises the default-to-full path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BatteryGuardTest {

    @Test
    fun returns_sample_when_no_sticky_broadcast_available() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val sample = BatteryGuard.current(context)

        // Robolectric default: no sticky battery broadcast → defaults to full/charging
        assertThat(sample.percent).isEqualTo(100)
        assertThat(sample.charging).isTrue()
    }

    @Test
    fun sample_percent_is_always_in_valid_range() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val sample = BatteryGuard.current(context)
        assertThat(sample.percent).isAtLeast(0)
        assertThat(sample.percent).isAtMost(100)
    }

    @Test
    fun sample_data_class_round_trips_correctly() {
        val s = BatteryGuard.Sample(percent = 42, charging = false)
        assertThat(s.percent).isEqualTo(42)
        assertThat(s.charging).isFalse()

        val copied = s.copy(percent = 15)
        assertThat(copied.percent).isEqualTo(15)
        assertThat(copied.charging).isFalse()
    }

    @Test
    fun multiple_calls_return_consistent_shape() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val s1 = BatteryGuard.current(context)
        val s2 = BatteryGuard.current(context)
        assertThat(s1.percent).isEqualTo(s2.percent)
        assertThat(s1.charging).isEqualTo(s2.charging)
    }
}
