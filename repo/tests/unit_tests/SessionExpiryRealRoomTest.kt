package com.eaglepoint.libops.tests

import androidx.test.core.app.ApplicationProvider
import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.auth.AuthRepository
import com.eaglepoint.libops.auth.SeedData
import com.eaglepoint.libops.auth.SessionStore
import com.eaglepoint.libops.data.db.LibOpsDatabase
import com.eaglepoint.libops.domain.AppResult
import com.eaglepoint.libops.domain.auth.SessionTimeouts
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end real-Room integration test for session expiry + restore
 * (§9.2). Covers:
 *   - login creates a session row with expiresAt in the future
 *   - restoreSession() after clock advance past expiresAt returns null
 *   - expired session rows are marked 'expired' in the real sessions table
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SessionExpiryRealRoomTest {

    private lateinit var db: LibOpsDatabase
    private lateinit var audit: AuditLogger
    private lateinit var store: SessionStore
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
    }

    @After
    fun tearDown() { db.close() }

    private fun repoAt(nowMs: Long) = AuthRepository(
        userDao = db.userDao(),
        permissionDao = db.permissionDao(),
        sessionDao = db.sessionDao(),
        sessionStore = store,
        audit = audit,
        clock = { nowMs },
    )

    @Test
    fun login_creates_session_with_expires_at_in_future(): Unit = runBlocking {
        val result = repoAt(clockMs).login("admin", seedPassword.toCharArray())
        assertThat(result).isInstanceOf(AppResult.Success::class.java)

        val user = db.userDao().findByUsername("admin")!!
        val sessions = db.sessionDao().openSessionsForUser(user.id)
        assertThat(sessions).hasSize(1)
        assertThat(sessions[0].expiresAt).isGreaterThan(clockMs)
    }

    @Test
    fun session_past_idle_limit_is_not_restored(): Unit = runBlocking {
        // Login at t0
        repoAt(clockMs).login("admin", seedPassword.toCharArray())
        val user = db.userDao().findByUsername("admin")!!
        val originalSession = db.sessionDao().openSessionsForUser(user.id)[0]

        // Advance clock past admin's 10-minute idle limit
        val advanced = clockMs + SessionTimeouts.PRIVILEGED_MINUTES * 60_000L + 5_000L

        // New repo at advanced time calls restoreSession
        val restored = repoAt(advanced).restoreSession()
        assertThat(restored).isNull()

        // The session row should now be marked 'expired' in real sessions table
        val refreshed = db.sessionDao().openSessionsForUser(user.id)
        assertThat(refreshed).isEmpty()
    }

    @Test
    fun session_within_idle_limit_can_be_restored(): Unit = runBlocking {
        repoAt(clockMs).login("admin", seedPassword.toCharArray())

        // Advance clock by 3 minutes — well within admin 10-min limit
        val advanced = clockMs + 3L * 60_000L

        val restored = repoAt(advanced).restoreSession()
        assertThat(restored).isNotNull()
        assertThat(restored!!.username).isEqualTo("admin")
    }

    @Test
    fun second_login_revokes_prior_session_in_real_sessions_table(): Unit = runBlocking {
        repoAt(clockMs).login("admin", seedPassword.toCharArray())
        val user = db.userDao().findByUsername("admin")!!
        val firstSession = db.sessionDao().openSessionsForUser(user.id)[0]

        // Log in again (simulates a second device or retry) — should revoke the first
        repoAt(clockMs + 1_000L).login("admin", seedPassword.toCharArray())

        val open = db.sessionDao().openSessionsForUser(user.id)
        assertThat(open).hasSize(1)
        assertThat(open[0].id).isNotEqualTo(firstSession.id)
    }
}
