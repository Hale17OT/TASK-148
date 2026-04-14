package com.eaglepoint.libops.domain.auth

/**
 * Lockout policy per PRD §9.1.
 *
 * - Maximum failed attempts before lockout: 5
 * - Lockout duration: 15 minutes
 * - Failed attempt counter resets on successful password login
 */
object LockoutPolicy {
    const val MAX_FAILED_ATTEMPTS = 5
    const val LOCKOUT_MINUTES = 15L
    const val LOCKOUT_MILLIS = LOCKOUT_MINUTES * 60_000L

    /**
     * Compute updated lockout state given a failed attempt.
     *
     * @param currentFailed current number of previously failed attempts
     * @param nowMillis current time
     */
    fun onFailure(currentFailed: Int, nowMillis: Long): LockoutState {
        val failed = currentFailed + 1
        val lockoutUntil = if (failed >= MAX_FAILED_ATTEMPTS) nowMillis + LOCKOUT_MILLIS else null
        val status = if (lockoutUntil != null) "locked" else "active"
        return LockoutState(failed, lockoutUntil, status)
    }

    /** Success resets failure count and clears lockout. */
    fun onSuccess(): LockoutState = LockoutState(0, null, "active")

    /**
     * Check whether the account is currently locked. Also returns
     * minutes remaining for UX display.
     */
    fun checkLocked(lockoutUntil: Long?, nowMillis: Long): LockCheck {
        if (lockoutUntil == null || lockoutUntil <= nowMillis) return LockCheck.Unlocked
        val remainingMs = lockoutUntil - nowMillis
        val minutes = ((remainingMs + 59_999) / 60_000).toInt()
        return LockCheck.Locked(minutes)
    }

    data class LockoutState(val failedAttempts: Int, val lockoutUntil: Long?, val status: String)

    sealed interface LockCheck {
        data object Unlocked : LockCheck
        data class Locked(val minutesRemaining: Int) : LockCheck
    }
}

/**
 * Biometric unlock eligibility. See PRD §9.1.
 *
 * Blocked after:
 * - password change
 * - role change affecting privileged permissions
 * - admin-forced logout
 * - 30 days of inactivity
 */
object BiometricPolicy {
    const val INACTIVITY_DAYS = 30L
    const val INACTIVITY_MILLIS = INACTIVITY_DAYS * 24 * 60 * 60 * 1000L

    data class State(
        val userEnabled: Boolean,
        val hasEverLoggedIn: Boolean,
        val lastPasswordLoginEpochMillis: Long?,
        val lastPasswordChangeEpochMillis: Long?,
        val privilegedRoleChangedEpochMillis: Long?,
        val adminForcedLogoutEpochMillis: Long?,
    )

    fun isEligible(state: State, nowMillis: Long): Boolean {
        if (!state.userEnabled || !state.hasEverLoggedIn) return false
        val last = state.lastPasswordLoginEpochMillis ?: return false

        // Password change invalidates until fresh login
        state.lastPasswordChangeEpochMillis?.let { if (it > last) return false }
        state.privilegedRoleChangedEpochMillis?.let { if (it > last) return false }
        state.adminForcedLogoutEpochMillis?.let { if (it > last) return false }
        if (nowMillis - last > INACTIVITY_MILLIS) return false
        return true
    }
}

/**
 * Session timeouts. See PRD §9.2.
 *
 * - Standard users: 15 min idle
 * - Administrators/Auditors: 10 min idle
 */
object SessionTimeouts {
    const val STANDARD_MINUTES = 15L
    const val PRIVILEGED_MINUTES = 10L

    fun idleLimitMillis(roleName: String?): Long {
        val minutes = when (roleName) {
            "administrator", "auditor" -> PRIVILEGED_MINUTES
            else -> STANDARD_MINUTES
        }
        return minutes * 60_000L
    }

    fun isExpired(lastActiveMillis: Long, roleName: String?, nowMillis: Long): Boolean =
        nowMillis - lastActiveMillis > idleLimitMillis(roleName)
}
