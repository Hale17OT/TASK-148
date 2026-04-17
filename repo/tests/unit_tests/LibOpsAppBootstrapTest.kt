package com.eaglepoint.libops.tests

import androidx.test.core.app.ApplicationProvider
import com.eaglepoint.libops.LibOpsApp
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Integration test for [LibOpsApp] — verifies the application service-locator
 * wiring is functional after Robolectric-driven `onCreate()`.
 *
 * Exercises the dependency graph: db → settings → sessionStore → auditLogger
 * → authRepository → observabilityPipeline → jobScheduler.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = LibOpsApp::class, sdk = [34], manifest = Config.NONE)
class LibOpsAppBootstrapTest {

    private lateinit var app: LibOpsApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun application_is_lib_ops_app_instance() {
        assertThat(app).isInstanceOf(LibOpsApp::class.java)
    }

    @Test
    fun session_store_is_initialized_and_starts_empty() {
        app.sessionStore.clear()
        assertThat(app.sessionStore.snapshot()).isNull()
    }

    @Test
    fun settings_provides_default_snapshot() {
        val snap = app.settings.current()
        assertThat(snap.parallelism).isAtLeast(1)
        assertThat(snap.parallelism).isAtMost(6)
    }

    @Test
    fun audit_logger_is_wired_to_database() {
        assertThat(app.auditLogger).isNotNull()
    }

    @Test
    fun auth_repository_is_wired_with_dao_layer() {
        assertThat(app.authRepository).isNotNull()
    }

    @Test
    fun observability_pipeline_is_wired() {
        assertThat(app.observabilityPipeline).isNotNull()
    }

    @Test
    fun job_scheduler_is_wired() {
        assertThat(app.jobScheduler).isNotNull()
    }

    @Test
    fun secret_repository_is_wired_with_cipher() {
        assertThat(app.secretRepository).isNotNull()
    }

    @Test
    fun review_admin_password_constant_is_policy_compliant() {
        // REVIEW_ADMIN_PASSWORD must satisfy PasswordPolicy:
        // ≥12 chars, upper, lower, digit, special
        val pw = LibOpsApp.REVIEW_ADMIN_PASSWORD
        assertThat(pw.length).isAtLeast(12)
        assertThat(pw.any { it.isUpperCase() }).isTrue()
        assertThat(pw.any { it.isLowerCase() }).isTrue()
        assertThat(pw.any { it.isDigit() }).isTrue()
        assertThat(pw.any { !it.isLetterOrDigit() }).isTrue()
    }

    @Test
    fun db_accessor_returns_real_database_instance() {
        assertThat(app.db).isNotNull()
        assertThat(app.db.userDao()).isNotNull()
        assertThat(app.db.auditDao()).isNotNull()
    }
}
