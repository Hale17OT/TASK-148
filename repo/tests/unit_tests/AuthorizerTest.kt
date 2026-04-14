package com.eaglepoint.libops.tests

import com.eaglepoint.libops.domain.auth.Authorizer
import com.eaglepoint.libops.domain.auth.Capabilities
import com.eaglepoint.libops.domain.auth.Roles
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AuthorizerTest {

    @Test
    fun administrator_has_all_capabilities() {
        val grants = Roles.DEFAULT_MAPPING[Roles.ADMIN]!!
        assertThat(grants).containsAtLeastElementsIn(Capabilities.ALL)
    }

    @Test
    fun auditor_cannot_manage_records() {
        val authz = Authorizer(Roles.DEFAULT_MAPPING[Roles.AUDITOR]!!)
        assertThat(authz.has(Capabilities.RECORDS_READ)).isTrue()
        assertThat(authz.has(Capabilities.RECORDS_MANAGE)).isFalse()
        assertThat(authz.has(Capabilities.SECRETS_READ_FULL)).isFalse()
    }

    @Test
    fun cataloger_can_manage_catalog_but_not_sources() {
        val authz = Authorizer(Roles.DEFAULT_MAPPING[Roles.CATALOGER]!!)
        assertThat(authz.has(Capabilities.RECORDS_MANAGE)).isTrue()
        assertThat(authz.has(Capabilities.TAXONOMY_MANAGE)).isTrue()
        assertThat(authz.has(Capabilities.HOLDINGS_MANAGE)).isTrue()
        assertThat(authz.has(Capabilities.BARCODES_MANAGE)).isTrue()
        assertThat(authz.has(Capabilities.SOURCES_MANAGE)).isFalse()
    }

    @Test
    fun collection_manager_cannot_manage_records_directly() {
        val authz = Authorizer(Roles.DEFAULT_MAPPING[Roles.COLLECTION_MANAGER]!!)
        assertThat(authz.has(Capabilities.SOURCES_MANAGE)).isTrue()
        assertThat(authz.has(Capabilities.JOBS_RUN)).isTrue()
        assertThat(authz.has(Capabilities.RECORDS_MANAGE)).isFalse()
    }

    @Test
    fun require_throws_on_missing_capability() {
        val authz = Authorizer(setOf(Capabilities.RECORDS_READ))
        try {
            authz.require(Capabilities.SECRETS_READ_FULL)
            throw AssertionError("Expected SecurityException")
        } catch (expected: SecurityException) {
            assertThat(expected).hasMessageThat().contains("secrets.read_full")
        }
    }

    @Test
    fun quality_scores_read_all_limited_to_admin_and_auditor() {
        assertThat(Roles.DEFAULT_MAPPING[Roles.ADMIN]!!).contains(Capabilities.QUALITY_SCORES_READ_ALL)
        assertThat(Roles.DEFAULT_MAPPING[Roles.AUDITOR]!!).contains(Capabilities.QUALITY_SCORES_READ_ALL)
        assertThat(Roles.DEFAULT_MAPPING[Roles.CATALOGER]!!).doesNotContain(Capabilities.QUALITY_SCORES_READ_ALL)
        assertThat(Roles.DEFAULT_MAPPING[Roles.COLLECTION_MANAGER]!!).doesNotContain(Capabilities.QUALITY_SCORES_READ_ALL)
    }
}
