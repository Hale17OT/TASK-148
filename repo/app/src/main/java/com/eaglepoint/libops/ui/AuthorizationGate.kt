package com.eaglepoint.libops.ui

import android.content.Intent
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.eaglepoint.libops.LibOpsApp
import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.auth.SessionStore
import com.eaglepoint.libops.domain.auth.SessionTimeouts
import kotlinx.coroutines.launch

/**
 * Enforces per-screen capability gates uniformly. Every feature Activity
 * calls [requireAccess] in onCreate *before* any data loading or UI writes
 * that would leak access to unauthorized callers.
 *
 * Addresses audit finding #9 (route-level authorization inconsistency):
 * sessions alone are not authoritative; each feature declares the
 * capability its *read* surface requires.
 *
 * Also enforces session idle-timeout (§9.2): if the session has been idle
 * past the role-based limit (10 min admin/auditor, 15 min standard), the
 * session is invalidated and the user is redirected to login.
 */
object AuthorizationGate {

    /**
     * Resolves the current session and confirms it grants [required].
     *
     * - No session → redirect to login, finish(), return null.
     * - Expired session (idle timeout) → invalidate DB row, clear store,
     *   audit `session.expired`, redirect to login, finish(), return null.
     * - Session but missing capability → audit the denied access, toast,
     *   finish(), return null.
     * - Otherwise → touch session activity timestamp, return the live session.
     */
    fun requireAccess(
        activity: FragmentActivity,
        required: String,
    ): SessionStore.ActiveSession? {
        val app = activity.application as LibOpsApp
        val session = app.sessionStore.snapshot()
        if (session == null) {
            redirectToLogin(activity)
            return null
        }
        val now = System.currentTimeMillis()
        if (SessionTimeouts.isExpired(session.lastActiveMillis, session.activeRoleName, now)) {
            val sessionId = session.sessionId
            val userId = session.userId
            app.sessionStore.clear()
            activity.lifecycleScope.launch {
                app.db.sessionDao().expire(sessionId)
                app.auditLogger.record(
                    action = "session.expired",
                    targetType = "session",
                    targetId = sessionId.toString(),
                    userId = userId,
                    reason = "idle_timeout",
                    severity = AuditLogger.Severity.WARN,
                )
            }
            Toast.makeText(activity, "Session expired — please sign in again", Toast.LENGTH_SHORT).show()
            redirectToLogin(activity)
            return null
        }
        if (required !in session.capabilities) {
            val screenName = activity.javaClass.simpleName
            val userId = session.userId
            activity.lifecycleScope.launch {
                app.auditLogger.record(
                    action = "authz.denied",
                    targetType = "screen",
                    targetId = screenName,
                    userId = userId,
                    reason = "missing:$required",
                    severity = AuditLogger.Severity.WARN,
                )
            }
            Toast.makeText(activity, "Access denied: $required required", Toast.LENGTH_SHORT).show()
            activity.finish()
            return null
        }
        app.sessionStore.touch(now)
        return session
    }

    /**
     * Lightweight session re-validation for in-screen write actions.
     *
     * Each Activity's [requireAccess] runs once in `onCreate`. Subsequent
     * user-triggered actions (button clicks, FAB taps) may execute minutes
     * later — past the idle-timeout window. This method re-checks the
     * timeout and, when [requiredCapability] is provided, re-verifies the
     * capability on every mutating action so expired or de-privileged
     * sessions cannot perform writes.
     *
     * @param requiredCapability if non-null, the session must still hold
     *   this capability; a denial is audited and the action is blocked.
     * @return the live session, or null after redirecting to login / denying.
     */
    fun revalidateSession(
        activity: FragmentActivity,
        requiredCapability: String? = null,
    ): SessionStore.ActiveSession? {
        val app = activity.application as LibOpsApp
        val session = app.sessionStore.snapshot()
        if (session == null) {
            redirectToLogin(activity)
            return null
        }
        val now = System.currentTimeMillis()
        if (SessionTimeouts.isExpired(session.lastActiveMillis, session.activeRoleName, now)) {
            val sessionId = session.sessionId
            val userId = session.userId
            app.sessionStore.clear()
            activity.lifecycleScope.launch {
                app.db.sessionDao().expire(sessionId)
                app.auditLogger.record(
                    action = "session.expired",
                    targetType = "session",
                    targetId = sessionId.toString(),
                    userId = userId,
                    reason = "idle_timeout_action",
                    severity = AuditLogger.Severity.WARN,
                )
            }
            Toast.makeText(activity, "Session expired — please sign in again", Toast.LENGTH_SHORT).show()
            redirectToLogin(activity)
            return null
        }
        // Re-check capability on every mutating action (function-level authz)
        if (requiredCapability != null && requiredCapability !in session.capabilities) {
            val screenName = activity.javaClass.simpleName
            val userId = session.userId
            activity.lifecycleScope.launch {
                app.auditLogger.record(
                    action = "authz.denied",
                    targetType = "action",
                    targetId = screenName,
                    userId = userId,
                    reason = "missing:$requiredCapability",
                    severity = AuditLogger.Severity.WARN,
                )
            }
            Toast.makeText(activity, "Access denied: $requiredCapability required", Toast.LENGTH_SHORT).show()
            return null
        }
        app.sessionStore.touch(now)
        return session
    }

    private fun redirectToLogin(activity: FragmentActivity) {
        val target = Intent(activity, LoginActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(target)
        activity.finish()
    }
}
