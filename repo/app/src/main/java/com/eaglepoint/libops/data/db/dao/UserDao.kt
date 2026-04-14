package com.eaglepoint.libops.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.eaglepoint.libops.data.db.entity.PermissionEntity
import com.eaglepoint.libops.data.db.entity.RoleEntity
import com.eaglepoint.libops.data.db.entity.RolePermissionEntity
import com.eaglepoint.libops.data.db.entity.UserEntity
import com.eaglepoint.libops.data.db.entity.UserRoleEntity
import com.eaglepoint.libops.data.db.entity.UserSessionEntity

@Dao
interface UserDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(user: UserEntity): Long

    @Update
    suspend fun update(user: UserEntity): Int

    @Query("SELECT * FROM users WHERE username = :username LIMIT 1")
    suspend fun findByUsername(username: String): UserEntity?

    @Query("SELECT * FROM users WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): UserEntity?

    @Query("SELECT * FROM users ORDER BY username ASC")
    suspend fun listAll(): List<UserEntity>

    @Query(
        "UPDATE users SET failedAttempts = :failed, lockoutUntilEpochMillis = :lockoutUntil, status = :status, updatedAt = :now WHERE id = :id"
    )
    suspend fun updateLockout(id: Long, failed: Int, lockoutUntil: Long?, status: String, now: Long): Int

    @Query(
        "UPDATE users SET passwordHash = :hash, passwordSalt = :salt, kdfAlgorithm = :alg, kdfIterations = :iters, kdfMemoryKb = :mem, lastPasswordChangeEpochMillis = :now, updatedAt = :now, version = version + 1 WHERE id = :id"
    )
    suspend fun updatePassword(id: Long, hash: String, salt: String, alg: String, iters: Int, mem: Int, now: Long): Int

    @Query("UPDATE users SET biometricEnabled = :enabled, updatedAt = :now WHERE id = :id")
    suspend fun setBiometric(id: Long, enabled: Boolean, now: Long): Int
}

@Dao
interface PermissionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRole(role: RoleEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPermission(permission: PermissionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun bindRolePermission(rp: RolePermissionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun assignRole(userRole: UserRoleEntity)

    @Query("SELECT * FROM roles ORDER BY name")
    suspend fun listRoles(): List<RoleEntity>

    @Query("SELECT * FROM roles WHERE name = :name LIMIT 1")
    suspend fun roleByName(name: String): RoleEntity?

    @Query("SELECT * FROM permissions ORDER BY name")
    suspend fun listPermissions(): List<PermissionEntity>

    @Query(
        """
        SELECT p.name FROM permissions p
        INNER JOIN role_permissions rp ON rp.permissionId = p.id
        INNER JOIN user_roles ur ON ur.roleId = rp.roleId
        WHERE ur.userId = :userId AND ur.active = 1
        """
    )
    suspend fun permissionsForUser(userId: Long): List<String>

    @Query(
        """
        SELECT r.* FROM roles r
        INNER JOIN user_roles ur ON ur.roleId = r.id
        WHERE ur.userId = :userId AND ur.active = 1
        """
    )
    suspend fun rolesForUser(userId: Long): List<RoleEntity>
}

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: UserSessionEntity): Long

    @Update
    suspend fun update(session: UserSessionEntity): Int

    @Query("SELECT * FROM user_sessions WHERE userId = :userId AND state IN ('created','authenticated','biometric_eligible')")
    suspend fun openSessionsForUser(userId: Long): List<UserSessionEntity>

    @Query("UPDATE user_sessions SET state = 'expired' WHERE id = :id")
    suspend fun expire(id: Long): Int

    @Query("UPDATE user_sessions SET state = 'revoked' WHERE userId = :userId AND state <> 'expired'")
    suspend fun revokeAllFor(userId: Long): Int

    @Query(
        """
        SELECT * FROM user_sessions
        WHERE state = 'authenticated'
        ORDER BY lastActiveAt DESC
        LIMIT 1
        """
    )
    suspend fun mostRecentAuthenticated(): UserSessionEntity?

    @Query("UPDATE user_sessions SET state = 'expired' WHERE state IN ('created','authenticated','biometric_eligible') AND expiresAt <= :nowMs")
    suspend fun expireAllPastDue(nowMs: Long): Int
}
