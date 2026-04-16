package com.eaglepoint.libops.tests

import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.auth.AuthRepository
import com.eaglepoint.libops.auth.SessionStore
import com.eaglepoint.libops.domain.AppResult
import com.eaglepoint.libops.domain.FieldError
import com.eaglepoint.libops.tests.fakes.FakeAuditDao
import com.eaglepoint.libops.tests.fakes.FakePermissionDao
import com.eaglepoint.libops.tests.fakes.FakeSessionDao
import com.eaglepoint.libops.tests.fakes.FakeUserDao
import com.eaglepoint.libops.ui.LoginViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Tests for [LoginViewModel] state-machine transitions covering all
 * [AppResult] variants returned by [AuthRepository.login] and
 * [AuthRepository.resumeViaBiometric] (§9.1).
 *
 * Uses [StubAuthRepository] (a test subclass of the now-open
 * [AuthRepository]) to avoid real PBKDF2 hashing / Dispatchers.IO.
 * [UnconfinedTestDispatcher] replaces [Dispatchers.Main] so that
 * [viewModelScope.launch] coroutines execute eagerly and state is
 * observable immediately after each [LoginViewModel] call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun fakeSession(username: String = "admin") = SessionStore.ActiveSession(
        sessionId = 1L, userId = 1L, username = username,
        activeRoleName = "administrator",
        capabilities = setOf("users.manage"),
        authenticatedAt = 0L, lastActiveMillis = 0L,
        biometricEnabled = false,
    )

    private fun stubRepo(
        loginResult: AppResult<SessionStore.ActiveSession> = AppResult.Success(fakeSession()),
        biometricResult: AppResult<SessionStore.ActiveSession> = AppResult.Success(fakeSession()),
    ): StubAuthRepository = StubAuthRepository(loginResult, biometricResult)

    // ── initial state ──────────────────────────────────────────────────────────

    @Test
    fun initial_state_is_idle() {
        val vm = LoginViewModel(stubRepo())
        assertThat(vm.state.value).isEqualTo(LoginViewModel.UiState.Idle)
    }

    // ── login → AppResult.Success ──────────────────────────────────────────────

    @Test
    fun successful_login_transitions_to_authenticated() = runTest {
        val vm = LoginViewModel(stubRepo(loginResult = AppResult.Success(fakeSession("alice"))))

        vm.login("alice", "pass".toCharArray())
        advanceUntilIdle()

        val state = vm.state.value
        assertThat(state).isInstanceOf(LoginViewModel.UiState.Authenticated::class.java)
        assertThat((state as LoginViewModel.UiState.Authenticated).session.username).isEqualTo("alice")
    }

    // ── login → AppResult.ValidationError ─────────────────────────────────────

    @Test
    fun invalid_credentials_transition_to_error() = runTest {
        val vm = LoginViewModel(
            stubRepo(
                loginResult = AppResult.ValidationError(
                    listOf(FieldError("credentials", "invalid", "Invalid credentials"))
                )
            )
        )

        vm.login("user", "bad".toCharArray())
        advanceUntilIdle()

        val state = vm.state.value
        assertThat(state).isInstanceOf(LoginViewModel.UiState.Error::class.java)
        assertThat((state as LoginViewModel.UiState.Error).message).contains("Invalid credentials")
    }

    // ── login → AppResult.Locked ───────────────────────────────────────────────

    @Test
    fun locked_account_transitions_to_locked_state_with_minutes_remaining() = runTest {
        val vm = LoginViewModel(stubRepo(loginResult = AppResult.Locked(minutesRemaining = 12)))

        vm.login("user", "guess".toCharArray())
        advanceUntilIdle()

        val state = vm.state.value
        assertThat(state).isInstanceOf(LoginViewModel.UiState.Locked::class.java)
        assertThat((state as LoginViewModel.UiState.Locked).minutesRemaining).isEqualTo(12)
    }

    // ── login → AppResult.Conflict ─────────────────────────────────────────────

    @Test
    fun conflict_result_maps_to_error_with_reason() = runTest {
        val vm = LoginViewModel(
            stubRepo(loginResult = AppResult.Conflict("account", "password_reset_required"))
        )

        vm.login("admin", "old".toCharArray())
        advanceUntilIdle()

        val state = vm.state.value
        assertThat(state).isInstanceOf(LoginViewModel.UiState.Error::class.java)
        assertThat((state as LoginViewModel.UiState.Error).message).contains("password reset required")
    }

    // ── guard: second call while Submitting is ignored ─────────────────────────

    @Test
    fun second_login_call_while_submitting_is_a_noop() = runTest {
        // Use a repo stub that suspends until we allow it — simulated via a
        // SuspendingStubAuthRepository that never resumes to exercise the guard.
        val suspendingRepo = SuspendingStubAuthRepository()
        val vm = LoginViewModel(suspendingRepo)

        vm.login("user", "pass1".toCharArray())
        // At this point the UnconfinedTestDispatcher has started the launch and hit
        // the suspension point — state is Submitting.
        assertThat(vm.state.value).isEqualTo(LoginViewModel.UiState.Submitting)

        // Second call with different credentials must be silently ignored.
        vm.login("other", "pass2".toCharArray())
        assertThat(vm.state.value).isEqualTo(LoginViewModel.UiState.Submitting)
        // Resume so the test can finish cleanly.
        suspendingRepo.resume(AppResult.Success(fakeSession()))
        advanceUntilIdle()
    }

    // ── reset ──────────────────────────────────────────────────────────────────

    @Test
    fun reset_returns_to_idle_after_error() = runTest {
        val vm = LoginViewModel(
            stubRepo(
                loginResult = AppResult.ValidationError(
                    listOf(FieldError("creds", "bad", "Bad credentials"))
                )
            )
        )

        vm.login("user", "bad".toCharArray())
        advanceUntilIdle()
        assertThat(vm.state.value).isInstanceOf(LoginViewModel.UiState.Error::class.java)

        vm.reset()
        assertThat(vm.state.value).isEqualTo(LoginViewModel.UiState.Idle)
    }

    // ── biometric login ────────────────────────────────────────────────────────

    @Test
    fun biometric_success_transitions_to_authenticated() = runTest {
        val vm = LoginViewModel(stubRepo(biometricResult = AppResult.Success(fakeSession("admin"))))

        vm.loginViaBiometric("admin")
        advanceUntilIdle()

        assertThat(vm.state.value).isInstanceOf(LoginViewModel.UiState.Authenticated::class.java)
    }

    @Test
    fun biometric_conflict_maps_to_error_with_unavailable_message() = runTest {
        val vm = LoginViewModel(
            stubRepo(biometricResult = AppResult.Conflict("session", "biometric_not_enrolled"))
        )

        vm.loginViaBiometric("admin")
        advanceUntilIdle()

        val state = vm.state.value
        assertThat(state).isInstanceOf(LoginViewModel.UiState.Error::class.java)
        assertThat((state as LoginViewModel.UiState.Error).message).contains("Biometric unavailable")
    }
}

// ── Test doubles ──────────────────────────────────────────────────────────────

/**
 * Overrides [login] and [resumeViaBiometric] with immediate stubs so that
 * [viewModelScope.launch] blocks complete synchronously under
 * [UnconfinedTestDispatcher]. Constructed with the desired result to return,
 * avoiding any real PBKDF2 hashing or database I/O.
 */
class StubAuthRepository(
    private val loginResult: AppResult<SessionStore.ActiveSession>,
    private val biometricResult: AppResult<SessionStore.ActiveSession>,
) : AuthRepository(
    userDao = FakeUserDao(),
    permissionDao = FakePermissionDao(),
    sessionDao = FakeSessionDao(),
    sessionStore = SessionStore(),
    audit = AuditLogger(FakeAuditDao()),
) {
    override suspend fun login(rawUsername: String, password: CharArray): AppResult<SessionStore.ActiveSession> =
        loginResult

    override suspend fun resumeViaBiometric(rawUsername: String): AppResult<SessionStore.ActiveSession> =
        biometricResult
}

/**
 * A stub that suspends [login] until [resume] is called — lets tests verify
 * that the Submitting guard rejects a concurrent second [LoginViewModel.login]
 * call.
 */
class SuspendingStubAuthRepository : AuthRepository(
    userDao = FakeUserDao(),
    permissionDao = FakePermissionDao(),
    sessionDao = FakeSessionDao(),
    sessionStore = SessionStore(),
    audit = AuditLogger(FakeAuditDao()),
) {
    private var continuation: ((AppResult<SessionStore.ActiveSession>) -> Unit)? = null

    override suspend fun login(
        rawUsername: String,
        password: CharArray,
    ): AppResult<SessionStore.ActiveSession> = suspendCancellableCoroutine { cont ->
        continuation = { result -> cont.resume(result) {} }
    }

    fun resume(result: AppResult<SessionStore.ActiveSession>) {
        continuation?.invoke(result)
        continuation = null
    }
}
