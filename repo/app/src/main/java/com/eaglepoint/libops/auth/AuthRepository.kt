package com.eaglepoint.libops.auth

import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.data.db.dao.PermissionDao
import com.eaglepoint.libops.data.db.dao.SessionDao
import com.eaglepoint.libops.data.db.dao.UserDao
import com.eaglepoint.libops.data.db.entity.UserEntity
import com.eaglepoint.libops.data.db.entity.UserSessionEntity
import com.eaglepoint.libops.domain.AppResult
import com.eaglepoint.libops.domain.FieldError
import com.eaglepoint.libops.domain.auth.LockoutPolicy
import com.eaglepoint.libops.domain.auth.PasswordHasher
import com.eaglepoint.libops.domain.auth.SessionTimeouts
import com.eaglepoint.libops.observability.ObservabilityPipeline
import com.eaglepoint.libops.observability.QueryTimer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Real authentication pipeline (§9.1–§9.2).
 *
 * - Resolves user by username
 * - Verifies PBKDF2 hash
 * - Enforces 5-strike lockout with 15-minute window
 * - Creates persisted UserSessionEntity and caches it in SessionStore
 * - Writes audit events for success/failure/lockout
 */
class AuthRepository(
    private val userDao: UserDao,
    private val permissionDao: PermissionDao,
    private val sessionDao: SessionDao,
    private val sessionStore: SessionStore,
    private val audit: AuditLogger,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val observability: ObservabilityPipeline? = null,
) {

    private val queryTimer: QueryTimer? = observability?.let { QueryTimer(it) }

    suspend fun login(rawUsername: String, password: CharArray): AppResult<SessionStore.ActiveSession> =
        withContext(Dispatchers.IO) {
            val username = rawUsername.trim().lowercase()
            if (username.isEmpty() || password.isEmpty()) {
                audit.record("login.failure", "user", reason = "blank_credentials", severity = AuditLogger.Severity.WARN)
                return@withContext AppResult.ValidationError(
                    listOf(FieldError("credentials", "required", "Username and password are required"))
                )
            }
            val user = (queryTimer?.let { it.timed("query", "userDao.findByUsername") { userDao.findByUsername(username) } } ?: userDao.findByUsername(username))
                ?: run {
                    audit.record("login.failure", "user", targetId = username, reason = "unknown_user", severity = AuditLogger.Severity.WARN)
                    // Spend comparable time to constant to avoid timing oracle
                    PasswordHasher.hash(password, ByteArray(16), PasswordHasher.ITERATIONS)
                    return@withContext AppResult.ValidationError(
                        listOf(FieldError("credentials", "invalid", "Invalid credentials"))
                    )
                }

            val now = clock()
            when (val check = LockoutPolicy.checkLocked(user.lockoutUntilEpochMillis, now)) {
                is LockoutPolicy.LockCheck.Locked -> {
                    audit.record(
                        "login.locked",
                        "user",
                        targetId = user.id.toString(),
                        userId = user.id,
                        reason = "lockout_in_effect",
                        severity = AuditLogger.Severity.WARN,
                    )
                    return@withContext AppResult.Locked(check.minutesRemaining)
                }
                LockoutPolicy.LockCheck.Unlocked -> Unit
            }

            if (user.status == "disabled") {
                audit.record("login.failure", "user", targetId = user.id.toString(), userId = user.id, reason = "account_disabled", severity = AuditLogger.Severity.WARN)
                return@withContext AppResult.Conflict("user", "account_disabled")
            }
            val needsPasswordReset = user.status == "password_reset_required"

            val salt = PasswordHasher.decodeBase64(user.passwordSalt)
            val expected = PasswordHasher.decodeBase64(user.passwordHash)
            val matched = PasswordHasher.verify(password, salt, expected, user.kdfIterations)
            if (!matched) {
                val next = LockoutPolicy.onFailure(user.failedAttempts, now)
                userDao.updateLockout(
                    id = user.id,
                    failed = next.failedAttempts,
                    lockoutUntil = next.lockoutUntil,
                    status = if (next.status == "locked") "locked" else user.status,
                    now = now,
                )
                val severity = if (next.status == "locked") AuditLogger.Severity.CRITICAL else AuditLogger.Severity.WARN
                audit.record(
                    if (next.status == "locked") "login.lockout_triggered" else "login.failure",
                    "user",
                    targetId = user.id.toString(),
                    userId = user.id,
                    reason = "password_mismatch",
                    severity = severity,
                )
                return@withContext if (next.status == "locked")
                    AppResult.Locked(LockoutPolicy.LOCKOUT_MINUTES.toInt())
                else
                    AppResult.ValidationError(listOf(FieldError("credentials", "invalid", "Invalid credentials")))
            }

            // Success — reset counters, load role context, create session.
            val reset = LockoutPolicy.onSuccess()
            userDao.updateLockout(user.id, reset.failedAttempts, reset.lockoutUntil, "active", now)

            val roles = queryTimer?.let { it.timed("query", "permissionDao.rolesForUser") { permissionDao.rolesForUser(user.id) } } ?: permissionDao.rolesForUser(user.id)
            val activeRole = roles.firstOrNull()
                ?: return@withContext AppResult.Conflict("role", "no_role_assigned")
            val capabilities = (queryTimer?.let { it.timed("query", "permissionDao.permissionsForUser") { permissionDao.permissionsForUser(user.id) } } ?: permissionDao.permissionsForUser(user.id)).toSet()

            // Revoke any prior open sessions (§9.2: one active session per user)
            sessionDao.revokeAllFor(user.id)

            val expires = now + SessionTimeouts.idleLimitMillis(activeRole.name)
            val session = UserSessionEntity(
                userId = user.id,
                activeRoleId = activeRole.id,
                state = "authenticated",
                createdAt = now,
                lastActiveAt = now,
                expiresAt = expires,
                biometricUsed = false,
            )
            val sessionId = sessionDao.insert(session)
            // Re-load to avoid clobbering the reset lockout state written above.
            val fresh = (queryTimer?.let { it.timed("query", "userDao.findById") { userDao.findById(user.id) } } ?: userDao.findById(user.id)) ?: user
            userDao.update(fresh.copy(lastPasswordLoginEpochMillis = now, updatedAt = now))

            audit.record(
                "login.success",
                "user",
                targetId = user.id.toString(),
                userId = user.id,
                reason = "password_verified",
            )

            val active = SessionStore.ActiveSession(
                sessionId = sessionId,
                userId = user.id,
                username = user.username,
                activeRoleName = activeRole.name,
                capabilities = capabilities,
                authenticatedAt = now,
                lastActiveMillis = now,
                biometricEnabled = user.biometricEnabled,
                passwordResetRequired = needsPasswordReset,
            )
            sessionStore.set(active)
            // After successful login with password_reset_required, transition to active
            // (the UI will enforce the password change before allowing further access)
            if (needsPasswordReset) {
                userDao.updateLockout(user.id, 0, null, "active", now)
            }
            AppResult.Success(active)
        }

    /**
     * Attempts to restore a prior authenticated session from the database
     * after process death. Enforces idle-timeout: if the session's
     * lastActiveAt is past the role-based idle limit, the DB row is expired,
     * an audit event is recorded, and null is returned.
     *
     * Called from [LibOpsApp.onCreate] so the session restore path is
     * subject to the same expiry checks as the gate path.
     */
    suspend fun restoreSession(): SessionStore.ActiveSession? = withContext(Dispatchers.IO) {
        // Expire any sessions whose absolute expiresAt has passed
        val now = clock()
        sessionDao.expireAllPastDue(now)

        val dbSession = (queryTimer?.let { it.timed("query", "sessionDao.mostRecentAuthenticated") { sessionDao.mostRecentAuthenticated() } } ?: sessionDao.mostRecentAuthenticated()) ?: return@withContext null
        val user = (queryTimer?.let { it.timed("query", "userDao.findById") { userDao.findById(dbSession.userId) } } ?: userDao.findById(dbSession.userId)) ?: run {
            sessionDao.expire(dbSession.id)
            return@withContext null
        }

        // Enforce idle-timeout against the session's lastActiveAt
        val roles = queryTimer?.let { it.timed("query", "permissionDao.rolesForUser") { permissionDao.rolesForUser(user.id) } } ?: permissionDao.rolesForUser(user.id)
        val activeRole = roles.firstOrNull() ?: run {
            sessionDao.expire(dbSession.id)
            return@withContext null
        }
        if (SessionTimeouts.isExpired(dbSession.lastActiveAt, activeRole.name, now)) {
            sessionDao.expire(dbSession.id)
            audit.record(
                action = "session.expired",
                targetType = "session",
                targetId = dbSession.id.toString(),
                userId = user.id,
                reason = "idle_timeout_on_restore",
                severity = AuditLogger.Severity.WARN,
            )
            return@withContext null
        }

        // Session is valid — rebuild in-memory state
        val capabilities = (queryTimer?.let { it.timed("query", "permissionDao.permissionsForUser") { permissionDao.permissionsForUser(user.id) } } ?: permissionDao.permissionsForUser(user.id)).toSet()
        val active = SessionStore.ActiveSession(
            sessionId = dbSession.id,
            userId = user.id,
            username = user.username,
            activeRoleName = activeRole.name,
            capabilities = capabilities,
            authenticatedAt = dbSession.createdAt,
            lastActiveMillis = dbSession.lastActiveAt,
            biometricEnabled = user.biometricEnabled,
            passwordResetRequired = user.status == "password_reset_required",
        )
        sessionStore.set(active)
        audit.record(
            action = "session.restored",
            targetType = "session",
            targetId = dbSession.id.toString(),
            userId = user.id,
            reason = "process_restart",
        )
        active
    }

    suspend fun logout(reason: String = "user_logout") = withContext(Dispatchers.IO) {
        val s = sessionStore.snapshot() ?: return@withContext
        sessionDao.expire(s.sessionId)
        audit.record("logout", "user", targetId = s.userId.toString(), userId = s.userId, reason = reason)
        sessionStore.clear()
    }

    suspend fun enableBiometric(): AppResult<Unit> = withContext(Dispatchers.IO) {
        val s = sessionStore.snapshot() ?: return@withContext AppResult.PermissionDenied("session")
        val user = (queryTimer?.let { it.timed("query", "userDao.findById") { userDao.findById(s.userId) } } ?: userDao.findById(s.userId)) ?: return@withContext AppResult.NotFound("user")
        if (user.lastPasswordLoginEpochMillis == null) {
            return@withContext AppResult.Conflict("biometric", "password_login_required")
        }
        userDao.setBiometric(user.id, true, clock())
        audit.record("biometric.enabled", "user", targetId = user.id.toString(), userId = user.id)
        sessionStore.set(s.copy(biometricEnabled = true))
        AppResult.Success(Unit)
    }

    suspend fun currentUser(): UserEntity? = withContext(Dispatchers.IO) {
        val s = sessionStore.snapshot() ?: return@withContext null
        queryTimer?.let { it.timed("query", "userDao.findById") { userDao.findById(s.userId) } } ?: userDao.findById(s.userId)
    }

    /**
     * Resume an authenticated session after successful biometric auth.
     *
     * Biometric unlock is only valid for a user who has:
     * - a prior successful password login on this device (§9.1)
     * - biometric enrollment enabled
     * - no session-invalidating events since that login
     *
     * The caller MUST have already completed BiometricPrompt successfully
     * and must pass the username that was presented for biometric unlock.
     */
    suspend fun resumeViaBiometric(rawUsername: String): AppResult<SessionStore.ActiveSession> =
        withContext(Dispatchers.IO) {
            val username = rawUsername.trim().lowercase()
            val user = (queryTimer?.let { it.timed("query", "userDao.findByUsername") { userDao.findByUsername(username) } } ?: userDao.findByUsername(username))
                ?: return@withContext AppResult.NotFound("user")

            val now = clock()
            when (val check = LockoutPolicy.checkLocked(user.lockoutUntilEpochMillis, now)) {
                is LockoutPolicy.LockCheck.Locked -> return@withContext AppResult.Locked(check.minutesRemaining)
                LockoutPolicy.LockCheck.Unlocked -> Unit
            }
            if (user.status != "active") {
                return@withContext AppResult.Conflict("user", "status_${user.status}")
            }

            val state = com.eaglepoint.libops.domain.auth.BiometricPolicy.State(
                userEnabled = user.biometricEnabled,
                hasEverLoggedIn = user.lastPasswordLoginEpochMillis != null,
                lastPasswordLoginEpochMillis = user.lastPasswordLoginEpochMillis,
                lastPasswordChangeEpochMillis = user.lastPasswordChangeEpochMillis,
                privilegedRoleChangedEpochMillis = null,
                adminForcedLogoutEpochMillis = null,
            )
            if (!com.eaglepoint.libops.domain.auth.BiometricPolicy.isEligible(state, now)) {
                audit.record(
                    "biometric.denied",
                    "user",
                    targetId = user.id.toString(),
                    userId = user.id,
                    reason = "not_eligible",
                    severity = AuditLogger.Severity.WARN,
                )
                return@withContext AppResult.Conflict("biometric", "not_eligible")
            }

            val roles = queryTimer?.let { it.timed("query", "permissionDao.rolesForUser") { permissionDao.rolesForUser(user.id) } } ?: permissionDao.rolesForUser(user.id)
            val activeRole = roles.firstOrNull()
                ?: return@withContext AppResult.Conflict("role", "no_role_assigned")
            val capabilities = (queryTimer?.let { it.timed("query", "permissionDao.permissionsForUser") { permissionDao.permissionsForUser(user.id) } } ?: permissionDao.permissionsForUser(user.id)).toSet()
            sessionDao.revokeAllFor(user.id)

            val expires = now + SessionTimeouts.idleLimitMillis(activeRole.name)
            val session = UserSessionEntity(
                userId = user.id,
                activeRoleId = activeRole.id,
                state = "authenticated",
                createdAt = now,
                lastActiveAt = now,
                expiresAt = expires,
                biometricUsed = true,
            )
            val sessionId = sessionDao.insert(session)
            audit.record(
                "login.success_biometric",
                "user",
                targetId = user.id.toString(),
                userId = user.id,
                reason = "biometric_verified",
            )
            val active = SessionStore.ActiveSession(
                sessionId = sessionId,
                userId = user.id,
                username = user.username,
                activeRoleName = activeRole.name,
                capabilities = capabilities,
                authenticatedAt = now,
                lastActiveMillis = now,
                biometricEnabled = true,
            )
            sessionStore.set(active)
            AppResult.Success(active)
        }

    /**
     * Step-up re-authentication — confirms the currently logged-in user's
     * password without replacing the session. Used for privileged actions
     * like revealing full secrets or resolving overdue alerts (§9.2).
     */
    suspend fun verifyPasswordForStepUp(password: CharArray): Boolean = withContext(Dispatchers.IO) {
        val s = sessionStore.snapshot() ?: return@withContext false
        val user = (queryTimer?.let { it.timed("query", "userDao.findById") { userDao.findById(s.userId) } } ?: userDao.findById(s.userId)) ?: return@withContext false
        val salt = PasswordHasher.decodeBase64(user.passwordSalt)
        val expected = PasswordHasher.decodeBase64(user.passwordHash)
        val ok = PasswordHasher.verify(password, salt, expected, user.kdfIterations)
        password.fill('\u0000')
        audit.record(
            action = if (ok) "stepup.success" else "stepup.failure",
            targetType = "user",
            targetId = user.id.toString(),
            userId = user.id,
            severity = if (ok) AuditLogger.Severity.INFO else AuditLogger.Severity.WARN,
        )
        ok
    }
}
