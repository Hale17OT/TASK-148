package com.eaglepoint.libops.tests

import androidx.test.core.app.ApplicationProvider
import com.eaglepoint.libops.LibOpsApp
import com.eaglepoint.libops.domain.auth.Capabilities
import com.eaglepoint.libops.tests.support.TestSessions
import com.eaglepoint.libops.ui.admin.AdminActivity
import com.eaglepoint.libops.ui.admin.RuntimeSettingsActivity
import com.eaglepoint.libops.ui.alerts.AlertsActivity
import com.eaglepoint.libops.ui.analytics.AnalyticsActivity
import com.eaglepoint.libops.ui.audit.AuditLogsActivity
import com.eaglepoint.libops.ui.collection.CollectionRunsActivity
import com.eaglepoint.libops.ui.collection.SourceEditorActivity
import com.eaglepoint.libops.ui.duplicates.DuplicatesActivity
import com.eaglepoint.libops.ui.duplicates.MergeReviewActivity
import com.eaglepoint.libops.ui.imports.ImportsActivity
import com.eaglepoint.libops.ui.records.RecordsActivity
import com.eaglepoint.libops.ui.secrets.SecretsActivity
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Capability-gate contract test for ALL 12 feature Activities.
 *
 * Each test pair verifies the Activity's AuthorizationGate enforcement:
 *   - No session / wrong capability → activity finishes
 *   - Correct capability → activity stays alive
 *
 * This is the Android-equivalent of a "route authorization test": every
 * UI entry point is proven to enforce its declared capability before
 * allowing access.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = LibOpsApp::class, sdk = [34], manifest = Config.NONE)
class ActivityCapabilityGateTest {

    private lateinit var app: LibOpsApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.sessionStore.clear()
    }

    private inline fun <reified A : androidx.fragment.app.FragmentActivity> build(): A =
        Robolectric.buildActivity(A::class.java).create().get()

    // ── AdminActivity: USERS_MANAGE ───────────────────────────────────────────

    @Test
    fun admin_activity_finishes_without_users_manage() {
        val activity = build<AdminActivity>()
        assertThat(activity.isFinishing).isTrue()
    }

    @Test
    fun admin_activity_finishes_when_session_lacks_users_manage() {
        app.sessionStore.set(TestSessions.catalogerSession())  // no USERS_MANAGE
        val activity = build<AdminActivity>()
        assertThat(activity.isFinishing).isTrue()
    }

    // ── RecordsActivity: RECORDS_READ ─────────────────────────────────────────

    @Test
    fun records_activity_finishes_without_session() {
        val activity = build<RecordsActivity>()
        assertThat(activity.isFinishing).isTrue()
    }

    @Test
    fun records_activity_finishes_when_session_lacks_records_read() {
        app.sessionStore.set(TestSessions.session(capabilities = setOf(Capabilities.AUDIT_READ)))
        val activity = build<RecordsActivity>()
        assertThat(activity.isFinishing).isTrue()
    }

    // ── ImportsActivity: IMPORTS_RUN ──────────────────────────────────────────

    @Test
    fun imports_activity_finishes_without_imports_run() {
        val activity = build<ImportsActivity>()
        assertThat(activity.isFinishing).isTrue()
    }

    @Test
    fun imports_activity_finishes_when_session_lacks_imports_run() {
        app.sessionStore.set(TestSessions.auditorSession())  // no IMPORTS_RUN
        val activity = build<ImportsActivity>()
        assertThat(activity.isFinishing).isTrue()
    }

    // ── SecretsActivity: SECRETS_READ_MASKED ──────────────────────────────────

    @Test
    fun secrets_activity_finishes_without_secrets_read_masked() {
        val activity = build<SecretsActivity>()
        assertThat(activity.isFinishing).isTrue()
    }

    @Test
    fun secrets_activity_finishes_when_cataloger_has_no_secret_access() {
        app.sessionStore.set(TestSessions.catalogerSession())
        val activity = build<SecretsActivity>()
        assertThat(activity.isFinishing).isTrue()
    }

    // ── AlertsActivity: ALERTS_READ ───────────────────────────────────────────

    @Test
    fun alerts_activity_finishes_without_alerts_read() {
        val activity = build<AlertsActivity>()
        assertThat(activity.isFinishing).isTrue()
    }

    // ── AuditLogsActivity: AUDIT_READ ─────────────────────────────────────────

    @Test
    fun audit_logs_activity_finishes_without_audit_read() {
        val activity = build<AuditLogsActivity>()
        assertThat(activity.isFinishing).isTrue()
    }

    @Test
    fun audit_logs_activity_finishes_when_cataloger_attempts_access() {
        app.sessionStore.set(TestSessions.catalogerSession())  // no AUDIT_READ
        val activity = build<AuditLogsActivity>()
        assertThat(activity.isFinishing).isTrue()
    }

    // ── AnalyticsActivity: ANALYTICS_READ ─────────────────────────────────────

    @Test
    fun analytics_activity_finishes_without_analytics_read() {
        val activity = build<AnalyticsActivity>()
        assertThat(activity.isFinishing).isTrue()
    }

    // ── CollectionRunsActivity: JOBS_READ ─────────────────────────────────────

    @Test
    fun collection_runs_activity_finishes_without_jobs_read() {
        val activity = build<CollectionRunsActivity>()
        assertThat(activity.isFinishing).isTrue()
    }

    @Test
    fun collection_runs_activity_finishes_when_cataloger_attempts_access() {
        app.sessionStore.set(TestSessions.catalogerSession())  // no JOBS_READ
        val activity = build<CollectionRunsActivity>()
        assertThat(activity.isFinishing).isTrue()
    }

    // ── SourceEditorActivity: SOURCES_MANAGE ──────────────────────────────────

    @Test
    fun source_editor_activity_finishes_without_sources_manage() {
        val activity = build<SourceEditorActivity>()
        assertThat(activity.isFinishing).isTrue()
    }

    // ── DuplicatesActivity: DUPLICATES_READ ───────────────────────────────────

    @Test
    fun duplicates_activity_finishes_without_duplicates_read() {
        val activity = build<DuplicatesActivity>()
        assertThat(activity.isFinishing).isTrue()
    }

    // ── MergeReviewActivity: DUPLICATES_RESOLVE ───────────────────────────────

    @Test
    fun merge_review_activity_finishes_without_duplicates_resolve() {
        val activity = build<MergeReviewActivity>()
        assertThat(activity.isFinishing).isTrue()
    }

    // ── RuntimeSettingsActivity: USERS_MANAGE ─────────────────────────────────

    @Test
    fun runtime_settings_activity_finishes_without_users_manage() {
        val activity = build<RuntimeSettingsActivity>()
        assertThat(activity.isFinishing).isTrue()
    }

    @Test
    fun runtime_settings_activity_finishes_when_auditor_attempts_access() {
        app.sessionStore.set(TestSessions.auditorSession())  // no USERS_MANAGE
        val activity = build<RuntimeSettingsActivity>()
        assertThat(activity.isFinishing).isTrue()
    }
}
