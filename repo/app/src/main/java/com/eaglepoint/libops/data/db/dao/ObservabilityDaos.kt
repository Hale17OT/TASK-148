package com.eaglepoint.libops.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.eaglepoint.libops.data.db.entity.AlertAcknowledgementEntity
import com.eaglepoint.libops.data.db.entity.AlertEntity
import com.eaglepoint.libops.data.db.entity.AlertResolutionEntity
import com.eaglepoint.libops.data.db.entity.MetricSnapshotEntity
import com.eaglepoint.libops.data.db.entity.QualityScoreSnapshotEntity

@Dao
interface AlertDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(alert: AlertEntity): Long

    @Update
    suspend fun update(alert: AlertEntity): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAck(ack: AlertAcknowledgementEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertResolution(res: AlertResolutionEntity): Long

    @Query("SELECT * FROM alerts WHERE id = :id")
    suspend fun byId(id: Long): AlertEntity?

    @Query("SELECT * FROM alerts WHERE status = :status ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun listByStatus(status: String, limit: Int, offset: Int): List<AlertEntity>

    @Query("SELECT COUNT(*) FROM alerts WHERE status = 'overdue'")
    suspend fun overdueCount(): Int

    @Query(
        """
        SELECT COUNT(*) FROM alerts
        WHERE ownerUserId = :userId AND status = 'overdue' AND createdAt >= :since
        """
    )
    suspend fun overdueForUserSince(userId: Long, since: Long): Int
}

@Dao
interface MetricsDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(snapshot: MetricSnapshotEntity): Long

    @Query("SELECT * FROM metric_snapshots WHERE kpiKey = :key ORDER BY capturedAt DESC LIMIT :limit")
    suspend fun latest(key: String, limit: Int): List<MetricSnapshotEntity>
}

@Dao
interface QualityScoreDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(snapshot: QualityScoreSnapshotEntity): Long

    @Query("SELECT * FROM quality_score_snapshots WHERE userId = :userId ORDER BY capturedAt DESC LIMIT 1")
    suspend fun latestFor(userId: Long): QualityScoreSnapshotEntity?

    @Query("SELECT * FROM quality_score_snapshots ORDER BY capturedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<QualityScoreSnapshotEntity>
}
