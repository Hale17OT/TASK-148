package com.eaglepoint.libops.tests

import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.eaglepoint.libops.LibOpsApp
import com.eaglepoint.libops.auth.SessionStore
import com.eaglepoint.libops.domain.auth.Capabilities
import com.eaglepoint.libops.domain.auth.Roles
import com.eaglepoint.libops.ui.AuthorizationGate
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises [AuthorizationGate.requireAccess] and [AuthorizationGate.revalidateSession]
 * with a real [FragmentActivity] backed by [LibOpsApp], verifying the synchronous
 * return value and [FragmentActivity.isFinishing] side-effects (§9.2, §11).
 *
 * Uses [AuthGateTestActivity] — a minimal [FragmentActivity] subclass — so that
 * tests pull in no feature-specific layouts or business logic. The async
 * DB/audit operations inside [AuthorizationGate] are dispatched on
 * [LibOpsApp]'s [SupervisorJob] and do not affect synchronous assertions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = LibOpsApp::class, sdk = [34], manifest = Config.NONE)
class AuthorizationGateRobolectricTest {

    private lateinit var app: LibOpsApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.sessionStore.clear()
    }

    private fun fakeSession(
        capabilities: Set<String> = setOf(Capabilities.RECORDS_READ),
        role: String = Roles.COLLECTION_MANAGER,
        lastActiveMillis: Long = System.currentTimeMillis(),
    ) = SessionStore.ActiveSession(
        sessionId = 1L, userId = 1L, username = "testuser",
        activeRoleName = role, capabilities = capabilities,
        authenticatedAt = lastActiveMillis, lastActiveMillis = lastActiveMillis,
        biometricEnabled = false,
    )

    private fun buildActivity(): AuthGateTestActivity =
        Robolectric.buildActivity(AuthGateTestActivity::class.java).create().start().resume().get()

    // ── requireAccess ──────────────────────────────────────────────────────────

    @Test
    fun no_session_returns_null_and_finishes_activity() {
        // sessionStore is empty (setUp cleared it)
        val activity = buildActivity()
        val result = AuthorizationGate.requireAccess(activity, Capabilities.RECORDS_READ)
        assertThat(result).isNull()
        assertThat(activity.isFinishing).isTrue()
    }

    @Test
    fun valid_session_with_required_capability_returns_session() {
        app.sessionStore.set(fakeSession(capabilities = setOf(Capabilities.RECORDS_READ)))
        val activity = buildActivity()

        val result = AuthorizationGate.requireAccess(activity, Capabilities.RECORDS_READ)

        assertThat(result).isNotNull()
        assertThat(result!!.username).isEqualTo("testuser")
        assertThat(activity.isFinishing).isFalse()
    }

    @Test
    fun session_missing_required_capability_returns_null_and_finishes_activity() {
        // Session only has RECORDS_READ but gate requires USERS_MANAGE
        app.sessionStore.set(fakeSession(capabilities = setOf(Capabilities.RECORDS_READ)))
        val activity = buildActivity()

        val result = AuthorizationGate.requireAccess(activity, Capabilities.USERS_MANAGE)

        assertThat(result).isNull()
        assertThat(activity.isFinishing).isTrue()
    }

    @Test
    fun expired_session_returns_null_clears_store_and_finishes_activity() {
        // Last active 20 minutes ago — exceeds standard 15-minute timeout
        val staleMs = System.currentTimeMillis() - 20L * 60_000L
        app.sessionStore.set(
            fakeSession(role = Roles.COLLECTION_MANAGER, lastActiveMillis = staleMs)
        )
        val activity = buildActivity()

        val result = AuthorizationGate.requireAccess(activity, Capabilities.RECORDS_READ)

        assertThat(result).isNull()
        assertThat(app.sessionStore.snapshot()).isNull()
        assertThat(activity.isFinishing).isTrue()
    }

    @Test
    fun privileged_role_expired_after_ten_minutes_returns_null() {
        // Admin/auditor timeout is 10 min — 11 minutes idle should expire
        val staleMs = System.currentTimeMillis() - 11L * 60_000L
        app.sessionStore.set(
            fakeSession(
                role = Roles.ADMIN,
                capabilities = Roles.DEFAULT_MAPPING[Roles.ADMIN]!!,
                lastActiveMillis = staleMs,
            )
        )
        val activity = buildActivity()

        val result = AuthorizationGate.requireAccess(activity, Capabilities.USERS_MANAGE)

        assertThat(result).isNull()
        assertThat(app.sessionStore.snapshot()).isNull()
    }

    // ── revalidateSession ──────────────────────────────────────────────────────

    @Test
    fun revalidate_no_session_returns_null_and_finishes_activity() {
        val activity = buildActivity()
        val result = AuthorizationGate.revalidateSession(activity)
        assertThat(result).isNull()
        assertThat(activity.isFinishing).isTrue()
    }

    @Test
    fun revalidate_valid_session_with_no_capability_check_returns_session() {
        app.sessionStore.set(fakeSession())
        val activity = buildActivity()

        val result = AuthorizationGate.revalidateSession(activity, requiredCapability = null)

        assertThat(result).isNotNull()
    }

    @Test
    fun revalidate_session_with_required_capability_returns_session() {
        app.sessionStore.set(fakeSession(capabilities = setOf(Capabilities.RECORDS_READ)))
        val activity = buildActivity()

        val result = AuthorizationGate.revalidateSession(activity, requiredCapability = Capabilities.RECORDS_READ)

        assertThat(result).isNotNull()
    }

    @Test
    fun revalidate_missing_capability_returns_null_but_does_not_finish_activity() {
        // revalidateSession denies the action but does NOT call activity.finish()
        app.sessionStore.set(fakeSession(capabilities = setOf(Capabilities.RECORDS_READ)))
        val activity = buildActivity()

        val result = AuthorizationGate.revalidateSession(activity, requiredCapability = Capabilities.USERS_MANAGE)

        assertThat(result).isNull()
        assertThat(activity.isFinishing).isFalse()
    }
}

/**
 * Minimal [FragmentActivity] used as the host for [AuthorizationGate] calls.
 * Requires no layout or lifecycle logic — only a [FragmentActivity] context
 * that has [LibOpsApp] as its [android.app.Application].
 */
class AuthGateTestActivity : FragmentActivity()
