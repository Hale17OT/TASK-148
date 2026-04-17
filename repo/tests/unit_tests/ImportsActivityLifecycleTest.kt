package com.eaglepoint.libops.tests

import androidx.test.core.app.ApplicationProvider
import com.eaglepoint.libops.LibOpsApp
import com.eaglepoint.libops.tests.support.TestSessions
import com.eaglepoint.libops.ui.imports.ImportsActivity
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Activity-level gate tests for [ImportsActivity].
 *
 * Note: [ImportsActivity.onCreate] reads `LibOpsApp.signingKeyStore` which is
 * backed by [EncryptedSigningKeyStore] + Android Keystore — unavailable in
 * Robolectric unit tests. We therefore only exercise the GATE path (which
 * runs BEFORE signing-key access). The full launch flow (with signing-key
 * initialization) is validated via the instrumented suite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = LibOpsApp::class, sdk = [34], manifest = Config.NONE)
class ImportsActivityLifecycleTest {

    private lateinit var app: LibOpsApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.sessionStore.clear()
    }

    @Test
    fun imports_activity_finishes_when_no_session() {
        val activity = Robolectric.buildActivity(ImportsActivity::class.java).create().get()
        assertThat(activity.isFinishing).isTrue()
    }

    @Test
    fun imports_activity_finishes_when_session_lacks_imports_run() {
        // Auditor has no IMPORTS_RUN capability
        app.sessionStore.set(TestSessions.auditorSession())
        val activity = Robolectric.buildActivity(ImportsActivity::class.java).create().get()
        assertThat(activity.isFinishing).isTrue()
    }

    @Test
    fun imports_activity_gate_executes_before_signing_key_access() {
        // Verifies the ordering: even without USERS_MANAGE-level session, the
        // gate denies and returns BEFORE any signingKeyStore resolution.
        // (Keystore is unavailable in Robolectric; if ordering regressed,
        // Robolectric would throw KeyStoreException instead of finishing.)
        val activity = Robolectric.buildActivity(ImportsActivity::class.java).create().get()
        assertThat(activity.isFinishing).isTrue()
    }
}
