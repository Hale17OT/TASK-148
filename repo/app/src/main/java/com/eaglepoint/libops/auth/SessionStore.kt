package com.eaglepoint.libops.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory session holder. Persisted session state lives in the
 * `user_sessions` table; this flow is the process-local cache the UI
 * observes.
 *
 * Deliberately not singleton-by-kotlin-object: the app provides the instance
 * through [LibOpsApp] so tests can substitute a clean session.
 */
class SessionStore {
    private val _current = MutableStateFlow<ActiveSession?>(null)
    val current: StateFlow<ActiveSession?> = _current.asStateFlow()

    fun set(session: ActiveSession) { _current.value = session }
    fun clear() { _current.value = null }
    fun touch(now: Long) {
        val cur = _current.value ?: return
        _current.value = cur.copy(lastActiveMillis = now)
    }

    /** Current session snapshot. */
    fun snapshot(): ActiveSession? = _current.value

    data class ActiveSession(
        val sessionId: Long,
        val userId: Long,
        val username: String,
        val activeRoleName: String,
        val capabilities: Set<String>,
        val authenticatedAt: Long,
        val lastActiveMillis: Long,
        val biometricEnabled: Boolean,
        val passwordResetRequired: Boolean = false,
    )
}
