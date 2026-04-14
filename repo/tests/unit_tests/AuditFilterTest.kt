package com.eaglepoint.libops.tests

import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.data.db.entity.AuditEventEntity
import com.eaglepoint.libops.tests.fakes.FakeAuditDao
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

/**
 * Proves that AuditDao.search correlation filter uses prefix semantics,
 * time-range filtering works correctly, and user-id filtering is honoured.
 * Exercises the FakeAuditDao which mirrors the Room LIKE prefix query.
 */
class AuditFilterTest {

    private lateinit var auditDao: FakeAuditDao
    private lateinit var audit: AuditLogger
    private var clockMs = 1_700_000_000_000L

    @Before
    fun setUp() {
        auditDao = FakeAuditDao()
        audit = AuditLogger(auditDao) { clockMs }
    }

    @Test
    fun prefix_filter_matches_events_starting_with_prefix(): Unit = runBlocking {
        audit.record("action.a", "t", correlationId = "abc-111-xxx")
        clockMs += 1
        audit.record("action.b", "t", correlationId = "abc-222-yyy")
        clockMs += 1
        audit.record("action.c", "t", correlationId = "def-333-zzz")

        val results = auditDao.search(
            userId = null,
            correlationPrefix = "abc",
            fromMs = 0,
            toMs = Long.MAX_VALUE,
            limit = 100,
            offset = 0,
        )
        assertThat(results).hasSize(2)
        assertThat(results.map { it.action }).containsExactly("action.a", "action.b")
    }

    @Test
    fun prefix_filter_is_case_sensitive(): Unit = runBlocking {
        audit.record("action.lower", "t", correlationId = "abc-123")
        clockMs += 1
        audit.record("action.upper", "t", correlationId = "ABC-456")

        val lower = auditDao.search(null, "abc", 0, Long.MAX_VALUE, 100, 0)
        assertThat(lower).hasSize(1)
        assertThat(lower[0].action).isEqualTo("action.lower")

        val upper = auditDao.search(null, "ABC", 0, Long.MAX_VALUE, 100, 0)
        assertThat(upper).hasSize(1)
        assertThat(upper[0].action).isEqualTo("action.upper")
    }

    @Test
    fun null_prefix_returns_all_events(): Unit = runBlocking {
        audit.record("a1", "t", correlationId = "aaa")
        clockMs += 1
        audit.record("a2", "t", correlationId = "bbb")

        val all = auditDao.search(null, null, 0, Long.MAX_VALUE, 100, 0)
        assertThat(all).hasSize(2)
    }

    @Test
    fun exact_correlation_id_matches_as_prefix(): Unit = runBlocking {
        val fullId = "550e8400-e29b-41d4-a716-446655440000"
        audit.record("match", "t", correlationId = fullId)
        clockMs += 1
        audit.record("other", "t", correlationId = "aaaaaaaa-0000-0000-0000-000000000000")

        // Full ID as prefix should match exactly one
        val exact = auditDao.search(null, fullId, 0, Long.MAX_VALUE, 100, 0)
        assertThat(exact).hasSize(1)
        assertThat(exact[0].action).isEqualTo("match")

        // First 8 chars as prefix should also match
        val short = auditDao.search(null, "550e8400", 0, Long.MAX_VALUE, 100, 0)
        assertThat(short).hasSize(1)
    }

    @Test
    fun time_range_filter_excludes_outside_events(): Unit = runBlocking {
        clockMs = 1000L
        audit.record("old", "t")
        clockMs = 5000L
        audit.record("in_range", "t")
        clockMs = 9000L
        audit.record("future", "t")

        val results = auditDao.search(null, null, fromMs = 3000, toMs = 7000, limit = 100, offset = 0)
        assertThat(results).hasSize(1)
        assertThat(results[0].action).isEqualTo("in_range")
    }

    @Test
    fun user_id_filter_restricts_to_matching_user(): Unit = runBlocking {
        audit.record("u1", "t", userId = 1L)
        clockMs += 1
        audit.record("u2", "t", userId = 2L)
        clockMs += 1
        audit.record("u1b", "t", userId = 1L)

        val user1 = auditDao.search(userId = 1L, correlationPrefix = null, fromMs = 0, toMs = Long.MAX_VALUE, limit = 100, offset = 0)
        assertThat(user1).hasSize(2)
        assertThat(user1.map { it.action }).containsExactly("u1", "u1b")
    }

    @Test
    fun combined_filters_apply_all_predicates(): Unit = runBlocking {
        clockMs = 5000L
        audit.record("target", "t", userId = 1L, correlationId = "abc-target")
        clockMs = 5001L
        audit.record("wrong_user", "t", userId = 2L, correlationId = "abc-wrong")
        clockMs = 5002L
        audit.record("wrong_corr", "t", userId = 1L, correlationId = "zzz-other")
        clockMs = 1000L
        audit.record("wrong_time", "t", userId = 1L, correlationId = "abc-old")

        val results = auditDao.search(
            userId = 1L,
            correlationPrefix = "abc",
            fromMs = 4000,
            toMs = 6000,
            limit = 100,
            offset = 0,
        )
        assertThat(results).hasSize(1)
        assertThat(results[0].action).isEqualTo("target")
    }

    @Test
    fun pagination_offset_and_limit_work(): Unit = runBlocking {
        for (i in 1..10) {
            clockMs += 1
            audit.record("action_$i", "t")
        }

        val page1 = auditDao.search(null, null, 0, Long.MAX_VALUE, limit = 3, offset = 0)
        assertThat(page1).hasSize(3)

        val page2 = auditDao.search(null, null, 0, Long.MAX_VALUE, limit = 3, offset = 3)
        assertThat(page2).hasSize(3)

        // No overlap between pages
        val p1ids = page1.map { it.id }.toSet()
        val p2ids = page2.map { it.id }.toSet()
        assertThat(p1ids.intersect(p2ids)).isEmpty()
    }
}
