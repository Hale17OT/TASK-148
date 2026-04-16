package com.eaglepoint.libops.tests

import android.content.Context
import androidx.room.Room
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

/**
 * Tests for [AnalyticsRepository.compute] using an in-memory Room database
 * (via Robolectric). Verifies that each KPI field is derived correctly from
 * the transactional tables it reads (§16).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AnalyticsRepositoryTest {

    private lateinit var db: LibOpsDatabase
    private lateinit var repo: AnalyticsRepository
    private var clockMs = 1_700_000_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LibOpsDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = AnalyticsRepository(db, clock = { clockMs })
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun compute_returns_baseline_values_on_empty_database() = runBlocking {
        val d = repo.compute()

        assertThat(d.configuredSources).isEqualTo(0)
        assertThat(d.queuedJobs).isEqualTo(0)
        assertThat(d.processedJobsLast24h).isEqualTo(0)
        assertThat(d.acceptedRecords).isEqualTo(0)
        assertThat(d.openAlerts).isEqualTo(0)
        assertThat(d.overdueAlerts).isEqualTo(0)
        assertThat(d.duplicatesPending).isEqualTo(0)
        assertThat(d.avgQualityScore).isEqualTo(100) // baseline: no data → 100
        assertThat(d.acquisitionSpend).isEqualTo(0)
        assertThat(d.itemsOrdered).isEqualTo(0)
        assertThat(d.itemsReturned).isEqualTo(0)
    }

    @Test
    fun compute_counts_active_records_and_configured_sources() = runBlocking {
        // Active + draft + disabled sources are "configured"
        insertSource("src-1", state = "active")
        insertSource("src-2", state = "draft")
        insertSource("src-3", state = "disabled")
        insertSource("src-4", state = "archived") // excluded from configuredSources

        // Active records
        insertRecord("Title A", status = "active")
        insertRecord("Title B", status = "active")
        insertRecord("Title C", status = "draft") // not counted as accepted

        val d = repo.compute()

        assertThat(d.configuredSources).isEqualTo(3) // active + draft + disabled
        assertThat(d.acceptedRecords).isEqualTo(2)   // only 'active' records
    }

    @Test
    fun compute_counts_open_and_overdue_alerts() = runBlocking {
        insertAlert(status = "open")
        insertAlert(status = "open")
        insertAlert(status = "overdue")
        insertAlert(status = "resolved") // excluded

        val d = repo.compute()

        assertThat(d.openAlerts).isEqualTo(2)
        assertThat(d.overdueAlerts).isEqualTo(1)
    }

    @Test
    fun compute_counts_queued_jobs_and_import_kpis() = runBlocking {
        val srcId = insertSource("src-main", state = "active")

        // queued + scheduled = queuedJobs
        insertJob(srcId, status = "queued")
        insertJob(srcId, status = "scheduled")
        // succeeded + terminal_failed = processedJobsLast24h
        insertJob(srcId, status = "succeeded")
        insertJob(srcId, status = "terminal_failed")

        // Import KPIs
        insertBatch(totalRows = 100, acceptedRows = 80, rejectedRows = 20)
        insertBatch(totalRows = 50, acceptedRows = 50, rejectedRows = 0)

        val d = repo.compute()

        assertThat(d.queuedJobs).isEqualTo(2)          // queued + scheduled
        assertThat(d.processedJobsLast24h).isEqualTo(2) // succeeded + terminal_failed
        assertThat(d.acquisitionSpend).isEqualTo(2)    // 2 batches
        assertThat(d.itemsOrdered).isEqualTo(150)      // 100 + 50 total rows
        assertThat(d.itemsReturned).isEqualTo(20)      // 20 + 0 rejected
    }

    @Test
    fun compute_averages_quality_scores_and_counts_unresolved_duplicates() = runBlocking {
        // Quality score average
        insertQualityScore(score = 80)
        insertQualityScore(score = 60)

        // Unresolved duplicates detected before clock
        insertDuplicate(status = "detected", detectedAt = clockMs - 1_000L)
        insertDuplicate(status = "under_review", detectedAt = clockMs - 1_000L)
        insertDuplicate(status = "merged", detectedAt = clockMs - 1_000L) // excluded

        val d = repo.compute()

        assertThat(d.avgQualityScore).isEqualTo(70) // (80 + 60) / 2
        assertThat(d.duplicatesPending).isEqualTo(2) // detected + under_review
    }

    @Test
    fun slaBreached_returns_false_for_recent_alert_and_true_after_sla() {
        val oneSecondAgo = clockMs - 1_000L
        assertThat(repo.slaBreached(oneSecondAgo)).isFalse()

        // 7 days + 1ms past the SLA
        val eightDaysAgo = clockMs - (8L * 24 * 60 * 60 * 1_000L)
        assertThat(repo.slaBreached(eightDaysAgo)).isTrue()
    }

    // ── Insert helpers ────────────────────────────────────────────────────────

    private suspend fun insertSource(name: String, state: String): Long =
        db.collectionSourceDao().insert(
            CollectionSourceEntity(
                name = name,
                entryType = "imported_file",
                refreshMode = "incremental",
                priority = 3,
                retryBackoff = "5_min",
                enabled = true,
                state = state,
                scheduleCron = null,
                createdAt = clockMs,
                updatedAt = clockMs,
            )
        )

    private suspend fun insertRecord(title: String, status: String): Long =
        db.recordDao().insert(
            MasterRecordEntity(
                title = title,
                titleNormalized = title.lowercase(),
                publisher = null,
                pubDate = null,
                format = null,
                category = "book",
                isbn10 = null,
                isbn13 = null,
                language = null,
                notes = null,
                status = status,
                sourceProvenanceJson = null,
                createdByUserId = 1L,
                createdAt = clockMs,
                updatedAt = clockMs,
            )
        )

    private suspend fun insertAlert(status: String): Long =
        db.alertDao().insert(
            AlertEntity(
                category = "job_failure",
                severity = "warn",
                title = "Test alert",
                body = "Test body",
                status = status,
                ownerUserId = null,
                correlationId = null,
                dueAt = clockMs + 3_600_000L,
                createdAt = clockMs,
                updatedAt = clockMs,
            )
        )

    private suspend fun insertJob(sourceId: Long, status: String): Long =
        db.jobDao().insert(
            JobEntity(
                sourceId = sourceId,
                status = status,
                priority = 3,
                refreshMode = "incremental",
                correlationId = "corr-$status",
                scheduledAt = clockMs,
                startedAt = null,
                finishedAt = null,
                lastError = null,
                createdAt = clockMs,
                updatedAt = clockMs,
            )
        )

    private suspend fun insertBatch(totalRows: Int, acceptedRows: Int, rejectedRows: Int): Long =
        db.importDao().insertBatch(
            ImportBatchEntity(
                bundleId = null,
                filename = "test.csv",
                format = "csv",
                totalRows = totalRows,
                acceptedRows = acceptedRows,
                rejectedRows = rejectedRows,
                state = "completed",
                createdByUserId = 1L,
                createdAt = clockMs,
                completedAt = clockMs,
            )
        )

    private suspend fun insertQualityScore(score: Int): Long =
        db.qualityScoreDao().insert(
            QualityScoreSnapshotEntity(
                userId = 1L,
                score = score,
                validationFailures30d = 0,
                policyViolations30d = 0,
                overdueAlerts30d = 0,
                unresolvedDuplicates7d = 0,
                capturedAt = clockMs,
            )
        )

    private suspend fun insertDuplicate(status: String, detectedAt: Long): Long =
        db.duplicateDao().insert(
            DuplicateCandidateEntity(
                primaryRecordId = null,
                candidateRecordId = null,
                primaryStagingRef = null,
                candidateStagingRef = null,
                score = 0.9,
                algorithm = "isbn13",
                status = status,
                detectedAt = detectedAt,
                reviewedByUserId = null,
                reviewedAt = null,
            )
        )
}
