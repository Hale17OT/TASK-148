package com.eaglepoint.libops.tests

import com.eaglepoint.libops.domain.auth.Capabilities
import com.eaglepoint.libops.domain.auth.Roles
import com.eaglepoint.libops.ui.Navigation
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for [Navigation.visibleFor]: verifies that capability-based filtering
 * is correct for each built-in role and that the entry list is well-formed
 * (§11 — route-level authorization).
 *
 * Pure-JVM tests — no Android runtime required.
 */
class NavigationTest {

    @Test
    fun admin_sees_all_nine_nav_entries() {
        val adminCaps = Roles.DEFAULT_MAPPING[Roles.ADMIN]!!
        val visible = Navigation.visibleFor(adminCaps)
        assertThat(visible).hasSize(Navigation.ALL.size)
        assertThat(visible).containsExactlyElementsIn(Navigation.ALL).inOrder()
    }

    @Test
    fun empty_capability_set_returns_empty_list() {
        assertThat(Navigation.visibleFor(emptySet())).isEmpty()
    }

    @Test
    fun single_capability_shows_only_the_matching_entry() {
        val visible = Navigation.visibleFor(setOf(Capabilities.RECORDS_READ))
        assertThat(visible).hasSize(1)
        assertThat(visible[0].key).isEqualTo("records")
    }

    @Test
    fun all_navigation_keys_are_unique() {
        val keys = Navigation.ALL.map { it.key }
        assertThat(keys.toSet()).hasSize(Navigation.ALL.size)
    }

    @Test
    fun each_entry_required_capability_is_a_known_capability() {
        for (entry in Navigation.ALL) {
            assertThat(Capabilities.ALL).contains(entry.required)
        }
    }

    @Test
    fun cataloger_sees_records_and_imports_but_not_admin_or_secrets() {
        // Cataloger: RECORDS_READ, IMPORTS_RUN but no USERS_MANAGE or SECRETS_READ_MASKED
        val caps = Roles.DEFAULT_MAPPING[Roles.CATALOGER]!!
        val keys = Navigation.visibleFor(caps).map { it.key }.toSet()
        assertThat(keys).contains("records")
        assertThat(keys).contains("imports")
        assertThat(keys).doesNotContain("admin")   // USERS_MANAGE not in cataloger role
        assertThat(keys).doesNotContain("secrets") // SECRETS_READ_MASKED not in cataloger role
    }

    @Test
    fun auditor_sees_audit_logs_but_not_admin_or_imports() {
        // Auditor: AUDIT_READ and SECRETS_READ_MASKED but no USERS_MANAGE or IMPORTS_RUN
        val caps = Roles.DEFAULT_MAPPING[Roles.AUDITOR]!!
        val keys = Navigation.visibleFor(caps).map { it.key }.toSet()
        assertThat(keys).contains("audit_logs")
        assertThat(keys).doesNotContain("admin")   // USERS_MANAGE not in auditor role
        assertThat(keys).doesNotContain("imports") // IMPORTS_RUN not in auditor role
    }

    @Test
    fun collection_manager_does_not_see_audit_logs() {
        // Collection Manager has no AUDIT_READ
        val caps = Roles.DEFAULT_MAPPING[Roles.COLLECTION_MANAGER]!!
        val keys = Navigation.visibleFor(caps).map { it.key }.toSet()
        assertThat(keys).doesNotContain("audit_logs")
    }
}
