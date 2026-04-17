package com.eaglepoint.libops.tests

import androidx.test.core.app.ApplicationProvider
import com.eaglepoint.libops.analytics.AnalyticsRepository
import com.eaglepoint.libops.data.db.LibOpsDatabase
import com.eaglepoint.libops.data.db.entity.AlertEntity
import com.eaglepoint.libops.data.db.entity.CollectionSourceEntity
import com.eaglepoint.libops.data.db.entity.DuplicateCandidateEntity
import com.eaglepoint.libops.data.db.entity.ImportBatchEntity
import com.eaglepoint.libops.data.db.entity.JobEntity
import com.eaglepoint.libops.data.db.entity.MasterRecordEntity
import com.eaglepoint.libops.data.db.entity.QualityScoreSnapshotEntity
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
 * Real-Room integration test mirroring operations performed by
 * [com.eaglepoint.libops.ui.analytics.AnalyticsActivity] (§16).
 *
 * AnalyticsActivity is read-only: onCreate → AnalyticsRepository.compute().
 * This test seeds every table the dashboard aggregates from and asserts the
 * end-to-end KPI computation produces the expected snapshot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AnalyticsDashboardIntegrationTest {

    private lateinit var db: LibOpsDatabase
    private lateinit var repo: AnalyticsRepository
    private val clockMs = 1_700_000_000_000L

    @Before
    fun setUp() {
        db = LibOpsDatabase.inMemory(
            ApplicationProvider.getApplicationContext<android.content.Context>()
        )
        repo = AnalyticsRepository(db, clock = { clockMs })
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun full_dashboard_snapshot_aggregates_all_tables_correctly(): Unit = runBlocking {
        // 1 active source + 1 disabled + 1 archived (archived excluded)
        val activeSrc = db.collectionSourceDao().insert(source("src_active", "active"))
        db.collectionSourceDao().insert(source("src_disabled", "disabled"))
        db.collectionSourceDao().insert(source("src_archived", "archived"))

        // Jobs: 2 queued, 1 scheduled, 1 succeeded, 1 terminal_failed
        listOf("queued", "queued", "scheduled", "succeeded", "terminal_failed").forEach { s ->
            db.jobDao().insert(
                JobEntity(
                    sourceId = activeSrc, status = s, priority = 3,
                    refreshMode = "full", correlationId = UUID.randomUUID().toString(),
                    scheduledAt = clockMs, startedAt = null, finishedAt = null,
                    lastError = null, createdAt = clockMs, updatedAt = clockMs,
                ),
            )
        }

        // Records: 4 active
        repeat(4) { i ->
            db.recordDao().insert(
                MasterRecordEntity(
                    title = "r$i", titleNormalized = "r$i",
                    publisher = null, pubDate = null, format = "paperback",
                    category = "book", isbn10 = null, isbn13 = null,
                    language = null, notes = null, status = "active",
                    sourceProvenanceJson = null, createdByUserId = 1L,
                    createdAt = clockMs, updatedAt = clockMs,
                ),
            )
        }

        // Alerts: 1 open, 2 overdue, 1 resolved
        listOf("open", "overdue", "overdue", "resolved").forEach { s ->
            db.alertDao().insert(
                AlertEntity(
                    category = "job_failure", severity = "warn",
                    title = "t", body = "b", status = s,
                    ownerUserId = null, correlationId = null,
                    dueAt = clockMs, createdAt = clockMs,
                    updatedAt = clockMs, version = 1,
                ),
            )
        }

        // Duplicates: 2 unresolved
        repeat(2) {
            db.duplicateDao().insert(
                DuplicateCandidateEntity(
                    primaryRecordId = 1L, candidateRecordId = null,
                    primaryStagingRef = null, candidateStagingRef = "batch=1",
                    score = 0.9, algorithm = "jaro_winkler",
                    status = "detected", detectedAt = clockMs - 1000,
                    reviewedByUserId = null, reviewedAt = null,
                ),
            )
        }

        // Quality scores: avg = 82
        db.qualityScoreDao().insert(QualityScoreSnapshotEntity(
            userId = 1L, score = 80,
            validationFailures30d = 0, policyViolations30d = 0,
            overdueAlerts30d = 0, unresolvedDuplicates7d = 0,
            capturedAt = clockMs,
        ))
        db.qualityScoreDao().insert(QualityScoreSnapshotEntity(
            userId = 2L, score = 84,
            validationFailures30d = 0, policyViolations30d = 0,
            overdueAlerts30d = 0, unresolvedDuplicates7d = 0,
            capturedAt = clockMs,
        ))

        // Import batches: 2 batches, 30 total rows, 5 rejected
        db.importDao().insertBatch(ImportBatchEntity(
            bundleId = null, filename = "a", format = "csv",
            totalRows = 10, acceptedRows = 9, rejectedRows = 1,
            state = "completed", createdByUserId = 1L,
            createdAt = clockMs, completedAt = clockMs,
        ))
        db.importDao().insertBatch(ImportBatchEntity(
            bundleId = null, filename = "b", format = "csv",
            totalRows = 20, acceptedRows = 16, rejectedRows = 4,
            state = "completed", createdByUserId = 1L,
            createdAt = clockMs, completedAt = clockMs,
        ))

        // Compute the dashboard
        val d = repo.compute()

        // Verify every KPI
        assertThat(d.configuredSources).isEqualTo(2)    // active + disabled (archived excluded)
        assertThat(d.queuedJobs).isEqualTo(3)           // 2 queued + 1 scheduled
        assertThat(d.processedJobsLast24h).isEqualTo(2) // succeeded + terminal_failed
        assertThat(d.acceptedRecords).isEqualTo(4)
        assertThat(d.openAlerts).isEqualTo(1)
        assertThat(d.overdueAlerts).isEqualTo(2)
        assertThat(d.duplicatesPending).isEqualTo(2)
        assertThat(d.avgQualityScore).isEqualTo(82)
        assertThat(d.acquisitionSpend).isEqualTo(2)     // batch count
        assertThat(d.itemsOrdered).isEqualTo(30)
        assertThat(d.itemsReturned).isEqualTo(5)
    }

    @Test
    fun dashboard_reflects_changes_after_inserting_new_rows(): Unit = runBlocking {
        val before = repo.compute()
        assertThat(before.acceptedRecords).isEqualTo(0)

        db.recordDao().insert(
            MasterRecordEntity(
                title = "t", titleNormalized = "t",
                publisher = null, pubDate = null, format = "paperback",
                category = "book", isbn10 = null, isbn13 = null,
                language = null, notes = null, status = "active",
                sourceProvenanceJson = null, createdByUserId = 1L,
                createdAt = clockMs, updatedAt = clockMs,
            ),
        )

        val after = repo.compute()
        assertThat(after.acceptedRecords).isEqualTo(1)
    }

    private fun source(name: String, state: String) = CollectionSourceEntity(
        name = name, entryType = "imported_file", refreshMode = "full",
        priority = 3, retryBackoff = "5_min", enabled = true,
        state = state, scheduleCron = null,
        createdAt = clockMs, updatedAt = clockMs,
    )
}
