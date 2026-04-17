package com.eaglepoint.libops.tests

import androidx.test.core.app.ApplicationProvider
import com.eaglepoint.libops.analytics.AnalyticsRepository
import com.eaglepoint.libops.data.db.LibOpsDatabase
import com.eaglepoint.libops.data.db.entity.AlertEntity
import com.eaglepoint.libops.data.db.entity.CollectionSourceEntity
import com.eaglepoint.libops.data.db.entity.ImportBatchEntity
import com.eaglepoint.libops.data.db.entity.JobEntity
import com.eaglepoint.libops.data.db.entity.MasterRecordEntity
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
 * Real-Room integration test for [AnalyticsRepository.compute] — validates
 * the dashboard KPI derivation against actual SQL queries across 6 DAOs
 * (§16). Complements [AnalyticsRepositoryTest] (fake-based) by proving
 * the real aggregate queries work.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AnalyticsRepositoryRealRoomTest {

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
    fun empty_db_produces_baseline_dashboard(): Unit = runBlocking {
        val d = repo.compute()
        assertThat(d.configuredSources).isEqualTo(0)
        assertThat(d.queuedJobs).isEqualTo(0)
        assertThat(d.acceptedRecords).isEqualTo(0)
        assertThat(d.openAlerts).isEqualTo(0)
        assertThat(d.overdueAlerts).isEqualTo(0)
        assertThat(d.avgQualityScore).isEqualTo(100)  // baseline
    }

    @Test
    fun configured_sources_counts_active_draft_disabled_but_not_archived(): Unit = runBlocking {
        listOf("active", "draft", "disabled", "archived").forEach { state ->
            db.collectionSourceDao().insert(
                CollectionSourceEntity(
                    name = "src_$state", entryType = "imported_file",
                    refreshMode = "full", priority = 3, retryBackoff = "5_min",
                    enabled = true, state = state, scheduleCron = null,
                    createdAt = clockMs, updatedAt = clockMs,
                ),
            )
        }
        val d = repo.compute()
        assertThat(d.configuredSources).isEqualTo(3)  // archived excluded
    }

    @Test
    fun queued_jobs_sums_queued_and_scheduled_states(): Unit = runBlocking {
        val sourceId = db.collectionSourceDao().insert(
            CollectionSourceEntity(
                name = "test", entryType = "imported_file",
                refreshMode = "full", priority = 3, retryBackoff = "5_min",
                enabled = true, state = "active", scheduleCron = null,
                createdAt = clockMs, updatedAt = clockMs,
            ),
        )
        listOf("queued", "queued", "scheduled", "running", "succeeded").forEach { status ->
            db.jobDao().insert(
                JobEntity(
                    sourceId = sourceId, status = status, priority = 3,
                    refreshMode = "full", correlationId = UUID.randomUUID().toString(),
                    scheduledAt = clockMs, startedAt = null, finishedAt = null,
                    lastError = null, createdAt = clockMs, updatedAt = clockMs,
                ),
            )
        }
        val d = repo.compute()
        assertThat(d.queuedJobs).isEqualTo(3)  // 2 queued + 1 scheduled
    }

    @Test
    fun accepted_records_reflects_active_count(): Unit = runBlocking {
        repeat(5) { i ->
            db.recordDao().insert(
                MasterRecordEntity(
                    title = "Book $i", titleNormalized = "book $i",
                    publisher = null, pubDate = null, format = "paperback",
                    category = "book", isbn10 = null, isbn13 = null,
                    language = null, notes = null, status = "active",
                    sourceProvenanceJson = null, createdByUserId = 1L,
                    createdAt = clockMs, updatedAt = clockMs,
                ),
            )
        }
        val d = repo.compute()
        assertThat(d.acceptedRecords).isEqualTo(5)
    }

    @Test
    fun open_and_overdue_alerts_counted_from_real_alert_dao(): Unit = runBlocking {
        val alertStates = listOf("open", "open", "overdue", "overdue", "overdue", "resolved")
        alertStates.forEach { status ->
            db.alertDao().insert(
                AlertEntity(
                    category = "job_failure", severity = "warn",
                    title = "t", body = "b", status = status,
                    ownerUserId = null, correlationId = null,
                    dueAt = clockMs, createdAt = clockMs,
                    updatedAt = clockMs, version = 1,
                ),
            )
        }
        val d = repo.compute()
        assertThat(d.openAlerts).isEqualTo(2)
        assertThat(d.overdueAlerts).isEqualTo(3)
    }

    @Test
    fun acquisition_kpis_sum_import_batches(): Unit = runBlocking {
        db.importDao().insertBatch(
            ImportBatchEntity(
                bundleId = null, filename = "a.csv", format = "csv",
                totalRows = 10, acceptedRows = 8, rejectedRows = 2,
                state = "completed", createdByUserId = 1L,
                createdAt = clockMs, completedAt = clockMs,
            ),
        )
        db.importDao().insertBatch(
            ImportBatchEntity(
                bundleId = null, filename = "b.csv", format = "csv",
                totalRows = 20, acceptedRows = 15, rejectedRows = 5,
                state = "completed", createdByUserId = 1L,
                createdAt = clockMs, completedAt = clockMs,
            ),
        )
        val d = repo.compute()
        assertThat(d.acquisitionSpend).isEqualTo(2)   // batch count
        assertThat(d.itemsOrdered).isEqualTo(30)       // total rows
        assertThat(d.itemsReturned).isEqualTo(7)       // total rejected
    }
}
