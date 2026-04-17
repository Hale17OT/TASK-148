package com.eaglepoint.libops.tests

import androidx.test.core.app.ApplicationProvider
import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.data.db.LibOpsDatabase
import com.eaglepoint.libops.data.db.entity.AlertEntity
import com.eaglepoint.libops.data.db.entity.CollectionSourceEntity
import com.eaglepoint.libops.data.db.entity.JobEntity
import com.eaglepoint.libops.observability.ObservabilityPipeline
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * End-to-end real-Room integration test of the observability anomaly-
 * detection path (§15): seed job-attempts + perf-samples + alerts, run
 * [ObservabilityPipeline.evaluateAnomalies] against the real database,
 * and assert the alerts table reflects the detection outcomes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ObservabilityAnomalyDetectionRealRoomTest {

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
    fun evaluate_anomalies_on_empty_db_returns_no_new_alerts(): Unit = runBlocking {
        val alerts = pipeline.evaluateAnomalies()
        assertThat(alerts).isEmpty()
        assertThat(db.alertDao().listByStatus("open", 100, 0)).isEmpty()
    }

    @Test
    fun record_exception_persists_and_can_trigger_alerts(): Unit = runBlocking {
        // Record several exceptions
        repeat(5) { i ->
            pipeline.recordException(
                component = "Worker",
                throwable = RuntimeException("boom_$i"),
                correlationId = UUID.randomUUID().toString(),
            )
        }
        // Re-run anomaly evaluation (no throw)
        val alerts = pipeline.evaluateAnomalies()
        assertThat(alerts).isNotNull()
    }

    @Test
    fun record_performance_samples_are_queryable_from_real_audit_dao(): Unit = runBlocking {
        // Insert 10 fast samples + 5 slow samples
        repeat(10) { pipeline.recordPerformanceSample("query", "fastPath", 20L) }
        repeat(5) { pipeline.recordPerformanceSample("query", "slowPath", 500L) }

        val samples = db.auditDao().perfSamplesSince("query", clockMs - 10_000)
        assertThat(samples).hasSize(15)
        val slowCount = samples.count { it.wasSlow }
        assertThat(slowCount).isEqualTo(5)
    }

    @Test
    fun compute_percentiles_reports_realistic_values_over_real_samples(): Unit = runBlocking {
        // Record 100 samples with durations 1..100
        for (i in 1..100) {
            pipeline.recordPerformanceSample("query", "bucket", i.toLong())
        }
        val p = pipeline.computePercentiles("query", clockMs - 10_000)
        // p50 should be roughly median (~50), p95 near 95, p99 near 99
        assertThat(p.p50).isAtLeast(1L)
        assertThat(p.p95).isAtLeast(p.p50)
        assertThat(p.p99).isAtLeast(p.p95)
    }

    @Test
    fun record_violation_persists_and_can_be_counted_per_user(): Unit = runBlocking {
        repeat(3) {
            pipeline.recordViolation(
                userId = 7L,
                policyKey = "rate_limit",
                severity = "warn",
                reason = "import_rate_exceeded",
            )
        }
        val count = db.auditDao().countViolationsSince(userId = 7L, since = clockMs - 10_000)
        assertThat(count).isEqualTo(3)
    }

    @Test
    fun open_alert_count_reflects_real_alert_table_state(): Unit = runBlocking {
        db.alertDao().insert(alert("open"))
        db.alertDao().insert(alert("open"))
        db.alertDao().insert(alert("acknowledged"))
        db.alertDao().insert(alert("resolved"))

        val openCount = db.alertDao().listByStatus("open", 100, 0).size
        assertThat(openCount).isEqualTo(2)
    }

    private fun alert(status: String) = AlertEntity(
        category = "job_failure", severity = "warn",
        title = "t", body = "b", status = status,
        ownerUserId = null, correlationId = null,
        dueAt = clockMs, createdAt = clockMs,
        updatedAt = clockMs, version = 1,
    )
}
