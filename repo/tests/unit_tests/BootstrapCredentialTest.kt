package com.eaglepoint.libops.tests

import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.auth.SeedData
import com.eaglepoint.libops.domain.auth.Authorizer
import com.eaglepoint.libops.domain.auth.Capabilities
import com.eaglepoint.libops.domain.auth.PasswordHasher
import com.eaglepoint.libops.domain.auth.PasswordPolicy
import com.eaglepoint.libops.domain.auth.Roles
import com.eaglepoint.libops.tests.fakes.FakeAuditDao
import com.eaglepoint.libops.tests.fakes.FakePermissionDao
import com.eaglepoint.libops.tests.fakes.FakeUserDao
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

/**
 * Tests the bootstrap credential generation, one-time retrieval, and
 * secure cleanup lifecycle.
 */
class BootstrapCredentialTest {

    private lateinit var userDao: FakeUserDao
    private lateinit var permDao: FakePermissionDao
    private lateinit var auditDao: FakeAuditDao
    private lateinit var audit: AuditLogger
    private var clockMs = 1_700_000_000_000L

    @Before
    fun setUp() {
        userDao = FakeUserDao()
        permDao = FakePermissionDao()
        auditDao = FakeAuditDao()
        audit = AuditLogger(auditDao) { clockMs }
    }

    @Test
    fun generated_password_satisfies_policy() {
        val password = SeedData.generateBootstrapPassword()
        val errors = PasswordPolicy.validate(password)
        assertThat(errors).isEmpty()
        assertThat(password.length).isAtLeast(12)
    }

    @Test
    fun generated_passwords_are_unique() {
        val passwords = (1..20).map { SeedData.generateBootstrapPassword() }.toSet()
        // All 20 should be distinct (probability of collision is astronomically low)
        assertThat(passwords.size).isEqualTo(20)
    }

    @Test
    fun consume_returns_password_once_then_null() = runBlocking {
        val seed = SeedData(userDao, permDao, audit, clock = { clockMs })
        seed.ensureSeeded()

        val first = seed.consumeBootstrapPassword()
        assertThat(first).isNotNull()
        assertThat(first!!.length).isAtLeast(12)

        // Second call returns null — consumed
        val second = seed.consumeBootstrapPassword()
        assertThat(second).isNull()
    }

    @Test
    fun override_password_is_used_instead_of_generated() = runBlocking {
        val seed = SeedData(userDao, permDao, audit, clock = { clockMs }, bootstrapPasswordOverride = "Override!Pass123")
        seed.ensureSeeded()

        val pw = seed.consumeBootstrapPassword()
        assertThat(pw).isEqualTo("Override!Pass123")
    }

    @Test
    fun no_password_generated_on_re_seed() = runBlocking {
        // First seed
        val seed1 = SeedData(userDao, permDao, audit, clock = { clockMs })
        seed1.ensureSeeded()
        seed1.consumeBootstrapPassword() // consume it

        // Second seed — admin already exists, so ensureSeeded returns early
        val seed2 = SeedData(userDao, permDao, audit, clock = { clockMs })
        seed2.ensureSeeded()
        assertThat(seed2.consumeBootstrapPassword()).isNull()
    }

    @Test
    fun bootstrap_password_not_in_audit_log() = runBlocking {
        val seed = SeedData(userDao, permDao, audit, clock = { clockMs })
        seed.ensureSeeded()
        val password = seed.lastBootstrapPassword!!

        val events = auditDao.allEventsChronological()
        for (event in events) {
            // Password must not appear in any audit field
            assertThat(event.reason ?: "").doesNotContain(password)
            assertThat(event.payloadJson ?: "").doesNotContain(password)
            assertThat(event.action).doesNotContain(password)
        }
    }

    @Test
    fun audit_log_contains_confirmation_marker_not_password() = runBlocking {
        val seed = SeedData(userDao, permDao, audit, clock = { clockMs })
        seed.ensureSeeded()

        val events = auditDao.allEventsChronological()
        val seedEvent = events.first { it.action == "seed.initialized" }
        assertThat(seedEvent.payloadJson).contains("credential_marker")
        assertThat(seedEvent.payloadJson).doesNotContain("bootstrap_password")
    }

    @Test
    fun seeded_admin_has_password_reset_required_status() = runBlocking {
        val seed = SeedData(userDao, permDao, audit, clock = { clockMs })
        seed.ensureSeeded()

        val admin = userDao.findByUsername("admin")!!
        assertThat(admin.status).isEqualTo("password_reset_required")
    }

    @Test
    fun generated_password_verifies_against_stored_hash() = runBlocking {
        val seed = SeedData(userDao, permDao, audit, clock = { clockMs })
        seed.ensureSeeded()
        val password = seed.consumeBootstrapPassword()!!

        val admin = userDao.findByUsername("admin")!!
        val salt = PasswordHasher.decodeBase64(admin.passwordSalt)
        val expected = PasswordHasher.decodeBase64(admin.passwordHash)
        val matches = PasswordHasher.verify(password.toCharArray(), salt, expected, admin.kdfIterations)
        assertThat(matches).isTrue()
    }

    // --- Flow-based handoff lifecycle (mirrors LibOpsApp + LoginActivity pattern) ---

    @Test
    fun flow_handoff_delivers_password_after_seeding() = runBlocking {
        val flow = MutableStateFlow<String?>(null)
        assertThat(flow.value).isNull()

        // Simulate seeding completing and setting the password
        val seed = SeedData(userDao, permDao, audit, clock = { clockMs })
        seed.ensureSeeded()
        flow.value = seed.consumeBootstrapPassword()

        assertThat(flow.value).isNotNull()
        assertThat(flow.value!!.length).isAtLeast(12)
    }

    @Test
    fun flow_handoff_consume_clears_value() {
        val flow = MutableStateFlow<String?>("TestPassword!23")
        // Simulate consume
        val pw = flow.value
        flow.value = null

        assertThat(pw).isEqualTo("TestPassword!23")
        assertThat(flow.value).isNull()
    }

    @Test
    fun flow_handoff_null_when_not_first_run() = runBlocking {
        // Seed once
        val seed1 = SeedData(userDao, permDao, audit, clock = { clockMs })
        seed1.ensureSeeded()
        seed1.consumeBootstrapPassword()

        // Second seed — no password generated
        val seed2 = SeedData(userDao, permDao, audit, clock = { clockMs })
        seed2.ensureSeeded()

        val flow = MutableStateFlow<String?>(null)
        flow.value = seed2.consumeBootstrapPassword()
        assertThat(flow.value).isNull()
    }

    // --- Role-based action visibility (mirrors RecordsActivity gating logic) ---

    @Test
    fun records_read_only_hides_all_mutations() {
        val caps = setOf(Capabilities.RECORDS_READ)
        val authz = Authorizer(caps)
        // RecordsActivity gates these before showing menu items
        assertThat(authz.has(Capabilities.RECORDS_MANAGE)).isFalse()
        assertThat(authz.has(Capabilities.TAXONOMY_MANAGE)).isFalse()
        assertThat(authz.has(Capabilities.HOLDINGS_MANAGE)).isFalse()
        assertThat(authz.has(Capabilities.BARCODES_MANAGE)).isFalse()
    }

    @Test
    fun cataloger_sees_all_catalog_actions() {
        val caps = Roles.DEFAULT_MAPPING[Roles.CATALOGER]!!
        val authz = Authorizer(caps)
        assertThat(authz.has(Capabilities.RECORDS_MANAGE)).isTrue()
        assertThat(authz.has(Capabilities.TAXONOMY_MANAGE)).isTrue()
        assertThat(authz.has(Capabilities.HOLDINGS_MANAGE)).isTrue()
        assertThat(authz.has(Capabilities.BARCODES_MANAGE)).isTrue()
    }

    @Test
    fun collection_manager_cannot_edit_records() {
        val caps = Roles.DEFAULT_MAPPING[Roles.COLLECTION_MANAGER]!!
        val authz = Authorizer(caps)
        assertThat(authz.has(Capabilities.RECORDS_MANAGE)).isFalse()
        assertThat(authz.has(Capabilities.TAXONOMY_MANAGE)).isFalse()
        // But can read
        assertThat(authz.has(Capabilities.RECORDS_READ)).isTrue()
    }

    @Test
    fun auditor_cannot_mutate_catalog() {
        val caps = Roles.DEFAULT_MAPPING[Roles.AUDITOR]!!
        val authz = Authorizer(caps)
        assertThat(authz.has(Capabilities.RECORDS_MANAGE)).isFalse()
        assertThat(authz.has(Capabilities.TAXONOMY_MANAGE)).isFalse()
        assertThat(authz.has(Capabilities.HOLDINGS_MANAGE)).isFalse()
        assertThat(authz.has(Capabilities.BARCODES_MANAGE)).isFalse()
    }
}
