package com.eaglepoint.libops.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Collection source. §9.3, §10.3.
 */
@Entity(
    tableName = "collection_sources",
    indices = [Index(value = ["name"], unique = true), Index(value = ["state"])],
)
data class CollectionSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val entryType: String, // site, ranking_list, artist, album, imported_file
    val refreshMode: String, // incremental, full
    val priority: Int, // 1..5
    val retryBackoff: String, // 1_min, 5_min, 30_min
    val enabled: Boolean,
    val state: String, // draft, active, disabled, archived
    val scheduleCron: String?,
    val batteryThresholdPercent: Int = 15,
    val maxRetries: Int = 3,
    val createdAt: Long,
    val updatedAt: Long,
    val version: Int = 1,
)

@Entity(
    tableName = "crawl_rules",
    indices = [
        Index(value = ["sourceId"]),
        Index(value = ["sourceId", "ruleKey"], unique = true),
    ],
    foreignKeys = [
        ForeignKey(
            entity = CollectionSourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class CrawlRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val ruleKey: String,
    val ruleValue: String,
    val include: Boolean,
)

@Entity(
    tableName = "jobs",
    indices = [
        Index(value = ["status", "priority", "scheduledAt"]),
        Index(value = ["sourceId", "scheduledAt"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = CollectionSourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class JobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val status: String, // scheduled, queued, running, retry_waiting, paused_low_battery, failed, terminal_failed, succeeded, cancelled, cancelled_partial
    val priority: Int,
    val retryCount: Int = 0,
    val refreshMode: String,
    val correlationId: String,
    val scheduledAt: Long,
    val startedAt: Long?,
    val finishedAt: Long?,
    val lastError: String?,
    val progressChunk: Int = 0,
    val totalChunks: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "job_attempts",
    indices = [Index(value = ["jobId", "attemptNumber"], unique = true)],
    foreignKeys = [
        ForeignKey(entity = JobEntity::class, parentColumns = ["id"], childColumns = ["jobId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class JobAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jobId: Long,
    val attemptNumber: Int,
    val outcome: String, // pending, running, succeeded, failed, paused
    val startedAt: Long,
    val finishedAt: Long?,
    val errorMessage: String?,
    val correlationId: String,
)

@Entity(
    tableName = "imported_bundles",
    indices = [Index(value = ["checksum"], unique = true)],
)
data class ImportedBundleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val manifestVersion: String,
    val checksum: String,
    val signatureValid: Boolean,
    val creatorUserId: Long?,
    val receivedAt: Long,
)

@Entity(
    tableName = "import_batches",
    indices = [Index(value = ["createdAt", "createdByUserId"]), Index(value = ["state"])],
)
data class ImportBatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bundleId: Long?,
    val filename: String,
    val format: String, // csv, json, signed_bundle
    val totalRows: Int,
    val acceptedRows: Int = 0,
    val rejectedRows: Int = 0,
    val state: String, // received, validating, staged, awaiting_merge_review, accepted_partial, accepted_all, rejected_*, completed
    val createdByUserId: Long,
    val createdAt: Long,
    val completedAt: Long?,
)

@Entity(
    tableName = "import_row_results",
    indices = [Index(value = ["batchId", "rowIndex"], unique = true), Index(value = ["batchId", "outcome"])],
    foreignKeys = [
        ForeignKey(entity = ImportBatchEntity::class, parentColumns = ["id"], childColumns = ["batchId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class ImportRowResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val batchId: Long,
    val rowIndex: Int,
    val outcome: String, // accepted, rejected_with_errors, duplicate_pending, merged
    val errorsJson: String?,
    val rawPayload: String,
    val stagedRecordId: Long?,
    val createdAt: Long,
)

@Entity(
    tableName = "duplicate_candidates",
    indices = [
        Index(value = ["status"]),
        Index(value = ["score"]),
    ],
)
data class DuplicateCandidateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val primaryRecordId: Long?,
    val candidateRecordId: Long?,
    val primaryStagingRef: String?,
    val candidateStagingRef: String?,
    val score: Double,
    val algorithm: String,
    val status: String, // detected, under_review, merged, dismissed, escalated, reversed, reopened
    val detectedAt: Long,
    val reviewedByUserId: Long?,
    val reviewedAt: Long?,
)

@Entity(
    tableName = "merge_decisions",
    indices = [Index(value = ["duplicateId"], unique = true)],
    foreignKeys = [
        ForeignKey(entity = DuplicateCandidateEntity::class, parentColumns = ["id"], childColumns = ["duplicateId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class MergeDecisionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val duplicateId: Long,
    val operatorUserId: Long,
    val decision: String, // merged, dismissed, escalated, reversed
    val rationale: String,
    val keptRecordId: Long?,
    val mergedFromRecordId: Long?,
    val provenanceJson: String,
    val decidedAt: Long,
)
