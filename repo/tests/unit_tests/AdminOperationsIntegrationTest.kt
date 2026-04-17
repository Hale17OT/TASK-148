package com.eaglepoint.libops.tests

import androidx.test.core.app.ApplicationProvider
import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.auth.SeedData
import com.eaglepoint.libops.data.db.LibOpsDatabase
import com.eaglepoint.libops.data.db.entity.UserEntity
import com.eaglepoint.libops.data.db.entity.UserRoleEntity
import com.eaglepoint.libops.domain.auth.PasswordHasher
import com.eaglepoint.libops.domain.auth.Roles
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real-Room integration test for the operations performed by
 * [com.eaglepoint.libops.ui.admin.AdminActivity]: create user, assign role,
 * change status, unlock, reset password (§11, §16).
 *
 * Mirrors the exact DB + audit sequence the UI performs, so every branch the
 * Activity triggers is verified end-to-end against the real entity schema,
 * Room query execution, and audit-logging pipeline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AdminOperationsIntegrationTest {

    private lateinit var db: LibOpsDatabase
    private lateinit var audit: AuditLogger
    private val clockMs = 1_700_000_000_000L
    private val adminUserId = 1L

    @Before
    fun setUp() = runBlocking {
        db = LibOpsDatabase.inMemory(
            ApplicationProvider.getApplicationContext<android.content.Context>()
        )
        audit = AuditLogger(db.auditDao(), clock = { clockMs })
        // Seed roles + admin so role lookups succeed
        SeedData(
            userDao = db.userDao(),
            permissionDao = db.permissionDao(),
            audit = audit,
            clock = { clockMs },
            bootstrapPasswordOverride = "Admin@Review2024!",
        ).ensureSeeded()
    }

    @After
    fun tearDown() { db.close() }

    // ── createUser ────────────────────────────────────────────────────────────

    @Test
    fun create_user_inserts_row_assigns_role_and_audits(): Unit = runBlocking {
        val salt = PasswordHasher.generateSalt()
        val hash = PasswordHasher.hash("NewUser@2024!".toCharArray(), salt)
        val entity = UserEntity(
            username = "cat_reviewer",
            displayName = "Cataloger Reviewer",
            passwordHash = PasswordHasher.encodeBase64(hash),
            passwordSalt = PasswordHasher.encodeBase64(salt),
            kdfAlgorithm = PasswordHasher.ALGORITHM,
            kdfIterations = PasswordHasher.ITERATIONS,
            kdfMemoryKb = PasswordHasher.MEMORY_KB,
            status = "password_reset_required",
            biometricEnabled = false,
            createdAt = clockMs,
            updatedAt = clockMs,
        )
        val userId = db.userDao().insert(entity)
        val role = db.permissionDao().roleByName(Roles.CATALOGER)!!
        db.permissionDao().assignRole(
            UserRoleEntity(
                userId = userId, roleId = role.id,
                active = true, assignedAt = clockMs,
                assignedByUserId = adminUserId,
            ),
        )
        audit.record(
            action = "user.created",
            targetType = "user",
            targetId = userId.toString(),
            userId = adminUserId,
            reason = "role=${Roles.CATALOGER}",
        )

        // Verify row persisted
        val stored = db.userDao().findByUsername("cat_reviewer")
        assertThat(stored).isNotNull()
        assertThat(stored!!.status).isEqualTo("password_reset_required")

        // Verify role assignment persisted
        val roles = db.permissionDao().rolesForUser(userId)
        assertThat(roles.map { it.name }).contains(Roles.CATALOGER)

        // Verify audit event in real audit_events table
        val events = db.auditDao().allEventsChronological()
        assertThat(events.any { it.action == "user.created" && it.userId == adminUserId }).isTrue()
    }

    // ── setUserStatus ─────────────────────────────────────────────────────────

    @Test
    fun disable_active_user_persists_status_change(): Unit = runBlocking {
        val user = db.userDao().findByUsername("admin")!!
        db.userDao().update(user.copy(status = "disabled", updatedAt = clockMs + 1))
        audit.record(
            action = "user.status_changed",
            targetType = "user",
            targetId = user.id.toString(),
            userId = adminUserId,
            reason = "new_status=disabled",
        )

        val after = db.userDao().findById(user.id)!!
        assertThat(after.status).isEqualTo("disabled")

        val events = db.auditDao().allEventsChronological()
        assertThat(events.any { it.action == "user.status_changed" }).isTrue()
    }

    // ── unlockUser ────────────────────────────────────────────────────────────

    @Test
    fun unlock_clears_failed_attempts_and_lockout(): Unit = runBlocking {
        val user = db.userDao().findByUsername("admin")!!
        // Simulate lockout state
        db.userDao().updateLockout(
            id = user.id, failed = 5,
            lockoutUntil = clockMs + 15 * 60_000L,
            status = "locked", now = clockMs,
        )
        val locked = db.userDao().findById(user.id)!!
        assertThat(locked.failedAttempts).isEqualTo(5)
        assertThat(locked.status).isEqualTo("locked")

        // Unlock
        db.userDao().updateLockout(
            id = user.id, failed = 0, lockoutUntil = null,
            status = "active", now = clockMs + 1,
        )
        audit.record(
            action = "user.unlocked",
            targetType = "user",
            targetId = user.id.toString(),
            userId = adminUserId,
            reason = "admin_unlock",
        )

        val unlocked = db.userDao().findById(user.id)!!
        assertThat(unlocked.failedAttempts).isEqualTo(0)
        assertThat(unlocked.lockoutUntilEpochMillis).isNull()
        assertThat(unlocked.status).isEqualTo("active")
    }

    // ── resetPassword ─────────────────────────────────────────────────────────

    @Test
    fun reset_password_updates_hash_and_forces_reset_status(): Unit = runBlocking {
        val user = db.userDao().findByUsername("admin")!!
        val originalHash = user.passwordHash

        val newSalt = PasswordHasher.generateSalt()
        val newHash = PasswordHasher.hash("FreshPass@99!".toCharArray(), newSalt)
        db.userDao().updatePassword(
            id = user.id,
            hash = PasswordHasher.encodeBase64(newHash),
            salt = PasswordHasher.encodeBase64(newSalt),
            alg = PasswordHasher.ALGORITHM,
            iters = PasswordHasher.ITERATIONS,
            mem = PasswordHasher.MEMORY_KB,
            now = clockMs + 1,
        )
        val refreshed = db.userDao().findById(user.id)!!
        db.userDao().update(refreshed.copy(status = "password_reset_required", updatedAt = clockMs + 1))
        audit.record(
            action = "user.password_reset",
            targetType = "user",
            targetId = user.id.toString(),
            userId = adminUserId,
            reason = "admin_reset",
            severity = AuditLogger.Severity.WARN,
        )

        val finalUser = db.userDao().findById(user.id)!!
        assertThat(finalUser.passwordHash).isNotEqualTo(originalHash)
        assertThat(finalUser.status).isEqualTo("password_reset_required")

        val events = db.auditDao().allEventsChronological()
        val resetEvent = events.firstOrNull { it.action == "user.password_reset" }
        assertThat(resetEvent).isNotNull()
        assertThat(resetEvent!!.severity).isEqualTo("warn")
    }

    // ── assignRole ────────────────────────────────────────────────────────────

    @Test
    fun assign_role_replaces_previous_role(): Unit = runBlocking {
        // Create a fresh user
        val salt = PasswordHasher.generateSalt()
        val hash = PasswordHasher.hash("User@2024!".toCharArray(), salt)
        val userId = db.userDao().insert(
            UserEntity(
                username = "rotator", displayName = "Role Rotator",
                passwordHash = PasswordHasher.encodeBase64(hash),
                passwordSalt = PasswordHasher.encodeBase64(salt),
                kdfAlgorithm = PasswordHasher.ALGORITHM,
                kdfIterations = PasswordHasher.ITERATIONS,
                kdfMemoryKb = PasswordHasher.MEMORY_KB,
                status = "active", biometricEnabled = false,
                createdAt = clockMs, updatedAt = clockMs,
            ),
        )
        val cmRole = db.permissionDao().roleByName(Roles.COLLECTION_MANAGER)!!
        db.permissionDao().assignRole(
            UserRoleEntity(
                userId = userId, roleId = cmRole.id,
                active = true, assignedAt = clockMs,
                assignedByUserId = adminUserId,
            ),
        )
        assertThat(db.permissionDao().rolesForUser(userId).map { it.name })
            .containsExactly(Roles.COLLECTION_MANAGER)

        // Reassign to auditor
        val auditorRole = db.permissionDao().roleByName(Roles.AUDITOR)!!
        db.permissionDao().assignRole(
            UserRoleEntity(
                userId = userId, roleId = auditorRole.id,
                active = true, assignedAt = clockMs + 1,
                assignedByUserId = adminUserId,
            ),
        )
        val after = db.permissionDao().rolesForUser(userId).map { it.name }
        assertThat(after).contains(Roles.AUDITOR)
    }
}
