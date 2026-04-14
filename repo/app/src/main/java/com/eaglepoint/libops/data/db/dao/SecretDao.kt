package com.eaglepoint.libops.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.eaglepoint.libops.data.db.entity.SecretEntity

@Dao
interface SecretDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(secret: SecretEntity): Long

    @Update
    suspend fun update(secret: SecretEntity): Int

    @Query("SELECT * FROM secrets ORDER BY alias")
    suspend fun listAll(): List<SecretEntity>

    @Query("SELECT * FROM secrets WHERE alias = :alias LIMIT 1")
    suspend fun byAlias(alias: String): SecretEntity?

    @Query("DELETE FROM secrets WHERE id = :id")
    suspend fun delete(id: Long): Int
}
