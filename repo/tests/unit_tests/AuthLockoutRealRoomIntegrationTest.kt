package com.eaglepoint.libops.tests

import androidx.test.core.app.ApplicationProvider
import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.auth.AuthRepository
import com.eaglepoint.libops.auth.SeedData
import com.eaglepoint.libops.auth.SessionStore
import com.eaglepoint.libops.data.db.LibOpsDatabase
import com.eaglepoint.libops.domain.AppResult
import com.eaglepoint.libops.domain.auth.LockoutPolicy
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end real-Room integration test of the authentication lockout flow
 * (§9.1, §9.2). Drives the auth pipeline from seeded credentials through
 * MAX_FAILED_ATTEMPTS failures → account locked → lockout window expires →
 * successful login re-enables session.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AuthLockoutRealRoomIntegrationTest {

    private lateinit var db: LibOpsDatabase
    private lateinit var audit: AuditLogger
    private lateinit var store: SessionStore
    private lateinit var repo: AuthRepository
    private var clockMs = 1_700_000_000_000L
    private val seedPassword = "Admin@Review2024!"

    @Before
    fun setUp() = runBlocking {
        db = LibOpsDatabase.inMemory(
            ApplicationProvider.getApplicationContext<android.content.Context>()
        )
        audit = AuditLogger(db.auditDao(), clock = { clockMs })
        store = SessionStore()

        SeedData(
            userDao = db.userDao(),
            permissionDao = db.permissionDao(),
            audit = audit,
            clock = { clockMs },
            bootstrapPasswordOverride = seedPassword,
        ).ensureSeeded()

        repo = AuthRepository(
            userDao = db.userDao(),
            permissionDao = db.permissionDao(),
            sessionDao = db.sessionDao(),
            sessionStore = store,
            audit = audit,
            clock = { clockMs },
        )
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun five_failed_attempts_lock_the_account(): Unit = runBlocking {
        repeat(LockoutPolicy.MAX_FAILED_ATTEMPTS) {
            val r = repo.login("admin", "Wrong#Pass123".toCharArray())
            // May be ValidationError for attempts 1..4, and Locked on attempt 5
            assertThat(r is AppResult.ValidationError || r is AppResult.Locked).isTrue()
        }

        val locked = db.userDao().findByUsername("admin")!!
        assertThat(locked.status).isEqualTo("locked")
        assertThat(locked.lockoutUntilEpochMillis).isNotNull()
    }

    @Test
    fun correct_password_during_lockout_is_rejected(): Unit = runBlocking {
        repeat(LockoutPolicy.MAX_FAILED_ATTEMPTS) {
            repo.login("admin", "Wrong#Pass123".toCharArray())
        }
        // Now try the CORRECT password while still locked
        val result = repo.login("admin", seedPassword.toCharArray())
        assertThat(result).isInstanceOf(AppResult.Locked::class.java)
    }

    @Test
    fun lockout_events_are_persisted_to_real_audit_table(): Unit = runBlocking {
        repeat(LockoutPolicy.MAX_FAILED_ATTEMPTS) {
            repo.login("admin", "Wrong#Pass123".toCharArray())
        }
        val events = db.auditDao().allEventsChronological()
        assertThat(events.any { it.action == "login.failure" }).isTrue()
        assertThat(events.any { it.action == "login.lockout_triggered" }).isTrue()
    }

    @Test
    fun successful_login_after_window_expiry_creates_session(): Unit = runBlocking {
        // Lock the account
        repeat(LockoutPolicy.MAX_FAILED_ATTEMPTS) {
            repo.login("admin", "Wrong#Pass123".toCharArray())
        }
        // Advance clock past lockout window
        clockMs += LockoutPolicy.LOCKOUT_MINUTES * 60_000L + 1000L

        // Rebuild repo with new clock (since we captured clockMs at constructor time)
        val freshRepo = AuthRepository(
            userDao = db.userDao(),
            permissionDao = db.permissionDao(),
            sessionDao = db.sessionDao(),
            sessionStore = store,
            audit = audit,
            clock = { clockMs },
        )
        val result = freshRepo.login("admin", seedPassword.toCharArray())
        assertThat(result).isInstanceOf(AppResult.Success::class.java)
    }

    @Test
    fun unknown_user_login_does_not_affect_real_user_lockout_counter(): Unit = runBlocking {
        repeat(3) {
            repo.login("nonexistent", "anything".toCharArray())
        }
        val admin = db.userDao().findByUsername("admin")!!
        assertThat(admin.failedAttempts).isEqualTo(0)
        assertThat(admin.status).isNotEqualTo("locked")
    }
}
