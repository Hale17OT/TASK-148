package com.eaglepoint.libops.auth

import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.data.db.dao.PermissionDao
import com.eaglepoint.libops.data.db.dao.UserDao
import com.eaglepoint.libops.data.db.entity.PermissionEntity
import com.eaglepoint.libops.data.db.entity.RoleEntity
import com.eaglepoint.libops.data.db.entity.RolePermissionEntity
import com.eaglepoint.libops.data.db.entity.UserEntity
import com.eaglepoint.libops.data.db.entity.UserRoleEntity
import com.eaglepoint.libops.domain.auth.Capabilities
import com.eaglepoint.libops.domain.auth.PasswordHasher
import com.eaglepoint.libops.domain.auth.Roles
import java.security.SecureRandom

/**
 * First-run seeding: creates roles, permissions, role-permission bindings,
 * and a default administrator account.
 *
 * The bootstrap password is generated uniquely per install using
 * [SecureRandom] and recorded in the audit log (not in the UI).
 * The account is created with status `password_reset_required` so
 * the admin must change it on first login.
 */
class SeedData(
    private val userDao: UserDao,
    private val permissionDao: PermissionDao,
    private val audit: AuditLogger,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val bootstrapPasswordOverride: String? = null,
) {

    /**
     * After [ensureSeeded], this holds the generated bootstrap password
     * for one-time secure display. Cleared after first read.
     */
    var lastBootstrapPassword: String? = null
        private set

    fun consumeBootstrapPassword(): String? {
        val pw = lastBootstrapPassword
        lastBootstrapPassword = null
        return pw
    }

    suspend fun ensureSeeded() {
        if (userDao.findByUsername("admin") != null) return

        val now = clock()

        // 1. Permissions
        val permissionIdByName = mutableMapOf<String, Long>()
        for (name in Capabilities.ALL) {
            val entity = PermissionEntity(name = name, description = name)
            val id = permissionDao.insertPermission(entity)
            permissionIdByName[name] = if (id == -1L) {
                // already present on re-run; read back
                permissionDao.listPermissions().first { it.name == name }.id
            } else id
        }

        // 2. Roles
        val roleIdByName = mutableMapOf<String, Long>()
        for (roleName in listOf(Roles.ADMIN, Roles.COLLECTION_MANAGER, Roles.CATALOGER, Roles.AUDITOR)) {
            val role = RoleEntity(name = roleName, description = roleName, createdAt = now)
            val id = permissionDao.insertRole(role)
            roleIdByName[roleName] = if (id == -1L) permissionDao.roleByName(roleName)!!.id else id
        }

        // 3. Role-permission bindings
        for ((roleName, caps) in Roles.DEFAULT_MAPPING) {
            val roleId = roleIdByName.getValue(roleName)
            for (cap in caps) {
                val permId = permissionIdByName.getValue(cap)
                permissionDao.bindRolePermission(RolePermissionEntity(roleId = roleId, permissionId = permId))
            }
        }

        // 4. Default admin user with per-install generated bootstrap secret
        val bootstrapPassword = bootstrapPasswordOverride ?: generateBootstrapPassword()
        val salt = PasswordHasher.generateSalt()
        val hash = PasswordHasher.hash(bootstrapPassword.toCharArray(), salt)
        val admin = UserEntity(
            username = "admin",
            displayName = "Administrator",
            passwordHash = PasswordHasher.encodeBase64(hash),
            passwordSalt = PasswordHasher.encodeBase64(salt),
            kdfAlgorithm = PasswordHasher.ALGORITHM,
            kdfIterations = PasswordHasher.ITERATIONS,
            kdfMemoryKb = PasswordHasher.MEMORY_KB,
            status = "password_reset_required",
            biometricEnabled = false,
            createdAt = now,
            updatedAt = now,
        )
        val adminId = userDao.insert(admin)
        permissionDao.assignRole(
            UserRoleEntity(
                userId = adminId,
                roleId = roleIdByName.getValue(Roles.ADMIN),
                active = true,
                assignedAt = now,
                assignedByUserId = null,
            )
        )

        // Store only a one-way confirmation marker — never log the password itself
        val confirmationMarker = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bootstrapPassword.toByteArray(Charsets.UTF_8))
            .take(8).joinToString("") { "%02x".format(it) }

        audit.record(
            "seed.initialized",
            "system",
            severity = AuditLogger.Severity.CRITICAL,
            reason = "first_run_seed",
            payloadJson = "{\"admin_created\":true,\"roles\":4,\"credential_marker\":\"$confirmationMarker\"}",
        )

        // The bootstrap password is returned to the caller (LibOpsApp) for
        // secure one-time display. It is NOT stored in the audit log.
        lastBootstrapPassword = bootstrapPassword
    }

    companion object {
        private const val BOOTSTRAP_PASSWORD_LENGTH = 20
        private val BOOTSTRAP_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%"

        /**
         * Generates a cryptographically random password that satisfies
         * [com.eaglepoint.libops.domain.auth.PasswordPolicy] requirements.
         */
        fun generateBootstrapPassword(): String {
            val rng = SecureRandom()
            // Guarantee complexity: start with one of each class, fill remainder randomly
            val mandatory = charArrayOf(
                ('A'..'Z').random(rng.asKotlinRandom()),
                ('a'..'z').random(rng.asKotlinRandom()),
                ('0'..'9').random(rng.asKotlinRandom()),
                "!@#$%".random(rng.asKotlinRandom()),
            )
            val remaining = CharArray(BOOTSTRAP_PASSWORD_LENGTH - mandatory.size) {
                BOOTSTRAP_CHARS[rng.nextInt(BOOTSTRAP_CHARS.length)]
            }
            val all = (mandatory.toList() + remaining.toList()).toMutableList()
            all.shuffle(rng.asKotlinRandom())
            return String(all.toCharArray())
        }

        private fun SecureRandom.asKotlinRandom(): kotlin.random.Random =
            kotlin.random.Random(this.nextLong())
    }
}
