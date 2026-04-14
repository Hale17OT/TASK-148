package com.eaglepoint.libops.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.eaglepoint.libops.data.db.entity.AuditEventEntity
import com.eaglepoint.libops.data.db.entity.ExceptionEventEntity
import com.eaglepoint.libops.data.db.entity.PerformanceSampleEntity
import com.eaglepoint.libops.data.db.entity.PolicyViolationEntity

@Dao
interface AuditDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvent(event: AuditEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertException(e: ExceptionEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSample(s: PerformanceSampleEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertViolation(v: PolicyViolationEntity): Long

    @Query("SELECT * FROM audit_events ORDER BY id DESC LIMIT 1")
    suspend fun latestEvent(): AuditEventEntity?

    @Query(
        """
        SELECT * FROM audit_events
        WHERE (:userId IS NULL OR userId = :userId)
          AND (:correlationPrefix IS NULL OR correlationId LIKE :correlationPrefix || '%')
          AND createdAt BETWEEN :fromMs AND :toMs
        ORDER BY createdAt DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun search(
        userId: Long?,
        correlationPrefix: String?,
        fromMs: Long,
        toMs: Long,
        limit: Int,
        offset: Int,
    ): List<AuditEventEntity>

    @Query("SELECT COUNT(*) FROM policy_violations WHERE userId = :userId AND createdAt >= :since")
    suspend fun countViolationsSince(userId: Long, since: Long): Int

    @Query("SELECT * FROM audit_events ORDER BY id ASC")
    suspend fun allEventsChronological(): List<AuditEventEntity>

    @Query("SELECT * FROM performance_samples WHERE kind = :kind AND createdAt >= :since ORDER BY durationMs ASC")
    suspend fun perfSamplesSince(kind: String, since: Long): List<PerformanceSampleEntity>

    @Query("SELECT COUNT(*) FROM performance_samples WHERE kind = :kind AND wasSlow = 1 AND createdAt >= :since")
    suspend fun slowSampleCountSince(kind: String, since: Long): Int

    @Query("SELECT COUNT(*) FROM performance_samples WHERE kind = :kind AND createdAt >= :since")
    suspend fun totalSampleCountSince(kind: String, since: Long): Int
}
