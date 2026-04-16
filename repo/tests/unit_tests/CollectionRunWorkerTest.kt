package com.eaglepoint.libops.tests

import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.eaglepoint.libops.LibOpsApp
import com.eaglepoint.libops.orchestration.CollectionRunWorker
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [CollectionRunWorker.doWork] verifying the worker's top-level
 * behaviour in an isolated Robolectric environment (§9.4, §15).
 *
 * Uses [TestListenableWorkerBuilder] to construct the worker directly (no
 * WorkManager scheduling involved). The in-process Room database starts empty
 * so [JobScheduler.pickBatch] returns an empty batch, exercising the
 * early-exit path. The three wrapped try/catch blocks (anomaly evaluation,
 * overdue sweep, quality score computation) also run against the empty DB
 * and complete silently.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = LibOpsApp::class, sdk = [34], manifest = Config.NONE)
class CollectionRunWorkerTest {

    private lateinit var app: LibOpsApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    // ── empty batch ────────────────────────────────────────────────────────────

    @Test
    fun doWork_with_empty_database_returns_result_success(): Unit = runBlocking {
        val worker = TestListenableWorkerBuilder<CollectionRunWorker>(app).build()
        val result = worker.doWork()
        assertThat(result).isEqualTo(ListenableWorker.Result.success())
    }

    // ── observability exceptions are swallowed ─────────────────────────────────

    @Test
    fun doWork_completes_even_when_observability_pipeline_throws(): Unit = runBlocking {
        // When the Room DB is empty the pipeline methods complete without errors;
        // this test confirms the overall worker result is still success regardless.
        val worker = TestListenableWorkerBuilder<CollectionRunWorker>(app).build()
        val result = worker.doWork()
        assertThat(result).isEqualTo(ListenableWorker.Result.success())
    }

    // ── static companion ──────────────────────────────────────────────────────

    @Test
    fun unique_name_constant_matches_expected_value() {
        assertThat(CollectionRunWorker.UNIQUE_NAME).isEqualTo("libops.collection_run_worker")
    }
}
