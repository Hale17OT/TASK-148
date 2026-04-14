package com.eaglepoint.libops.audit

import com.eaglepoint.libops.data.db.dao.AuditDao
import com.eaglepoint.libops.data.db.entity.AuditEventEntity
import com.eaglepoint.libops.domain.audit.AuditChain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Writes tamper-evident audit events. Uses a mutex so parallel writers can't
 * race to the hash chain's tail.
 */
class AuditLogger(
    private val dao: AuditDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val writeLock = Mutex()

    suspend fun record(
        action: String,
        targetType: String,
        targetId: String? = null,
        userId: Long? = null,
        severity: Severity = Severity.INFO,
        reason: String? = null,
        payloadJson: String? = null,
        correlationId: String = UUID.randomUUID().toString(),
    ): AuditEventEntity = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val previous = dao.latestEvent()
            val fields = AuditChain.EventFields(
                correlationId = correlationId,
                userId = userId,
                action = action,
                targetType = targetType,
                targetId = targetId,
                severity = severity.raw,
                reason = reason,
                payloadJson = payloadJson,
                createdAt = clock(),
            )
            val hash = AuditChain.hash(fields, previous?.eventHash)
            val entity = AuditEventEntity(
                correlationId = fields.correlationId,
                userId = fields.userId,
                action = fields.action,
                targetType = fields.targetType,
                targetId = fields.targetId,
                severity = fields.severity,
                reason = fields.reason,
                payloadJson = fields.payloadJson,
                previousEventHash = previous?.eventHash,
                eventHash = hash,
                createdAt = fields.createdAt,
            )
            val id = dao.insertEvent(entity)
            entity.copy(id = id)
        }
    }

    enum class Severity(val raw: String) { INFO("info"), WARN("warn"), CRITICAL("critical") }
}
