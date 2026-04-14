package com.eaglepoint.libops.tests

import com.eaglepoint.libops.domain.auth.BiometricPolicy
import com.eaglepoint.libops.domain.auth.LockoutPolicy
import com.eaglepoint.libops.domain.auth.SessionTimeouts
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LockoutPolicyTest {

    private val now = 1_700_000_000_000L

    @Test
    fun tracks_failed_attempts_without_lockout_below_threshold() {
        val state = LockoutPolicy.onFailure(currentFailed = 3, nowMillis = now)
        assertThat(state.failedAttempts).isEqualTo(4)
        assertThat(state.lockoutUntil).isNull()
        assertThat(state.status).isEqualTo("active")
    }

    @Test
    fun fifth_failure_locks_account_for_15_minutes() {
        val state = LockoutPolicy.onFailure(currentFailed = 4, nowMillis = now)
        assertThat(state.failedAttempts).isEqualTo(5)
        assertThat(state.lockoutUntil).isEqualTo(now + 15 * 60_000L)
        assertThat(state.status).isEqualTo("locked")
    }

    @Test
    fun success_resets_counter() {
        val s = LockoutPolicy.onSuccess()
        assertThat(s.failedAttempts).isEqualTo(0)
        assertThat(s.lockoutUntil).isNull()
        assertThat(s.status).isEqualTo("active")
    }

    @Test
    fun lock_check_returns_minutes_remaining() {
        val lockoutUntil = now + 10 * 60_000L
        val check = LockoutPolicy.checkLocked(lockoutUntil, now)
        assertThat(check).isInstanceOf(LockoutPolicy.LockCheck.Locked::class.java)
        assertThat((check as LockoutPolicy.LockCheck.Locked).minutesRemaining).isEqualTo(10)
    }

    @Test
    fun lock_check_returns_unlocked_after_expiry() {
        assertThat(LockoutPolicy.checkLocked(now - 1, now)).isEqualTo(LockoutPolicy.LockCheck.Unlocked)
        assertThat(LockoutPolicy.checkLocked(null, now)).isEqualTo(LockoutPolicy.LockCheck.Unlocked)
    }

    @Test
    fun biometric_requires_prior_password_login() {
        val state = BiometricPolicy.State(
            userEnabled = true,
            hasEverLoggedIn = false,
            lastPasswordLoginEpochMillis = null,
            lastPasswordChangeEpochMillis = null,
            privilegedRoleChangedEpochMillis = null,
            adminForcedLogoutEpochMillis = null,
        )
        assertThat(BiometricPolicy.isEligible(state, now)).isFalse()
    }

    @Test
    fun biometric_blocked_after_password_change() {
        val login = now - 1000
        val state = BiometricPolicy.State(
            userEnabled = true,
            hasEverLoggedIn = true,
            lastPasswordLoginEpochMillis = login,
            lastPasswordChangeEpochMillis = login + 100,
            privilegedRoleChangedEpochMillis = null,
            adminForcedLogoutEpochMillis = null,
        )
        assertThat(BiometricPolicy.isEligible(state, now)).isFalse()
    }

    @Test
    fun biometric_blocked_after_30_days_inactivity() {
        val login = now - BiometricPolicy.INACTIVITY_MILLIS - 1
        val state = BiometricPolicy.State(
            userEnabled = true,
            hasEverLoggedIn = true,
            lastPasswordLoginEpochMillis = login,
            lastPasswordChangeEpochMillis = null,
            privilegedRoleChangedEpochMillis = null,
            adminForcedLogoutEpochMillis = null,
        )
        assertThat(BiometricPolicy.isEligible(state, now)).isFalse()
    }

    @Test
    fun biometric_eligible_when_all_conditions_fresh() {
        val login = now - 1000
        val state = BiometricPolicy.State(
            userEnabled = true,
            hasEverLoggedIn = true,
            lastPasswordLoginEpochMillis = login,
            lastPasswordChangeEpochMillis = login - 10_000,
            privilegedRoleChangedEpochMillis = null,
            adminForcedLogoutEpochMillis = null,
        )
        assertThat(BiometricPolicy.isEligible(state, now)).isTrue()
    }

    @Test
    fun session_timeout_differs_by_role() {
        assertThat(SessionTimeouts.idleLimitMillis("administrator")).isEqualTo(10 * 60_000L)
        assertThat(SessionTimeouts.idleLimitMillis("auditor")).isEqualTo(10 * 60_000L)
        assertThat(SessionTimeouts.idleLimitMillis("cataloger")).isEqualTo(15 * 60_000L)
        assertThat(SessionTimeouts.idleLimitMillis(null)).isEqualTo(15 * 60_000L)
    }

    @Test
    fun session_expires_when_idle_past_limit() {
        val last = now - 16 * 60_000L
        assertThat(SessionTimeouts.isExpired(last, "cataloger", now)).isTrue()
        val recent = now - 9 * 60_000L
        assertThat(SessionTimeouts.isExpired(recent, "administrator", now)).isFalse()
    }
}
