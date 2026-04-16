package com.eaglepoint.libops.tests

import com.eaglepoint.libops.domain.auth.BiometricPolicy
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for [BiometricPolicy.isEligible] covering all disqualification
 * conditions: user disabled, never logged in, inactivity, password change,
 * role change, and admin forced logout (§9.2).
 */
class BiometricPolicyTest {

    private val now = 1_700_000_000_000L
    private val recentLogin = now - 1L * 24 * 60 * 60 * 1000L // 1 day ago

    private fun base() = BiometricPolicy.State(
        userEnabled = true,
        hasEverLoggedIn = true,
        lastPasswordLoginEpochMillis = recentLogin,
        lastPasswordChangeEpochMillis = null,
        privilegedRoleChangedEpochMillis = null,
        adminForcedLogoutEpochMillis = null,
    )

    @Test
    fun eligible_with_recent_login_and_no_disqualifiers() {
        assertThat(BiometricPolicy.isEligible(base(), now)).isTrue()
    }

    @Test
    fun ineligible_when_user_disabled() {
        assertThat(BiometricPolicy.isEligible(base().copy(userEnabled = false), now)).isFalse()
    }

    @Test
    fun ineligible_when_never_logged_in() {
        assertThat(BiometricPolicy.isEligible(base().copy(hasEverLoggedIn = false), now)).isFalse()
    }

    @Test
    fun ineligible_when_no_password_login_timestamp() {
        assertThat(BiometricPolicy.isEligible(base().copy(lastPasswordLoginEpochMillis = null), now)).isFalse()
    }

    @Test
    fun ineligible_after_thirty_day_inactivity() {
        val stale = now - 31L * 24 * 60 * 60 * 1000L
        assertThat(BiometricPolicy.isEligible(base().copy(lastPasswordLoginEpochMillis = stale), now)).isFalse()
    }

    @Test
    fun eligible_just_before_thirty_day_cutoff() {
        val justUnder = now - 29L * 24 * 60 * 60 * 1000L
        assertThat(BiometricPolicy.isEligible(base().copy(lastPasswordLoginEpochMillis = justUnder), now)).isTrue()
    }

    @Test
    fun ineligible_after_password_change() {
        val afterLogin = recentLogin + 1000L
        assertThat(BiometricPolicy.isEligible(base().copy(lastPasswordChangeEpochMillis = afterLogin), now)).isFalse()
    }

    @Test
    fun ineligible_after_role_change() {
        val afterLogin = recentLogin + 1000L
        assertThat(BiometricPolicy.isEligible(base().copy(privilegedRoleChangedEpochMillis = afterLogin), now)).isFalse()
    }

    @Test
    fun ineligible_after_admin_forced_logout() {
        val afterLogin = recentLogin + 1000L
        assertThat(BiometricPolicy.isEligible(base().copy(adminForcedLogoutEpochMillis = afterLogin), now)).isFalse()
    }
}
