package com.eaglepoint.libops.tests.fakes

import com.eaglepoint.libops.data.db.dao.AuditDao
import com.eaglepoint.libops.data.db.dao.PermissionDao
import com.eaglepoint.libops.data.db.dao.SessionDao
import com.eaglepoint.libops.data.db.dao.UserDao
import com.eaglepoint.libops.data.db.entity.AuditEventEntity
import com.eaglepoint.libops.data.db.entity.ExceptionEventEntity
import com.eaglepoint.libops.data.db.entity.PerformanceSampleEntity
import com.eaglepoint.libops.data.db.entity.PermissionEntity
import com.eaglepoint.libops.data.db.entity.PolicyViolationEntity
import com.eaglepoint.libops.data.db.entity.RoleEntity
import com.eaglepoint.libops.data.db.entity.RolePermissionEntity
import com.eaglepoint.libops.data.db.entity.UserEntity
import com.eaglepoint.libops.data.db.entity.UserRoleEntity
import com.eaglepoint.libops.data.db.entity.UserSessionEntity
import java.util.concurrent.atomic.AtomicLong

/**
 * Pure-JVM in-memory fakes of the Room DAO interfaces. They implement only
 * the methods exercised by tests; anything else throws.
 *
 * These fakes let us verify AuthRepository behavior without pulling in the
 * Robolectric android-all runtime jar (which requires network access to
 * fetch at test time).
 */

class FakeUserDao : UserDao {
    private val users = linkedMapOf<Long, UserEntity>()
    private val ids = AtomicLong(0)

    override suspend fun insert(user: UserEntity): Long {
        if (users.values.any { it.username == user.username }) error("unique username")
        val id = ids.incrementAndGet()
        users[id] = user.copy(id = id)
        return id
    }

    override suspend fun update(user: UserEntity): Int {
        if (!users.containsKey(user.id)) return 0
        users[user.id] = user
        return 1
    }

    override suspend fun findByUsername(username: String): UserEntity? =
        users.values.firstOrNull { it.username == username }

    override suspend fun findById(id: Long): UserEntity? = users[id]

    override suspend fun listAll(): List<UserEntity> = users.values.sortedBy { it.username }

    override suspend fun updateLockout(id: Long, failed: Int, lockoutUntil: Long?, status: String, now: Long): Int {
        val u = users[id] ?: return 0
        users[id] = u.copy(failedAttempts = failed, lockoutUntilEpochMillis = lockoutUntil, status = status, updatedAt = now)
        return 1
    }

    override suspend fun updatePassword(id: Long, hash: String, salt: String, alg: String, iters: Int, mem: Int, now: Long): Int {
        val u = users[id] ?: return 0
        users[id] = u.copy(
            passwordHash = hash, passwordSalt = salt, kdfAlgorithm = alg, kdfIterations = iters, kdfMemoryKb = mem,
            lastPasswordChangeEpochMillis = now, updatedAt = now, version = u.version + 1,
        )
        return 1
    }

    override suspend fun setBiometric(id: Long, enabled: Boolean, now: Long): Int {
        val u = users[id] ?: return 0
        users[id] = u.copy(biometricEnabled = enabled, updatedAt = now)
        return 1
    }
}

class FakePermissionDao : PermissionDao {
    private val roles = linkedMapOf<Long, RoleEntity>()
    private val permissions = linkedMapOf<Long, PermissionEntity>()
    private val rolePermissions = mutableSetOf<RolePermissionEntity>()
    private val userRoles = mutableListOf<UserRoleEntity>()
    private val roleIds = AtomicLong(0)
    private val permissionIds = AtomicLong(0)

    override suspend fun insertRole(role: RoleEntity): Long {
        if (roles.values.any { it.name == role.name }) return -1
        val id = roleIds.incrementAndGet()
        roles[id] = role.copy(id = id)
        return id
    }

    override suspend fun insertPermission(permission: PermissionEntity): Long {
        if (permissions.values.any { it.name == permission.name }) return -1
        val id = permissionIds.incrementAndGet()
        permissions[id] = permission.copy(id = id)
        return id
    }

    override suspend fun bindRolePermission(rp: RolePermissionEntity) { rolePermissions.add(rp) }

    override suspend fun assignRole(userRole: UserRoleEntity) {
        userRoles.removeAll { it.userId == userRole.userId && it.roleId == userRole.roleId }
        userRoles.add(userRole)
    }

    override suspend fun listRoles(): List<RoleEntity> = roles.values.sortedBy { it.name }

    override suspend fun roleByName(name: String): RoleEntity? = roles.values.firstOrNull { it.name == name }

    override suspend fun listPermissions(): List<PermissionEntity> = permissions.values.sortedBy { it.name }

    override suspend fun permissionsForUser(userId: Long): List<String> {
        val roleIdsForUser = userRoles.filter { it.userId == userId && it.active }.map { it.roleId }.toSet()
        val permissionIdsForUser = rolePermissions.filter { it.roleId in roleIdsForUser }.map { it.permissionId }.toSet()
        return permissions.values.filter { it.id in permissionIdsForUser }.map { it.name }
    }

    override suspend fun rolesForUser(userId: Long): List<RoleEntity> {
        val ids = userRoles.filter { it.userId == userId && it.active }.map { it.roleId }.toSet()
        return roles.values.filter { it.id in ids }
    }
}

class FakeSessionDao : SessionDao {
    private val sessions = linkedMapOf<Long, UserSessionEntity>()
    private val ids = AtomicLong(0)

    override suspend fun insert(session: UserSessionEntity): Long {
        val id = ids.incrementAndGet()
        sessions[id] = session.copy(id = id)
        return id
    }

    override suspend fun update(session: UserSessionEntity): Int {
        if (!sessions.containsKey(session.id)) return 0
        sessions[session.id] = session
        return 1
    }

    override suspend fun openSessionsForUser(userId: Long): List<UserSessionEntity> =
        sessions.values.filter { it.userId == userId && it.state in setOf("created", "authenticated", "biometric_eligible") }

    override suspend fun expire(id: Long): Int {
        val s = sessions[id] ?: return 0
        sessions[id] = s.copy(state = "expired")
        return 1
    }

    override suspend fun revokeAllFor(userId: Long): Int {
        var count = 0
        sessions.forEach { (id, s) ->
            if (s.userId == userId && s.state != "expired") {
                sessions[id] = s.copy(state = "revoked"); count++
            }
        }
        return count
    }

    override suspend fun mostRecentAuthenticated(): UserSessionEntity? =
        sessions.values
            .filter { it.state == "authenticated" }
            .maxByOrNull { it.lastActiveAt }

    override suspend fun expireAllPastDue(nowMs: Long): Int {
        var count = 0
        sessions.forEach { (id, s) ->
            if (s.state in setOf("created", "authenticated", "biometric_eligible") && s.expiresAt <= nowMs) {
                sessions[id] = s.copy(state = "expired"); count++
            }
        }
        return count
    }
}

class FakeAuditDao : AuditDao {
    private val events = mutableListOf<AuditEventEntity>()
    private val exceptions = mutableListOf<ExceptionEventEntity>()
    private val samples = mutableListOf<PerformanceSampleEntity>()
    private val violations = mutableListOf<PolicyViolationEntity>()
    private val ids = AtomicLong(0)

    override suspend fun insertEvent(event: AuditEventEntity): Long {
        val id = ids.incrementAndGet()
        events.add(event.copy(id = id)); return id
    }

    override suspend fun insertException(e: ExceptionEventEntity): Long {
        val id = ids.incrementAndGet()
        exceptions.add(e.copy(id = id)); return id
    }

    override suspend fun insertSample(s: PerformanceSampleEntity): Long {
        val id = ids.incrementAndGet()
        samples.add(s.copy(id = id)); return id
    }

    override suspend fun insertViolation(v: PolicyViolationEntity): Long {
        val id = ids.incrementAndGet()
        violations.add(v.copy(id = id)); return id
    }

    override suspend fun latestEvent(): AuditEventEntity? = events.lastOrNull()

    override suspend fun search(userId: Long?, correlationPrefix: String?, fromMs: Long, toMs: Long, limit: Int, offset: Int): List<AuditEventEntity> =
        events.filter {
            (userId == null || it.userId == userId) &&
                (correlationPrefix == null || it.correlationId.startsWith(correlationPrefix)) &&
                it.createdAt in fromMs..toMs
        }.drop(offset).take(limit)

    override suspend fun countViolationsSince(userId: Long, since: Long): Int =
        violations.count { it.userId == userId && it.createdAt >= since }

    override suspend fun allEventsChronological(): List<AuditEventEntity> = events.sortedBy { it.id }

    override suspend fun perfSamplesSince(kind: String, since: Long): List<PerformanceSampleEntity> =
        samples.filter { it.kind == kind && it.createdAt >= since }.sortedBy { it.durationMs }

    override suspend fun slowSampleCountSince(kind: String, since: Long): Int =
        samples.count { it.kind == kind && it.wasSlow && it.createdAt >= since }

    override suspend fun totalSampleCountSince(kind: String, since: Long): Int =
        samples.count { it.kind == kind && it.createdAt >= since }
}
