package com.eaglepoint.libops.tests

import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.auth.AuthRepository
import com.eaglepoint.libops.auth.SeedData
import com.eaglepoint.libops.auth.SessionStore
import com.eaglepoint.libops.domain.AppResult
import com.eaglepoint.libops.tests.fakes.FakeAuditDao
import com.eaglepoint.libops.tests.fakes.FakePermissionDao
import com.eaglepoint.libops.tests.fakes.FakeSessionDao
import com.eaglepoint.libops.tests.fakes.FakeUserDao
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

/**
 * Tests the biometric resume path (§9.1): after a successful password login,
 * a user with biometric enabled can use [AuthRepository.resumeViaBiometric]
 * to create a new authenticated session without re-entering their password.
 */
class BiometricLoginTest {

    companion object {
        const val TEST_PASSWORD = "TestPassword!23"
    }

    private lateinit var userDao: FakeUserDao
    private lateinit var permDao: FakePermissionDao
    private lateinit var sessionDao: FakeSessionDao
    private lateinit var auditDao: FakeAuditDao
    private lateinit var audit: AuditLogger
    private lateinit var store: SessionStore
    private lateinit var repo: AuthRepository
    private var clockMs = 1_700_000_000_000L

    @Before
    fun setUp() = runBlocking {
        userDao = FakeUserDao()
        permDao = FakePermissionDao()
        sessionDao = FakeSessionDao()
        auditDao = FakeAuditDao()
        audit = AuditLogger(auditDao) { clockMs }
        store = SessionStore()
        SeedData(userDao, permDao, audit, clock = { clockMs }, bootstrapPasswordOverride = TEST_PASSWORD).ensureSeeded()
        repo = AuthRepository(
            userDao = userDao,
            permissionDao = permDao,
            sessionDao = sessionDao,
            sessionStore = store,
            audit = audit,
            clock = { clockMs },
        )
    }

    @Test
    fun biometric_resume_fails_without_prior_password_login() = runBlocking {
        // Enable biometric on the seeded admin (who hasn't logged in yet via this repo)
        val user = userDao.findByUsername("admin")!!
        userDao.setBiometric(user.id, true, clockMs)
        // resumeViaBiometric should fail because lastPasswordLoginEpochMillis is still null
        val result = repo.resumeViaBiometric("admin")
        assertThat(result).isInstanceOf(AppResult.Conflict::class.java)
    }

    @Test
    fun biometric_resume_succeeds_after_password_login_and_enable() = runBlocking {
        // Step 1: Password login
        val loginResult = repo.login("admin", TEST_PASSWORD.toCharArray())
        assertThat(loginResult).isInstanceOf(AppResult.Success::class.java)

        // Step 2: Enable biometric
        val enableResult = repo.enableBiometric()
        assertThat(enableResult).isInstanceOf(AppResult.Success::class.java)

        // Step 3: Clear session (simulate app restart)
        store.clear()

        // Step 4: Resume via biometric
        clockMs += 60_000 // 1 minute later
        val bioResult = repo.resumeViaBiometric("admin")
        assertThat(bioResult).isInstanceOf(AppResult.Success::class.java)
        val session = (bioResult as AppResult.Success).data
        assertThat(session.username).isEqualTo("admin")
        assertThat(session.biometricEnabled).isTrue()
    }

    @Test
    fun biometric_resume_fails_for_disabled_account() = runBlocking {
        // Login and enable biometric
        repo.login("admin", TEST_PASSWORD.toCharArray())
        repo.enableBiometric()
        store.clear()

        // Disable the user account
        val user = userDao.findByUsername("admin")!!
        userDao.update(user.copy(status = "disabled"))

        val result = repo.resumeViaBiometric("admin")
        assertThat(result).isInstanceOf(AppResult.Conflict::class.java)
        assertThat((result as AppResult.Conflict).reason).isEqualTo("status_disabled")
    }

    @Test
    fun biometric_resume_fails_for_unknown_user() = runBlocking {
        val result = repo.resumeViaBiometric("nonexistent")
        assertThat(result).isInstanceOf(AppResult.NotFound::class.java)
    }

    @Test
    fun biometric_resume_fails_when_biometric_not_enabled() = runBlocking {
        // Login but do NOT enable biometric
        repo.login("admin", TEST_PASSWORD.toCharArray())
        store.clear()

        val result = repo.resumeViaBiometric("admin")
        assertThat(result).isInstanceOf(AppResult.Conflict::class.java)
    }

    @Test
    fun biometric_resume_creates_session_with_biometric_flag() = runBlocking {
        repo.login("admin", TEST_PASSWORD.toCharArray())
        repo.enableBiometric()
        store.clear()
        clockMs += 60_000

        val result = repo.resumeViaBiometric("admin")
        assertThat(result).isInstanceOf(AppResult.Success::class.java)
        val session = (result as AppResult.Success).data
        // The session should reflect biometric usage
        assertThat(store.snapshot()).isNotNull()
        assertThat(store.snapshot()!!.sessionId).isEqualTo(session.sessionId)
    }

    @Test
    fun biometric_resume_audits_success() = runBlocking {
        repo.login("admin", TEST_PASSWORD.toCharArray())
        repo.enableBiometric()
        store.clear()
        clockMs += 60_000

        repo.resumeViaBiometric("admin")
        val events = auditDao.allEventsChronological()
        assertThat(events.any { it.action == "login.success_biometric" }).isTrue()
    }

    @Test
    fun biometric_resume_audits_denial() = runBlocking {
        // Login but don't enable biometric — resume will be denied
        repo.login("admin", TEST_PASSWORD.toCharArray())
        store.clear()

        repo.resumeViaBiometric("admin")
        val events = auditDao.allEventsChronological()
        assertThat(events.any { it.action == "biometric.denied" }).isTrue()
    }
}
