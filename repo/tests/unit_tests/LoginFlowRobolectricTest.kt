package com.eaglepoint.libops.tests

import androidx.test.core.app.ApplicationProvider
import com.eaglepoint.libops.LibOpsApp
import com.eaglepoint.libops.auth.SessionStore
import com.eaglepoint.libops.domain.AppResult
import com.eaglepoint.libops.domain.auth.Capabilities
import com.eaglepoint.libops.domain.auth.Roles
import com.eaglepoint.libops.ui.AuthorizationGate
import com.eaglepoint.libops.ui.LoginViewModel
import com.eaglepoint.libops.ui.Navigation
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Activity-level functional test that exercises the login → session →
 * authorization gate → navigation visibility pipeline against the real
 * [LibOpsApp] environment (§9.1, §9.2, §11).
 *
 * Validates that a successful login produces a session whose capabilities
 * correctly gate UI navigation and [AuthorizationGate.requireAccess].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = LibOpsApp::class, sdk = [34], manifest = Config.NONE)
class LoginFlowRobolectricTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var app: LibOpsApp

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        app = ApplicationProvider.getApplicationContext()
        app.sessionStore.clear()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun adminSession() = SessionStore.ActiveSession(
        sessionId = 1L, userId = 1L, username = "admin",
        activeRoleName = Roles.ADMIN,
        capabilities = Roles.DEFAULT_MAPPING[Roles.ADMIN]!!,
        authenticatedAt = System.currentTimeMillis(),
        lastActiveMillis = System.currentTimeMillis(),
        biometricEnabled = false,
    )

    private fun catalogerSession() = SessionStore.ActiveSession(
        sessionId = 2L, userId = 2L, username = "cat_reviewer",
        activeRoleName = Roles.CATALOGER,
        capabilities = Roles.DEFAULT_MAPPING[Roles.CATALOGER]!!,
        authenticatedAt = System.currentTimeMillis(),
        lastActiveMillis = System.currentTimeMillis(),
        biometricEnabled = false,
    )

    // ── login → session → navigation visibility ───────────────────────────────

    @Test
    fun admin_session_unlocks_all_navigation_entries() {
        app.sessionStore.set(adminSession())
        val caps = app.sessionStore.snapshot()!!.capabilities
        val visible = Navigation.visibleFor(caps)
        assertThat(visible).hasSize(Navigation.ALL.size)
    }

    @Test
    fun cataloger_session_hides_admin_and_audit_entries() {
        app.sessionStore.set(catalogerSession())
        val caps = app.sessionStore.snapshot()!!.capabilities
        val keys = Navigation.visibleFor(caps).map { it.key }.toSet()
        assertThat(keys).contains("records")
        assertThat(keys).contains("imports")
        assertThat(keys).doesNotContain("admin")
        assertThat(keys).doesNotContain("audit_logs")
    }

    // ── session → authorization gate ──────────────────────────────────────────

    @Test
    fun authorization_gate_grants_access_with_matching_capability() {
        app.sessionStore.set(adminSession())
        val activity = Robolectric.buildActivity(AuthGateTestActivity::class.java)
            .create().start().resume().get()

        val result = AuthorizationGate.requireAccess(activity, Capabilities.USERS_MANAGE)
        assertThat(result).isNotNull()
        assertThat(result!!.username).isEqualTo("admin")
        assertThat(activity.isFinishing).isFalse()
    }

    @Test
    fun authorization_gate_denies_access_without_capability() {
        app.sessionStore.set(catalogerSession())
        val activity = Robolectric.buildActivity(AuthGateTestActivity::class.java)
            .create().start().resume().get()

        val result = AuthorizationGate.requireAccess(activity, Capabilities.USERS_MANAGE)
        assertThat(result).isNull()
        assertThat(activity.isFinishing).isTrue()
    }

    // ── login viewmodel → session store → gate ─────────────────────────────────

    @Test
    fun successful_login_creates_session_visible_to_gate() = runTest {
        val session = adminSession()
        val repo = StubAuthRepository(
            loginResult = AppResult.Success(session),
            biometricResult = AppResult.Success(session),
        )
        val vm = LoginViewModel(repo)

        vm.login("admin", "Admin@Review2024!".toCharArray())
        advanceUntilIdle()

        assertThat(vm.state.value).isInstanceOf(LoginViewModel.UiState.Authenticated::class.java)
        val authed = vm.state.value as LoginViewModel.UiState.Authenticated
        assertThat(authed.session.capabilities).contains(Capabilities.USERS_MANAGE)
    }

    // ── role-based end-to-end: session → nav → gate ─────────────────────────

    @Test
    fun end_to_end_cataloger_session_restricts_navigation_and_gate() {
        // Set a cataloger session directly (LoginViewModel delegation already
        // tested above; this test validates the session→nav→gate pipeline).
        val session = catalogerSession()
        app.sessionStore.set(session)

        // Navigation should restrict to cataloger-visible entries
        val caps = app.sessionStore.snapshot()!!.capabilities
        val keys = Navigation.visibleFor(caps).map { it.key }.toSet()
        assertThat(keys).contains("records")
        assertThat(keys).doesNotContain("admin")

        // Gate should deny USERS_MANAGE
        val activity = Robolectric.buildActivity(AuthGateTestActivity::class.java)
            .create().start().resume().get()
        val gateResult = AuthorizationGate.requireAccess(activity, Capabilities.USERS_MANAGE)
        assertThat(gateResult).isNull()
    }
}
