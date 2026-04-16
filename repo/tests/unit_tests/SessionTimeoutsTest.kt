package com.eaglepoint.libops.tests

import com.eaglepoint.libops.domain.auth.SessionTimeouts
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for [SessionTimeouts] verifying idle-timeout calculation for
 * privileged (admin/auditor) and standard roles (§9.2).
 */
class SessionTimeoutsTest {

    @Test
    fun standard_role_gets_fifteen_minute_limit() {
        val limit = SessionTimeouts.idleLimitMillis("collection_manager")
        assertThat(limit).isEqualTo(15L * 60_000L)
    }

    @Test
    fun administrator_gets_ten_minute_limit() {
        val limit = SessionTimeouts.idleLimitMillis("administrator")
        assertThat(limit).isEqualTo(10L * 60_000L)
    }

    @Test
    fun auditor_gets_ten_minute_limit() {
        val limit = SessionTimeouts.idleLimitMillis("auditor")
        assertThat(limit).isEqualTo(10L * 60_000L)
    }

    @Test
    fun null_role_defaults_to_standard_limit() {
        val limit = SessionTimeouts.idleLimitMillis(null)
        assertThat(limit).isEqualTo(15L * 60_000L)
    }

    @Test
    fun session_within_limit_is_not_expired() {
        val now = 1_700_000_000_000L
        val fiveMinutesAgo = now - 5L * 60_000L
        assertThat(SessionTimeouts.isExpired(fiveMinutesAgo, "collection_manager", now)).isFalse()
    }

    @Test
    fun session_past_standard_limit_is_expired() {
        val now = 1_700_000_000_000L
        val twentyMinutesAgo = now - 20L * 60_000L
        assertThat(SessionTimeouts.isExpired(twentyMinutesAgo, "collection_manager", now)).isTrue()
    }

    @Test
    fun admin_session_past_ten_minutes_is_expired() {
        val now = 1_700_000_000_000L
        val elevenMinutesAgo = now - 11L * 60_000L
        assertThat(SessionTimeouts.isExpired(elevenMinutesAgo, "administrator", now)).isTrue()
    }

    @Test
    fun admin_session_within_ten_minutes_is_not_expired() {
        val now = 1_700_000_000_000L
        val nineMinutesAgo = now - 9L * 60_000L
        assertThat(SessionTimeouts.isExpired(nineMinutesAgo, "administrator", now)).isFalse()
    }
}
