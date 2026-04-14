package com.eaglepoint.libops.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Append-only audit event log with hash-chain support (§14).
 *
 * - [previousEventHash] and [eventHash] form a local chain; broken chains raise
 *   integrity alerts.
 * - [targetType] + [targetId] provide a typed reference to any entity.
 */
@Entity(
    tableName = "audit_events",
    indices = [
        Index(value = ["userId", "createdAt"]),
        Index(value = ["correlationId", "createdAt"]),
        Index(value = ["targetType", "targetId"]),
        Index(value = ["createdAt"]),
    ],
)
data class AuditEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val correlationId: String,
    val userId: Long?,
    val action: String,
    val targetType: String,
    val targetId: String?,
    val severity: String, // info, warn, critical
    val reason: String?,
    val payloadJson: String?,
    val previousEventHash: String?,
    val eventHash: String,
    val createdAt: Long,
)

@Entity(
    tableName = "exception_events",
    indices = [
        Index(value = ["correlationId", "createdAt"]),
        Index(value = ["createdAt"]),
    ],
)
data class ExceptionEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val correlationId: String,
    val message: String,
    val stackTrace: String,
    val component: String,
    val createdAt: Long,
)

@Entity(
    tableName = "performance_samples",
    indices = [
        Index(value = ["kind", "createdAt"]),
        Index(value = ["createdAt"]),
    ],
)
data class PerformanceSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String, // query, work, image_decode
    val label: String,
    val durationMs: Long,
    val wasSlow: Boolean,
    val createdAt: Long,
)

@Entity(
    tableName = "policy_violations",
    indices = [
        Index(value = ["userId", "createdAt"]),
        Index(value = ["severity", "createdAt"]),
    ],
)
data class PolicyViolationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long?,
    val policyKey: String,
    val severity: String, // low, medium, high
    val reason: String,
    val correlationId: String?,
    val createdAt: Long,
)
