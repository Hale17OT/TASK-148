package com.eaglepoint.libops.tests

import com.eaglepoint.libops.domain.alerts.AlertPolicy
import com.eaglepoint.libops.domain.alerts.AnomalyThresholds
import com.eaglepoint.libops.domain.mask.Masking
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AlertPolicyTest {

    private val now = 1_700_000_000_000L

    @Test
    fun overdue_after_7_days() {
        val created = now - AlertPolicy.SLA_MILLIS - 1
        assertThat(AlertPolicy.isOverdue(created, now)).isTrue()
    }

    @Test
    fun not_overdue_within_7_days() {
        val created = now - AlertPolicy.SLA_MILLIS + 1000
        assertThat(AlertPolicy.isOverdue(created, now)).isFalse()
    }

    @Test
    fun resolution_note_min_10_chars() {
        val errors = AlertPolicy.validateResolution("acknowledged", "short")
        assertThat(errors.map { it.code }).contains("too_short")
    }

    @Test
    fun resolution_must_be_acknowledged_first() {
        val errors = AlertPolicy.validateResolution("open", "A sufficiently long note here.")
        assertThat(errors.map { it.code }).contains("illegal_transition")
    }

    @Test
    fun resolution_succeeds_when_acknowledged_and_long_enough() {
        val errors = AlertPolicy.validateResolution("acknowledged", "Resolved properly with details.")
        assertThat(errors).isEmpty()
    }

    @Test
    fun anomaly_job_failure_rate_triggers_alert() {
        assertThat(AnomalyThresholds.jobFailuresTriggerAlert(failures = 11, total = 50)).isTrue()
        assertThat(AnomalyThresholds.jobFailuresTriggerAlert(failures = 9, total = 50)).isFalse()
        assertThat(AnomalyThresholds.jobFailuresTriggerAlert(failures = 100, total = 10)).isFalse()
    }

    @Test
    fun anomaly_slow_query_fraction_triggers_alert() {
        assertThat(AnomalyThresholds.slowQueriesTriggerAlert(slow = 6, total = 100)).isTrue()
        assertThat(AnomalyThresholds.slowQueriesTriggerAlert(slow = 5, total = 100)).isFalse()
    }

    @Test
    fun masking_shows_only_last_four() {
        assertThat(Masking.mask("SuperSecretToken1234")).isEqualTo("****************1234")
    }

    @Test
    fun masking_full_mask_for_short_values() {
        assertThat(Masking.mask("ab")).isEqualTo("**")
        assertThat(Masking.mask("")).isEqualTo("")
        assertThat(Masking.mask(null)).isEqualTo("")
    }
}
