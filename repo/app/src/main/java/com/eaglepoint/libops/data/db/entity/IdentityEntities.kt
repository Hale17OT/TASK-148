package com.eaglepoint.libops.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * User accounts. See PRD §8 Identity and §10.1 User account lifecycle.
 */
@Entity(
    tableName = "users",
    indices = [Index(value = ["username"], unique = true)],
)
data class UserEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val username: String,
    val displayName: String,
    val passwordHash: String,
    val passwordSalt: String,
    val kdfAlgorithm: String,
    val kdfIterations: Int,
    val kdfMemoryKb: Int,
    val status: String, // pending_activation, active, locked, password_reset_required, disabled
    val failedAttempts: Int = 0,
    val lockoutUntilEpochMillis: Long? = null,
    val biometricEnabled: Boolean = false,
    val lastPasswordLoginEpochMillis: Long? = null,
    val lastPasswordChangeEpochMillis: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val version: Int = 1,
)

@Entity(tableName = "roles", indices = [Index(value = ["name"], unique = true)])
data class RoleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String,
    val createdAt: Long,
)

@Entity(tableName = "permissions", indices = [Index(value = ["name"], unique = true)])
data class PermissionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String,
)

@Entity(
    tableName = "role_permissions",
    primaryKeys = ["roleId", "permissionId"],
    indices = [Index(value = ["permissionId"])],
    foreignKeys = [
        ForeignKey(entity = RoleEntity::class, parentColumns = ["id"], childColumns = ["roleId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PermissionEntity::class, parentColumns = ["id"], childColumns = ["permissionId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class RolePermissionEntity(
    val roleId: Long,
    val permissionId: Long,
)

@Entity(
    tableName = "user_roles",
    primaryKeys = ["userId", "roleId"],
    indices = [Index(value = ["roleId"])],
    foreignKeys = [
        ForeignKey(entity = UserEntity::class, parentColumns = ["id"], childColumns = ["userId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = RoleEntity::class, parentColumns = ["id"], childColumns = ["roleId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class UserRoleEntity(
    val userId: Long,
    val roleId: Long,
    val active: Boolean = true,
    val assignedAt: Long,
    val assignedByUserId: Long?,
)

@Entity(
    tableName = "user_sessions",
    indices = [Index(value = ["userId"]), Index(value = ["state"]) ],
    foreignKeys = [
        ForeignKey(entity = UserEntity::class, parentColumns = ["id"], childColumns = ["userId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class UserSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long,
    val activeRoleId: Long?,
    val state: String, // created, authenticated, biometric_eligible, expired, revoked
    val createdAt: Long,
    val lastActiveAt: Long,
    val expiresAt: Long,
    val biometricUsed: Boolean = false,
)

/**
 * Encrypted secret storage. The [cipherText] is encrypted outside the database
 * using the [SecretCipher]. Only masked form is displayed by default.
 */
@Entity(
    tableName = "secrets",
    indices = [Index(value = ["alias"], unique = true)],
)
data class SecretEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val alias: String,
    val cipherText: String,
    val iv: String,
    val maskedPreview: String,
    val category: String, // proxy, signing_key, api_token, other
    val createdAt: Long,
    val updatedAt: Long,
    val createdByUserId: Long,
)
