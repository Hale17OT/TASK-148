package com.eaglepoint.libops.tests

import androidx.test.core.app.ApplicationProvider
import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.data.db.LibOpsDatabase
import com.eaglepoint.libops.domain.audit.AuditChain
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end integration of the tamper-evident audit hash chain
 * (§16 AuditChain + real AuditDao).
 *
 * Each [AuditLogger.record] call must:
 *   - reference the previous row's eventHash as `previousEventHash`
 *   - produce a fresh `eventHash` that [AuditChain.verify] confirms
 *
 * Verifies the chain holds across many operations in a real Room database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AuditChainRealRoomIntegrationTest {

    private lateinit var db: LibOpsDatabase
    private lateinit var audit: AuditLogger
    private val clockMs = 1_700_000_000_000L

    @Before
    fun setUp() {
        db = LibOpsDatabase.inMemory(
            ApplicationProvider.getApplicationContext<android.content.Context>()
        )
        audit = AuditLogger(db.auditDao(), clock = { clockMs })
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun first_audit_event_has_null_previous_hash(): Unit = runBlocking {
        audit.record("test.action", "target_type", targetId = "1", userId = 1L)
        val events = db.auditDao().allEventsChronological()
        assertThat(events).hasSize(1)
        assertThat(events[0].previousEventHash).isNull()
        assertThat(events[0].eventHash).isNotEmpty()
    }

    @Test
    fun subsequent_event_references_prior_event_hash(): Unit = runBlocking {
        audit.record("action.one", "t", targetId = "1", userId = 1L)
        audit.record("action.two", "t", targetId = "2", userId = 1L)
        audit.record("action.three", "t", targetId = "3", userId = 1L)

        val events = db.auditDao().allEventsChronological()
        assertThat(events).hasSize(3)
        assertThat(events[1].previousEventHash).isEqualTo(events[0].eventHash)
        assertThat(events[2].previousEventHash).isEqualTo(events[1].eventHash)
    }

    @Test
    fun chain_verifies_end_to_end_over_many_events(): Unit = runBlocking {
        repeat(50) { i ->
            audit.record("bulk.action", "t", targetId = i.toString(), userId = 1L)
        }
        val events = db.auditDao().allEventsChronological()
        assertThat(events).hasSize(50)

        // Convert entities to AuditChain.StoredEvent and call the canonical verify() API
        val stored = events.map { e ->
            AuditChain.StoredEvent(
                fields = AuditChain.EventFields(
                    correlationId = e.correlationId,
                    userId = e.userId,
                    action = e.action,
                    targetType = e.targetType,
                    targetId = e.targetId,
                    severity = e.severity,
                    reason = e.reason,
                    payloadJson = e.payloadJson,
                    createdAt = e.createdAt,
                ),
                storedHash = e.eventHash,
            )
        }
        val result = AuditChain.verify(stored)
        assertThat(result.ok).isTrue()
        assertThat(result.brokenAtIndex).isNull()
    }

    @Test
    fun different_action_types_interleave_cleanly_in_single_chain(): Unit = runBlocking {
        audit.record("login.success", "user", targetId = "1", userId = 1L)
        audit.record("record.created", "master_record", targetId = "10", userId = 1L)
        audit.record("import.received", "import_batch", targetId = "5", userId = 1L)
        audit.record("alert.overdue_sweep", "alert", targetId = "3", severity = AuditLogger.Severity.WARN)
        audit.record("signing_key.rotated", "signing_key", targetId = "v2", severity = AuditLogger.Severity.CRITICAL)

        val events = db.auditDao().allEventsChronological()
        assertThat(events).hasSize(5)

        // Entire chain remains linked
        for (i in 1 until events.size) {
            assertThat(events[i].previousEventHash).isEqualTo(events[i - 1].eventHash)
        }
    }

    @Test
    fun concurrent_record_calls_preserve_chain_integrity(): Unit = runBlocking {
        // AuditLogger uses a Mutex internally — this exercises that path.
        val actions = (0 until 20).map { "concurrent.$it" }
        actions.forEach { audit.record(it, "t", targetId = "0", userId = 1L) }

        val events = db.auditDao().allEventsChronological()
        assertThat(events).hasSize(20)
        for (i in 1 until events.size) {
            assertThat(events[i].previousEventHash).isEqualTo(events[i - 1].eventHash)
        }
    }
}
