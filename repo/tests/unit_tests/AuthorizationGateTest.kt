package com.eaglepoint.libops.tests

import com.eaglepoint.libops.auth.SessionStore
import com.eaglepoint.libops.domain.auth.Authorizer
import com.eaglepoint.libops.domain.auth.Capabilities
import com.eaglepoint.libops.domain.auth.Roles
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests the authorization decision logic that [AuthorizationGate] relies on:
 * - No session → deny (gate redirects to login)
 * - Session without required capability → deny (gate audits + finishes)
 * - Session with capability → allow
 *
 * AuthorizationGate itself delegates to SessionStore.snapshot() and
 * Authorizer; these tests prove the underlying logic is correct without
 * requiring an Android Activity context.
 */
class AuthorizationGateTest {

    private fun sessionWith(role: String, caps: Set<String>) = SessionStore.ActiveSession(
        sessionId = 1L,
        userId = 1L,
        username = "testuser",
        activeRoleName = role,
        capabilities = caps,
        authenticatedAt = System.currentTimeMillis(),
        lastActiveMillis = System.currentTimeMillis(),
        biometricEnabled = false,
    )

    @Test
    fun no_session_means_deny() {
        val store = SessionStore()
        // No session set — snapshot returns null
        assertThat(store.snapshot()).isNull()
    }

    @Test
    fun session_without_capability_is_denied() {
        val session = sessionWith(Roles.AUDITOR, Roles.DEFAULT_MAPPING[Roles.AUDITOR]!!)
        val authz = Authorizer(session.capabilities)
        // Auditor cannot manage users
        assertThat(authz.has(Capabilities.USERS_MANAGE)).isFalse()
        // Auditor cannot run imports
        assertThat(authz.has(Capabilities.IMPORTS_RUN)).isFalse()
    }

    @Test
    fun session_with_capability_is_allowed() {
        val session = sessionWith(Roles.ADMIN, Roles.DEFAULT_MAPPING[Roles.ADMIN]!!)
        val authz = Authorizer(session.capabilities)
        // Admin has all capabilities
        assertThat(authz.has(Capabilities.IMPORTS_RUN)).isTrue()
        assertThat(authz.has(Capabilities.ALERTS_RESOLVE)).isTrue()
        assertThat(authz.has(Capabilities.USERS_MANAGE)).isTrue()
    }

    @Test
    fun gate_decision_matches_capability_check() {
        val session = sessionWith(Roles.COLLECTION_MANAGER, Roles.DEFAULT_MAPPING[Roles.COLLECTION_MANAGER]!!)
        val required = Capabilities.IMPORTS_RUN
        // AuthorizationGate checks: required in session.capabilities
        val gateWouldAllow = required in session.capabilities
        assertThat(gateWouldAllow).isTrue()
    }

    @Test
    fun gate_denies_capability_not_in_role() {
        val session = sessionWith(Roles.CATALOGER, Roles.DEFAULT_MAPPING[Roles.CATALOGER]!!)
        // Cataloger cannot manage sources
        val required = Capabilities.SOURCES_MANAGE
        val gateWouldAllow = required in session.capabilities
        assertThat(gateWouldAllow).isFalse()
    }

    @Test
    fun every_screen_capability_is_covered_by_admin_role() {
        val adminCaps = Roles.DEFAULT_MAPPING[Roles.ADMIN]!!
        val screenCaps = listOf(
            Capabilities.IMPORTS_RUN,
            Capabilities.ALERTS_READ,
            Capabilities.ANALYTICS_READ,
            Capabilities.AUDIT_READ,
            Capabilities.SOURCES_MANAGE,
            Capabilities.EXPORTS_RUN,
            Capabilities.RECORDS_READ,
            Capabilities.DUPLICATES_READ,
        )
        for (cap in screenCaps) {
            assertThat(adminCaps).contains(cap)
        }
    }

    @Test
    fun session_store_set_and_clear_lifecycle() {
        val store = SessionStore()
        assertThat(store.snapshot()).isNull()

        val session = sessionWith(Roles.ADMIN, setOf(Capabilities.IMPORTS_RUN))
        store.set(session)
        assertThat(store.snapshot()).isNotNull()
        assertThat(store.snapshot()!!.username).isEqualTo("testuser")

        store.clear()
        assertThat(store.snapshot()).isNull()
    }

    @Test
    fun requireAny_accepts_if_any_capability_present() {
        val caps = setOf(Capabilities.ALERTS_READ, Capabilities.ANALYTICS_READ)
        val authz = Authorizer(caps)
        assertThat(authz.requireAny(Capabilities.ALERTS_READ, Capabilities.USERS_MANAGE)).isTrue()
        assertThat(authz.requireAny(Capabilities.USERS_MANAGE, Capabilities.ROLES_MANAGE)).isFalse()
    }
}
