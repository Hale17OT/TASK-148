package com.eaglepoint.libops.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "alerts",
    indices = [
        Index(value = ["status", "severity", "createdAt"]),
        Index(value = ["ownerUserId"]),
    ],
)
data class AlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String, // job_failure, slow_query, audit_integrity, rate_limit, overdue_alerts
    val severity: String, // info, warn, critical
    val title: String,
    val body: String,
    val status: String, // open, acknowledged, overdue, resolved, reopened, auto_suppressed
    val ownerUserId: Long?,
    val correlationId: String?,
    val dueAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val version: Int = 1,
)

@Entity(
    tableName = "alert_acknowledgements",
    indices = [Index(value = ["alertId"], unique = true)],
    foreignKeys = [
        ForeignKey(entity = AlertEntity::class, parentColumns = ["id"], childColumns = ["alertId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class AlertAcknowledgementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val alertId: Long,
    val userId: Long,
    val acknowledgedAt: Long,
    val note: String?,
)

@Entity(
    tableName = "alert_resolutions",
    indices = [Index(value = ["alertId"], unique = true)],
    foreignKeys = [
        ForeignKey(entity = AlertEntity::class, parentColumns = ["id"], childColumns = ["alertId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class AlertResolutionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val alertId: Long,
    val userId: Long,
    val resolvedAt: Long,
    val note: String,
)

@Entity(tableName = "metric_snapshots", indices = [Index(value = ["kpiKey", "capturedAt"]) ])
data class MetricSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kpiKey: String,
    val numericValue: Double?,
    val textValue: String?,
    val grain: String, // daily, hourly
    val capturedAt: Long,
)

@Entity(tableName = "quality_score_snapshots", indices = [Index(value = ["userId", "capturedAt"]) ])
data class QualityScoreSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long,
    val score: Int,
    val validationFailures30d: Int,
    val policyViolations30d: Int,
    val overdueAlerts30d: Int,
    val unresolvedDuplicates7d: Int,
    val capturedAt: Long,
)
