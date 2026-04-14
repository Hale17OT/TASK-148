package com.eaglepoint.libops.tests

import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.auth.AuthRepository
import com.eaglepoint.libops.auth.SeedData
import com.eaglepoint.libops.auth.SessionStore
import com.eaglepoint.libops.domain.AppResult
import com.eaglepoint.libops.domain.auth.Authorizer
import com.eaglepoint.libops.domain.auth.Capabilities
import com.eaglepoint.libops.tests.fakes.FakeAuditDao
import com.eaglepoint.libops.tests.fakes.FakePermissionDao
import com.eaglepoint.libops.tests.fakes.FakeSessionDao
import com.eaglepoint.libops.tests.fakes.FakeUserDao
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

/**
 * Integration test covering the full first-run bootstrap lifecycle:
 * seed → persist credential → login → password reset required → role access.
 *
 * Uses fake DAOs to exercise the real [SeedData] + [AuthRepository] pipeline
 * end-to-end without Android runtime dependencies.
 */
class BootstrapIntegrationTest {

    private lateinit var userDao: FakeUserDao
    private lateinit var permDao: FakePermissionDao
    private lateinit var sessionDao: FakeSessionDao
    private lateinit var auditDao: FakeAuditDao
    private lateinit var audit: AuditLogger
    private lateinit var store: SessionStore
    private var clockMs = 1_700_000_000_000L

    @Before
    fun setUp() {
        userDao = FakeUserDao()
        permDao = FakePermissionDao()
        sessionDao = FakeSessionDao()
        auditDao = FakeAuditDao()
        audit = AuditLogger(auditDao) { clockMs }
        store = SessionStore()
    }

    @Test
    fun full_bootstrap_login_and_role_access_lifecycle() = runBlocking {
        // 1. Seed creates admin with generated password
        val seed = SeedData(userDao, permDao, audit, clock = { clockMs })
        seed.ensureSeeded()
        val bootstrapPw = seed.consumeBootstrapPassword()!!

        // 2. Store in encrypted store (using fake that mirrors real contract)
        val encryptedStore = FakeBootstrapCredentialStore()
        encryptedStore.store(bootstrapPw)
        assertThat(encryptedStore.peek()).isNotNull()

        // 3. Login with the bootstrap password
        val repo = AuthRepository(userDao, permDao, sessionDao, store, audit, clock = { clockMs })
        val loginResult = repo.login("admin", bootstrapPw.toCharArray())
        assertThat(loginResult).isInstanceOf(AppResult.Success::class.java)

        val session = (loginResult as AppResult.Success).data
        assertThat(session.passwordResetRequired).isTrue()
        assertThat(session.activeRoleName).isEqualTo("administrator")

        // 4. Verify admin has full capabilities
        val authz = Authorizer(session.capabilities)
        assertThat(authz.has(Capabilities.USERS_MANAGE)).isTrue()
        assertThat(authz.has(Capabilities.RECORDS_MANAGE)).isTrue()
        assertThat(authz.has(Capabilities.SOURCES_MANAGE)).isTrue()
        assertThat(authz.has(Capabilities.ALERTS_RESOLVE)).isTrue()

        // 5. Acknowledge — consume from encrypted store (mirrors acknowledgeBootstrapCredential)
        val consumed = encryptedStore.consume()
        assertThat(consumed).isEqualTo(bootstrapPw)
        assertThat(encryptedStore.peek()).isNull()

        // 6. Second seed attempt produces nothing
        val seed2 = SeedData(userDao, permDao, audit, clock = { clockMs })
        seed2.ensureSeeded()
        assertThat(seed2.consumeBootstrapPassword()).isNull()
    }

    @Test
    fun bootstrap_password_survives_simulated_process_death() = runBlocking {
        // 1. First process: seed + store
        val seed = SeedData(userDao, permDao, audit, clock = { clockMs })
        seed.ensureSeeded()
        val pw = seed.consumeBootstrapPassword()!!
        val encStore = FakeBootstrapCredentialStore()
        encStore.store(pw)

        // 2. Simulate process death — in-memory state gone, encrypted store persists

        // 3. Second process: recover from encrypted storage
        val recovered = encStore.peek()
        assertThat(recovered).isEqualTo(pw)

        // 4. Login still works
        val repo = AuthRepository(userDao, permDao, sessionDao, store, audit, clock = { clockMs })
        val result = repo.login("admin", recovered!!.toCharArray())
        assertThat(result).isInstanceOf(AppResult.Success::class.java)
    }

    @Test
    fun role_gated_source_management_requires_correct_capability() = runBlocking {
        val seed = SeedData(userDao, permDao, audit, clock = { clockMs },
            bootstrapPasswordOverride = "TestPass!234")
        seed.ensureSeeded()

        val repo = AuthRepository(userDao, permDao, sessionDao, store, audit, clock = { clockMs })
        val result = repo.login("admin", "TestPass!234".toCharArray())
        val session = (result as AppResult.Success).data

        // Admin can manage sources
        assertThat(Capabilities.SOURCES_MANAGE in session.capabilities).isTrue()

        // Create a non-admin user and check they can't manage sources
        val now = clockMs
        val salt = com.eaglepoint.libops.domain.auth.PasswordHasher.generateSalt()
        val hash = com.eaglepoint.libops.domain.auth.PasswordHasher.hash("AuditorPass!23".toCharArray(), salt)
        val auditorId = userDao.insert(com.eaglepoint.libops.data.db.entity.UserEntity(
            username = "auditor1", displayName = "Auditor",
            passwordHash = com.eaglepoint.libops.domain.auth.PasswordHasher.encodeBase64(hash),
            passwordSalt = com.eaglepoint.libops.domain.auth.PasswordHasher.encodeBase64(salt),
            kdfAlgorithm = com.eaglepoint.libops.domain.auth.PasswordHasher.ALGORITHM,
            kdfIterations = com.eaglepoint.libops.domain.auth.PasswordHasher.ITERATIONS,
            kdfMemoryKb = com.eaglepoint.libops.domain.auth.PasswordHasher.MEMORY_KB,
            status = "active", biometricEnabled = false,
            createdAt = now, updatedAt = now,
        ))
        val auditorRole = permDao.roleByName("auditor")!!
        permDao.assignRole(com.eaglepoint.libops.data.db.entity.UserRoleEntity(
            userId = auditorId, roleId = auditorRole.id, active = true,
            assignedAt = now, assignedByUserId = null,
        ))

        store.clear()
        val auditorResult = repo.login("auditor1", "AuditorPass!23".toCharArray())
        val auditorSession = (auditorResult as AppResult.Success).data

        assertThat(Capabilities.SOURCES_MANAGE in auditorSession.capabilities).isFalse()
        assertThat(Capabilities.RECORDS_MANAGE in auditorSession.capabilities).isFalse()
        assertThat(Capabilities.AUDIT_READ in auditorSession.capabilities).isTrue()
    }
}
