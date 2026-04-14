package com.eaglepoint.libops.tests

import com.eaglepoint.libops.domain.audit.AuditChain
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AuditChainTest {

    private fun fields(seq: Long): AuditChain.EventFields = AuditChain.EventFields(
        correlationId = "cor-$seq",
        userId = seq,
        action = "action-$seq",
        targetType = "user",
        targetId = seq.toString(),
        severity = "info",
        reason = null,
        payloadJson = null,
        createdAt = 1_700_000_000_000L + seq,
    )

    @Test
    fun hash_is_deterministic_and_length_64() {
        val h1 = AuditChain.hash(fields(1), previousHash = null)
        val h2 = AuditChain.hash(fields(1), previousHash = null)
        assertThat(h1).isEqualTo(h2)
        assertThat(h1.length).isEqualTo(64)
    }

    @Test
    fun hash_changes_when_previous_hash_changes() {
        val h1 = AuditChain.hash(fields(1), previousHash = "abc")
        val h2 = AuditChain.hash(fields(1), previousHash = "xyz")
        assertThat(h1).isNotEqualTo(h2)
    }

    @Test
    fun chain_verifies_when_hashes_match() {
        val a = fields(1); val h1 = AuditChain.hash(a, null)
        val b = fields(2); val h2 = AuditChain.hash(b, h1)
        val c = fields(3); val h3 = AuditChain.hash(c, h2)

        val result = AuditChain.verify(
            listOf(
                AuditChain.StoredEvent(a, h1),
                AuditChain.StoredEvent(b, h2),
                AuditChain.StoredEvent(c, h3),
            )
        )
        assertThat(result.ok).isTrue()
        assertThat(result.brokenAtIndex).isNull()
    }

    @Test
    fun chain_detects_tampered_event() {
        val a = fields(1); val h1 = AuditChain.hash(a, null)
        val b = fields(2); val h2 = AuditChain.hash(b, h1)
        val tamperedB = b.copy(action = "tampered-action")

        val result = AuditChain.verify(
            listOf(
                AuditChain.StoredEvent(a, h1),
                AuditChain.StoredEvent(tamperedB, h2), // stored hash still old
            )
        )
        assertThat(result.ok).isFalse()
        assertThat(result.brokenAtIndex).isEqualTo(1)
    }

    @Test
    fun chain_detects_missing_intermediate() {
        val a = fields(1); val h1 = AuditChain.hash(a, null)
        val b = fields(2); val h2 = AuditChain.hash(b, h1)
        val c = fields(3); val h3 = AuditChain.hash(c, h2)

        // Drop b from chain — c's stored hash still uses h2 (based on b) but
        // verifier will recompute as hash(c, h1) and mismatch.
        val result = AuditChain.verify(
            listOf(
                AuditChain.StoredEvent(a, h1),
                AuditChain.StoredEvent(c, h3),
            )
        )
        assertThat(result.ok).isFalse()
        assertThat(result.brokenAtIndex).isEqualTo(1)
    }
}
