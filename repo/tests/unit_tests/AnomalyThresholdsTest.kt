package com.eaglepoint.libops.tests

import com.eaglepoint.libops.domain.alerts.AnomalyThresholds
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for [AnomalyThresholds] — the anomaly detection rules that trigger
 * alerts from the observability pipeline (§15).
 */
class AnomalyThresholdsTest {

    // ── job failure rate ──────────────────────────────────────────────────────

    @Test
    fun no_alert_below_window_size() {
        // 49 total attempts (below the 50-attempt window) — never triggers
        assertThat(AnomalyThresholds.jobFailuresTriggerAlert(failures = 49, total = 49)).isFalse()
    }

    @Test
    fun no_alert_at_twenty_percent_failure_rate() {
        // Exactly 20% = 10/50 — threshold is >20%, so boundary should NOT trigger
        assertThat(AnomalyThresholds.jobFailuresTriggerAlert(failures = 10, total = 50)).isFalse()
    }

    @Test
    fun alert_above_twenty_percent_failure_rate() {
        assertThat(AnomalyThresholds.jobFailuresTriggerAlert(failures = 11, total = 50)).isTrue()
    }

    // ── slow query rate ───────────────────────────────────────────────────────

    @Test
    fun no_alert_on_zero_total_queries() {
        assertThat(AnomalyThresholds.slowQueriesTriggerAlert(slow = 0, total = 0)).isFalse()
    }

    @Test
    fun no_alert_at_five_percent_slow_rate() {
        // 5/100 = 5% — threshold is >5%, so boundary should NOT trigger
        assertThat(AnomalyThresholds.slowQueriesTriggerAlert(slow = 5, total = 100)).isFalse()
    }

    @Test
    fun alert_above_five_percent_slow_rate() {
        assertThat(AnomalyThresholds.slowQueriesTriggerAlert(slow = 6, total = 100)).isTrue()
    }
}
