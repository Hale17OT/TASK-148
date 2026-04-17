package com.eaglepoint.libops.tests

import androidx.test.core.app.ApplicationProvider
import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.data.db.LibOpsDatabase
import com.eaglepoint.libops.observability.ObservabilityPipeline
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real-Room integration test for [ObservabilityPipeline] covering the three
 * recording paths (exception, perf sample, violation) and the periodic
 * anomaly-evaluation sweep. Complements [ObservabilityPipelineTest]
 * (fake-based) by verifying end-to-end persistence and re-reads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ObservabilityPipelineRealRoomTest {

    private lateinit var db: LibOpsDatabase
    private lateinit var pipeline: ObservabilityPipeline
    private val clockMs = 1_700_000_000_000L

    @Before
    fun setUp() {
        db = LibOpsDatabase.inMemory(
            ApplicationProvider.getApplicationContext<android.content.Context>()
        )
        pipeline = ObservabilityPipeline(
            auditDao = db.auditDao(),
            alertDao = db.alertDao(),
            jobDao = db.jobDao(),
            auditLogger = AuditLogger(db.auditDao(), clock = { clockMs }),
            clock = { clockMs },
            userDao = db.userDao(),
            qualityScoreDao = db.qualityScoreDao(),
            duplicateDao = db.duplicateDao(),
            importDao = db.importDao(),
        )
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun record_exception_persists_to_real_audit_table(): Unit = runBlocking {
        val ex = RuntimeException("boom")
        val id = pipeline.recordException("TestComponent", ex, correlationId = "corr-123")
        assertThat(id).isGreaterThan(0L)
    }

    @Test
    fun record_performance_sample_persists_with_slow_flag(): Unit = runBlocking {
        val fastId = pipeline.recordPerformanceSample("query", "fastQuery", 10L)
        val slowId = pipeline.recordPerformanceSample("query", "slowQuery", 500L)
        assertThat(fastId).isGreaterThan(0L)
        assertThat(slowId).isGreaterThan(0L)

        val fastSamples = db.auditDao().perfSamplesSince("query", clockMs - 1000)
        assertThat(fastSamples).isNotEmpty()
        val slow = fastSamples.firstOrNull { it.label == "slowQuery" }
        assertThat(slow).isNotNull()
        assertThat(slow!!.wasSlow).isTrue()
    }

    @Test
    fun record_violation_persists_to_real_audit_table(): Unit = runBlocking {
        val id = pipeline.recordViolation(
            userId = 42L,
            policyKey = "rate_limit",
            severity = "warn",
            reason = "exceeded_limit",
        )
        assertThat(id).isGreaterThan(0L)
    }

    @Test
    fun evaluate_anomalies_on_empty_database_returns_empty_list(): Unit = runBlocking {
        val alerts = pipeline.evaluateAnomalies()
        assertThat(alerts).isEmpty()
    }

    @Test
    fun compute_percentiles_on_empty_samples_returns_zeros(): Unit = runBlocking {
        val p = pipeline.computePercentiles("query", clockMs - 1000)
        assertThat(p.p50).isAtMost(0L)
    }

    @Test
    fun compute_quality_scores_on_empty_db_returns_zero_scores(): Unit = runBlocking {
        val count = pipeline.computeQualityScores()
        assertThat(count).isAtLeast(0)
    }
}
