package com.eaglepoint.libops.tests

import com.eaglepoint.libops.domain.orchestration.BatteryAwareness
import com.eaglepoint.libops.domain.orchestration.ImportRateLimiter
import com.eaglepoint.libops.domain.orchestration.JobOrdering
import com.eaglepoint.libops.domain.orchestration.RetryPolicy
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class JobOrderingTest {

    @Test
    fun higher_priority_wins() {
        val sorted = JobOrdering.sorted(
            listOf(
                JobOrdering.Candidate(id = 1, priority = 1, scheduledAt = 100, retryCount = 0),
                JobOrdering.Candidate(id = 2, priority = 5, scheduledAt = 100, retryCount = 0),
            )
        )
        assertThat(sorted.first().id).isEqualTo(2)
    }

    @Test
    fun earlier_scheduled_time_wins_when_priority_equal() {
        val sorted = JobOrdering.sorted(
            listOf(
                JobOrdering.Candidate(id = 1, priority = 3, scheduledAt = 200, retryCount = 0),
                JobOrdering.Candidate(id = 2, priority = 3, scheduledAt = 100, retryCount = 0),
            )
        )
        assertThat(sorted.first().id).isEqualTo(2)
    }

    @Test
    fun lower_retry_count_wins() {
        val sorted = JobOrdering.sorted(
            listOf(
                JobOrdering.Candidate(id = 1, priority = 3, scheduledAt = 100, retryCount = 2),
                JobOrdering.Candidate(id = 2, priority = 3, scheduledAt = 100, retryCount = 1),
            )
        )
        assertThat(sorted.first().id).isEqualTo(2)
    }

    @Test
    fun lower_id_is_final_tiebreaker() {
        val sorted = JobOrdering.sorted(
            listOf(
                JobOrdering.Candidate(id = 17, priority = 3, scheduledAt = 100, retryCount = 1),
                JobOrdering.Candidate(id = 5, priority = 3, scheduledAt = 100, retryCount = 1),
            )
        )
        assertThat(sorted.first().id).isEqualTo(5)
    }

    @Test
    fun battery_aware_pause_when_below_threshold_and_not_charging() {
        assertThat(BatteryAwareness.shouldPause(batteryPct = 14, charging = false)).isTrue()
        assertThat(BatteryAwareness.shouldPause(batteryPct = 14, charging = true)).isFalse()
        assertThat(BatteryAwareness.shouldPause(batteryPct = 15, charging = false)).isFalse()
    }

    @Test
    fun battery_threshold_bounded_between_10_and_25() {
        // Below bound: 5 -> clamped to 10 => 9 pauses
        assertThat(BatteryAwareness.shouldPause(batteryPct = 9, charging = false, threshold = 5)).isTrue()
        // Above bound: 50 -> clamped to 25 => 20 pauses
        assertThat(BatteryAwareness.shouldPause(batteryPct = 20, charging = false, threshold = 50)).isTrue()
    }

    @Test
    fun rate_limit_blocks_after_30_in_window() {
        assertThat(ImportRateLimiter.allowed(recentCount = 29)).isTrue()
        assertThat(ImportRateLimiter.allowed(recentCount = 30)).isFalse()
        assertThat(ImportRateLimiter.allowed(recentCount = 5, limit = 5)).isFalse()
    }

    @Test
    fun rate_limit_cannot_be_raised_above_30() {
        // Any limit passed above 30 is clamped to 30
        assertThat(ImportRateLimiter.allowed(recentCount = 30, limit = 100)).isFalse()
        assertThat(ImportRateLimiter.allowed(recentCount = 29, limit = 100)).isTrue()
    }

    @Test
    fun retry_policy_max_3() {
        assertThat(RetryPolicy.MAX_RETRIES).isEqualTo(3)
    }

    @Test
    fun retry_policy_backoff_values() {
        assertThat(RetryPolicy.nextDelay("1_min")).isEqualTo(60_000L)
        assertThat(RetryPolicy.nextDelay("5_min")).isEqualTo(300_000L)
        assertThat(RetryPolicy.nextDelay("30_min")).isEqualTo(1_800_000L)
    }

    @Test
    fun retry_policy_treats_parser_corruption_and_signature_as_terminal() {
        assertThat(RetryPolicy.isRetryable(RetryPolicy.RetryKind.PARSER_CORRUPTION)).isFalse()
        assertThat(RetryPolicy.isRetryable(RetryPolicy.RetryKind.INVALID_SIGNATURE)).isFalse()
        assertThat(RetryPolicy.isRetryable(RetryPolicy.RetryKind.TRANSIENT_IO)).isTrue()
    }
}
