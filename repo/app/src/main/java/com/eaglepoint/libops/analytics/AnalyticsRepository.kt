package com.eaglepoint.libops.analytics

import com.eaglepoint.libops.data.db.LibOpsDatabase
import com.eaglepoint.libops.domain.alerts.AlertPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Source-of-truth analytics derived directly from transactional tables
 * (§16). Nightly snapshots remain the cache — dashboards read from here so
 * users see current state without waiting for the next snapshot.
 */
class AnalyticsRepository(
    private val db: LibOpsDatabase,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    data class Dashboard(
        val configuredSources: Int,
        val queuedJobs: Int,
        val processedJobsLast24h: Int,
        val acceptedRecords: Int,
        val openAlerts: Int,
        val overdueAlerts: Int,
        val duplicatesPending: Int,
        val avgQualityScore: Int,
        val acquisitionSpend: Int,
        val itemsOrdered: Int,
        val itemsReturned: Int,
    )

    suspend fun compute(): Dashboard = withContext(Dispatchers.IO) {
        val sources = db.collectionSourceDao().listAll()
            .count { it.state == "active" || it.state == "draft" || it.state == "disabled" }
        val queued = db.jobDao().countByStatus("queued") + db.jobDao().countByStatus("scheduled")
        val processed = db.jobDao().countByStatus("succeeded") + db.jobDao().countByStatus("terminal_failed")
        val accepted = db.recordDao().activeCount()
        val open = db.alertDao().listByStatus("open", 1_000, 0).size
        val overdue = db.alertDao().overdueCount()
        val duplicates = db.duplicateDao().unresolvedOlderThan(clock())
        val quality = db.qualityScoreDao().recent(50)
            .takeIf { it.isNotEmpty() }
            ?.let { list -> list.map { it.score }.average().toInt() }
            ?: 100 // no data ⇒ assume baseline

        // Acquisition KPIs derived from import batch aggregates.
        // acquisitionSpend = total import batches processed (acquisition effort),
        // itemsOrdered = total rows submitted across all batches (items requested),
        // itemsReturned = total rejected rows across all batches (items returned/failed).
        val batchCount = db.importDao().batchCount()
        val totalOrdered = db.importDao().totalRowsAllBatches()
        val totalReturned = db.importDao().totalRejectedAllBatches()

        Dashboard(
            configuredSources = sources,
            queuedJobs = queued,
            processedJobsLast24h = processed,
            acceptedRecords = accepted,
            openAlerts = open,
            overdueAlerts = overdue,
            duplicatesPending = duplicates,
            avgQualityScore = quality,
            acquisitionSpend = batchCount,
            itemsOrdered = totalOrdered,
            itemsReturned = totalReturned,
        )
    }

    /** SLA sanity-check helper used by the scheduled overdue sweeper. */
    fun slaBreached(createdAt: Long): Boolean =
        AlertPolicy.isOverdue(createdAt, clock())
}
