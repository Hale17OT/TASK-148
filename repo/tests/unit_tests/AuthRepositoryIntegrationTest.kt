package com.eaglepoint.libops.tests

import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.auth.AuthRepository
import com.eaglepoint.libops.auth.SeedData
import com.eaglepoint.libops.auth.SessionStore
import com.eaglepoint.libops.domain.AppResult
import com.eaglepoint.libops.domain.auth.LockoutPolicy
import com.eaglepoint.libops.tests.fakes.FakeAuditDao
import com.eaglepoint.libops.tests.fakes.FakePermissionDao
import com.eaglepoint.libops.tests.fakes.FakeSessionDao
import com.eaglepoint.libops.tests.fakes.FakeUserDao
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

/**
 * Live-path test of the real [AuthRepository] + [SeedData] + [AuditLogger]
 * wired together using in-memory fakes for the Room DAOs (§9.1, §9.2).
 *
 * Keeping these as pure-JVM tests rather than Robolectric-backed means they
 * don't need the Android-all-instrumented runtime jar — the CI/local test
 * run stays hermetic.
 */
class AuthRepositoryIntegrationTest {

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
            clock = { clockMs })
    }

    @Test
    fun login_succeeds_with_seeded_admin_credentials() = runBlocking {
        val result = repo.login("admin", TEST_PASSWORD.toCharArray())
        assertThat(result).isInstanceOf(AppResult.Success::class.java)
        val session = (result as AppResult.Success).data
        assertThat(session.username).isEqualTo("admin")
        assertThat(session.activeRoleName).isEqualTo("administrator")
        assertThat(session.capabilities).contains("users.manage")
    }

    @Test
    fun wrong_password_does_not_create_session() = runBlocking {
        val result = repo.login("admin", "WrongPassword123!".toCharArray())
        assertThat(result).isInstanceOf(AppResult.ValidationError::class.java)
        assertThat(store.snapshot()).isNull()
    }

    @Test
    fun five_failures_lock_account_for_15_minutes() = runBlocking {
        for (attempt in 1..LockoutPolicy.MAX_FAILED_ATTEMPTS - 1) {
            val r = repo.login("admin", "WrongGuess123!@".toCharArray())
            assertThat(r).isInstanceOf(AppResult.ValidationError::class.java)
        }
        val lockingAttempt = repo.login("admin", "WrongGuess123!@".toCharArray())
        assertThat(lockingAttempt).isInstanceOf(AppResult.Locked::class.java)

        val afterLock = repo.login("admin", TEST_PASSWORD.toCharArray())
        assertThat(afterLock).isInstanceOf(AppResult.Locked::class.java)
        val locked = afterLock as AppResult.Locked
        assertThat(locked.minutesRemaining).isEqualTo(LockoutPolicy.LOCKOUT_MINUTES.toInt())
    }

    @Test
    fun successful_login_after_lockout_expires_resets_counter() = runBlocking {
        repeat(LockoutPolicy.MAX_FAILED_ATTEMPTS) {
            repo.login("admin", "WrongGuess123!@".toCharArray())
        }
        clockMs += LockoutPolicy.LOCKOUT_MILLIS + 1

        val ok = repo.login("admin", TEST_PASSWORD.toCharArray())
        assertThat(ok).isInstanceOf(AppResult.Success::class.java)
        val user = userDao.findByUsername("admin")!!
        assertThat(user.failedAttempts).isEqualTo(0)
        assertThat(user.lockoutUntilEpochMillis).isNull()
    }

    @Test
    fun login_writes_audit_event_with_hash_chain() = runBlocking {
        repo.login("admin", TEST_PASSWORD.toCharArray())
        val events = auditDao.allEventsChronological()
        val loginEvents = events.filter { it.action == "login.success" }
        assertThat(loginEvents).isNotEmpty()
        val last = loginEvents.last()
        assertThat(last.eventHash.length).isEqualTo(64)
        assertThat(last.previousEventHash).isNotNull()
        val seedEvents = events.filter { it.action == "seed.initialized" }
        assertThat(seedEvents).isNotEmpty()
    }

    @Test
    fun logout_expires_session() = runBlocking {
        repo.login("admin", TEST_PASSWORD.toCharArray())
        assertThat(store.snapshot()).isNotNull()
        repo.logout()
        assertThat(store.snapshot()).isNull()
    }

    @Test
    fun biometric_cannot_be_enabled_without_password_login() = runBlocking {
        // Direct call with no session
        val result = repo.enableBiometric()
        assertThat(result).isInstanceOf(AppResult.PermissionDenied::class.java)
    }

    @Test
    fun biometric_can_be_enabled_after_successful_password_login() = runBlocking {
        repo.login("admin", TEST_PASSWORD.toCharArray())
        val result = repo.enableBiometric()
        assertThat(result).isInstanceOf(AppResult.Success::class.java)
        val admin = userDao.findByUsername("admin")!!
        assertThat(admin.biometricEnabled).isTrue()
    }

    @Test
    fun unknown_user_returns_invalid_credentials() = runBlocking {
        val result = repo.login("ghost", "SomeLongPassword1@".toCharArray())
        assertThat(result).isInstanceOf(AppResult.ValidationError::class.java)
    }
}
