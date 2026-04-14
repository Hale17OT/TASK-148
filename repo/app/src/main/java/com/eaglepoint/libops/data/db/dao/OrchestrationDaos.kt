package com.eaglepoint.libops.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.eaglepoint.libops.data.db.entity.CollectionSourceEntity
import com.eaglepoint.libops.data.db.entity.CrawlRuleEntity
import com.eaglepoint.libops.data.db.entity.DuplicateCandidateEntity
import com.eaglepoint.libops.data.db.entity.ImportBatchEntity
import com.eaglepoint.libops.data.db.entity.ImportRowResultEntity
import com.eaglepoint.libops.data.db.entity.ImportedBundleEntity
import com.eaglepoint.libops.data.db.entity.JobAttemptEntity
import com.eaglepoint.libops.data.db.entity.JobEntity
import com.eaglepoint.libops.data.db.entity.MergeDecisionEntity

@Dao
interface CollectionSourceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(source: CollectionSourceEntity): Long

    @Update
    suspend fun update(source: CollectionSourceEntity): Int

    @Query("SELECT * FROM collection_sources WHERE id = :id")
    suspend fun byId(id: Long): CollectionSourceEntity?

    @Query("SELECT * FROM collection_sources WHERE state = :state ORDER BY name")
    suspend fun listByState(state: String): List<CollectionSourceEntity>

    @Query("SELECT * FROM collection_sources ORDER BY name")
    suspend fun listAll(): List<CollectionSourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: CrawlRuleEntity): Long

    @Query("DELETE FROM crawl_rules WHERE sourceId = :sourceId AND ruleKey = :ruleKey")
    suspend fun deleteRule(sourceId: Long, ruleKey: String): Int

    @Query("SELECT * FROM crawl_rules WHERE sourceId = :sourceId")
    suspend fun rulesFor(sourceId: Long): List<CrawlRuleEntity>
}

@Dao
interface JobDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(job: JobEntity): Long

    @Update
    suspend fun update(job: JobEntity): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAttempt(attempt: JobAttemptEntity): Long

    @Query("SELECT * FROM jobs WHERE id = :id")
    suspend fun byId(id: Long): JobEntity?

    @Query(
        """
        SELECT * FROM jobs
        WHERE status IN ('scheduled', 'queued', 'retry_waiting')
          AND scheduledAt <= :nowMs
        ORDER BY priority DESC, scheduledAt ASC, retryCount ASC, id ASC
        LIMIT :limit
        """
    )
    suspend fun nextRunnable(limit: Int, nowMs: Long): List<JobEntity>

    @Query("SELECT * FROM jobs WHERE status = 'paused_low_battery'")
    suspend fun pausedJobs(): List<JobEntity>

    @Query(
        """
        UPDATE jobs SET status = :status, updatedAt = :now, retryCount = :retryCount,
                        lastError = :lastError, startedAt = :startedAt, finishedAt = :finishedAt,
                        scheduledAt = :scheduledAt
        WHERE id = :id
        """
    )
    suspend fun updateStatus(
        id: Long,
        status: String,
        retryCount: Int,
        lastError: String?,
        startedAt: Long?,
        finishedAt: Long?,
        now: Long,
        scheduledAt: Long,
    ): Int

    @Query("SELECT COUNT(*) FROM jobs WHERE status = :status")
    suspend fun countByStatus(status: String): Int

    @Query("SELECT * FROM job_attempts WHERE jobId = :jobId ORDER BY attemptNumber DESC")
    suspend fun attemptsFor(jobId: Long): List<JobAttemptEntity>

    @Query(
        """
        SELECT * FROM job_attempts
        WHERE outcome IN ('succeeded', 'failed')
        ORDER BY startedAt DESC
        LIMIT :limit
        """
    )
    suspend fun recentTerminalAttempts(limit: Int): List<JobAttemptEntity>
}

@Dao
interface ImportDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBundle(bundle: ImportedBundleEntity): Long

    @Query("SELECT * FROM imported_bundles WHERE checksum = :checksum LIMIT 1")
    suspend fun bundleByChecksum(checksum: String): ImportedBundleEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBatch(batch: ImportBatchEntity): Long

    @Update
    suspend fun updateBatch(batch: ImportBatchEntity): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRow(row: ImportRowResultEntity): Long

    @Query("SELECT * FROM import_batches WHERE id = :id")
    suspend fun batchById(id: Long): ImportBatchEntity?

    @Query("SELECT * FROM import_batches ORDER BY createdAt DESC, id DESC LIMIT :limit OFFSET :offset")
    suspend fun recentBatches(limit: Int, offset: Int): List<ImportBatchEntity>

    @Query("SELECT * FROM import_row_results WHERE batchId = :batchId ORDER BY rowIndex ASC LIMIT :limit OFFSET :offset")
    suspend fun rowsFor(batchId: Long, limit: Int, offset: Int): List<ImportRowResultEntity>

    @Query("SELECT COUNT(*) FROM import_batches WHERE createdByUserId = :userId AND createdAt >= :since")
    suspend fun userImportsSince(userId: Long, since: Long): Int

    @Query("SELECT COALESCE(SUM(totalRows), 0) FROM import_batches")
    suspend fun totalRowsAllBatches(): Int

    @Query("SELECT COALESCE(SUM(acceptedRows), 0) FROM import_batches")
    suspend fun totalAcceptedAllBatches(): Int

    @Query("SELECT COALESCE(SUM(rejectedRows), 0) FROM import_batches")
    suspend fun totalRejectedAllBatches(): Int

    @Query("SELECT COUNT(*) FROM import_batches")
    suspend fun batchCount(): Int

    @Query(
        """
        SELECT COUNT(*) FROM import_row_results r
        INNER JOIN import_batches b ON r.batchId = b.id
        WHERE b.createdByUserId = :userId
          AND r.outcome = 'rejected_with_errors'
          AND r.createdAt >= :since
        """
    )
    suspend fun rejectedRowsForUserSince(userId: Long, since: Long): Int
}

@Dao
interface DuplicateDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(candidate: DuplicateCandidateEntity): Long

    @Update
    suspend fun update(candidate: DuplicateCandidateEntity): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDecision(decision: MergeDecisionEntity): Long

    @Query("SELECT * FROM duplicate_candidates WHERE id = :id")
    suspend fun byId(id: Long): DuplicateCandidateEntity?

    @Query("SELECT * FROM duplicate_candidates WHERE status = :status ORDER BY score DESC LIMIT :limit OFFSET :offset")
    suspend fun listByStatus(status: String, limit: Int, offset: Int): List<DuplicateCandidateEntity>

    @Query("SELECT COUNT(*) FROM duplicate_candidates WHERE status IN ('detected','under_review') AND detectedAt <= :olderThan")
    suspend fun unresolvedOlderThan(olderThan: Long): Int
}
