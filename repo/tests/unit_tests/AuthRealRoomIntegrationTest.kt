package com.eaglepoint.libops.tests

import androidx.test.core.app.ApplicationProvider
import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.auth.AuthRepository
import com.eaglepoint.libops.auth.SeedData
import com.eaglepoint.libops.auth.SessionStore
import com.eaglepoint.libops.data.db.LibOpsDatabase
import com.eaglepoint.libops.domain.AppResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real-Room integration test for the authentication pipeline.
 *
 * Exercises [SeedData] + [AuthRepository] + real Room DAOs (UserDao,
 * PermissionDao, SessionDao, AuditDao) end-to-end via [LibOpsDatabase.inMemory].
 * Complements [AuthRepositoryIntegrationTest] (which uses in-memory DAO
 * fakes) by verifying that the same flow works against the real entity
 * schemas and Room query execution.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AuthRealRoomIntegrationTest {

    private lateinit var db: LibOpsDatabase
    private lateinit var audit: AuditLogger
    private lateinit var store: SessionStore
    private lateinit var repo: AuthRepository
    private val clockMs = 1_700_000_000_000L
    private val seedPassword = "Admin@Review2024!"

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = LibOpsDatabase.inMemory(context)
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
    fun tearDown() {
        db.close()
    }

    @Test
    fun real_room_login_with_seeded_credentials_succeeds() = runBlocking {
        val result = repo.login("admin", seedPassword.toCharArray())
        assertThat(result).isInstanceOf(AppResult.Success::class.java)

        val session = (result as AppResult.Success).data
        assertThat(session.username).isEqualTo("admin")
        assertThat(session.activeRoleName).isEqualTo("administrator")
    }

    @Test
    fun real_room_login_creates_session_row_in_real_session_dao() = runBlocking {
        repo.login("admin", seedPassword.toCharArray())

        val user = db.userDao().findByUsername("admin")!!
        val sessions = db.sessionDao().openSessionsForUser(user.id)
        assertThat(sessions).hasSize(1)
        assertThat(sessions[0].state).isEqualTo("authenticated")
    }

    @Test
    fun real_room_login_records_audit_event() = runBlocking {
        repo.login("admin", seedPassword.toCharArray())

        val events = db.auditDao().allEventsChronological()
        assertThat(events.any { it.action == "login.success" }).isTrue()
    }

    @Test
    fun real_room_wrong_password_returns_validation_error() = runBlocking {
        val result = repo.login("admin", "WrongPassword!23".toCharArray())
        assertThat(result).isInstanceOf(AppResult.ValidationError::class.java)
    }

    @Test
    fun real_room_seeded_admin_has_all_capabilities() = runBlocking {
        val user = db.userDao().findByUsername("admin")!!
        val caps = db.permissionDao().permissionsForUser(user.id)
        assertThat(caps).contains("users.manage")
        assertThat(caps).contains("records.manage")
        assertThat(caps).contains("audit.read")
    }
}
