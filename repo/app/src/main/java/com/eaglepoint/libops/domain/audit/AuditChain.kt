package com.eaglepoint.libops.domain.audit

import java.security.MessageDigest

/**
 * Tamper-evident audit hash chain (§14).
 *
 * Each audit event hashes a canonical representation plus the previous event's
 * hash. The resulting chain makes retroactive edits detectable.
 */
object AuditChain {

    data class EventFields(
        val correlationId: String,
        val userId: Long?,
        val action: String,
        val targetType: String,
        val targetId: String?,
        val severity: String,
        val reason: String?,
        val payloadJson: String?,
        val createdAt: Long,
    )

    fun canonical(fields: EventFields, previousHash: String?): String = buildString {
        append(previousHash ?: "")
        append('\u0001')
        append(fields.correlationId); append('\u0001')
        append(fields.userId?.toString() ?: ""); append('\u0001')
        append(fields.action); append('\u0001')
        append(fields.targetType); append('\u0001')
        append(fields.targetId ?: ""); append('\u0001')
        append(fields.severity); append('\u0001')
        append(fields.reason ?: ""); append('\u0001')
        append(fields.payloadJson ?: ""); append('\u0001')
        append(fields.createdAt)
    }

    fun hash(fields: EventFields, previousHash: String?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(canonical(fields, previousHash).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    data class ChainResult(val ok: Boolean, val brokenAtIndex: Int?)

    /**
     * Verify a chronologically-ordered list of events. For each event we
     * require that its stored hash matches recomputed hash(previous, fields).
     */
    fun verify(events: List<StoredEvent>): ChainResult {
        var prev: String? = null
        events.forEachIndexed { index, e ->
            val expected = hash(e.fields, prev)
            if (expected != e.storedHash) return ChainResult(false, index)
            prev = e.storedHash
        }
        return ChainResult(true, null)
    }

    data class StoredEvent(val fields: EventFields, val storedHash: String)
}
