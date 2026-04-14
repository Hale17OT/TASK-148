package com.eaglepoint.libops.tests.fakes

import com.eaglepoint.libops.data.db.dao.AlertDao
import com.eaglepoint.libops.data.db.dao.ImportDao
import com.eaglepoint.libops.data.db.entity.AlertAcknowledgementEntity
import com.eaglepoint.libops.data.db.entity.AlertEntity
import com.eaglepoint.libops.data.db.entity.AlertResolutionEntity
import com.eaglepoint.libops.data.db.entity.ImportBatchEntity
import com.eaglepoint.libops.data.db.entity.ImportRowResultEntity
import com.eaglepoint.libops.data.db.entity.ImportedBundleEntity
import java.util.concurrent.atomic.AtomicLong

class FakeAlertDao : AlertDao {
    private val alerts = linkedMapOf<Long, AlertEntity>()
    private val acks = mutableListOf<AlertAcknowledgementEntity>()
    private val resolutions = mutableListOf<AlertResolutionEntity>()
    private val ids = AtomicLong(0)

    override suspend fun insert(alert: AlertEntity): Long {
        val id = ids.incrementAndGet()
        alerts[id] = alert.copy(id = id)
        return id
    }

    override suspend fun update(alert: AlertEntity): Int {
        if (!alerts.containsKey(alert.id)) return 0
        alerts[alert.id] = alert
        return 1
    }

    override suspend fun insertAck(ack: AlertAcknowledgementEntity): Long {
        acks.add(ack); return ack.id
    }

    override suspend fun insertResolution(res: AlertResolutionEntity): Long {
        resolutions.add(res); return res.id
    }

    override suspend fun byId(id: Long): AlertEntity? = alerts[id]

    override suspend fun listByStatus(status: String, limit: Int, offset: Int): List<AlertEntity> =
        alerts.values.filter { it.status == status }
            .sortedByDescending { it.createdAt }
            .drop(offset).take(limit)

    override suspend fun overdueCount(): Int =
        alerts.values.count { it.status == "overdue" }

    override suspend fun overdueForUserSince(userId: Long, since: Long): Int =
        alerts.values.count { it.ownerUserId == userId && it.status == "overdue" && it.createdAt >= since }

    fun all(): List<AlertEntity> = alerts.values.toList()
}

class FakeImportDao : ImportDao {
    private val bundles = linkedMapOf<Long, ImportedBundleEntity>()
    private val batches = linkedMapOf<Long, ImportBatchEntity>()
    private val rows = mutableListOf<ImportRowResultEntity>()
    private val bundleIds = AtomicLong(0)
    private val batchIds = AtomicLong(0)
    private val rowIds = AtomicLong(0)

    override suspend fun insertBundle(bundle: ImportedBundleEntity): Long {
        val id = bundleIds.incrementAndGet()
        bundles[id] = bundle.copy(id = id)
        return id
    }

    override suspend fun bundleByChecksum(checksum: String): ImportedBundleEntity? =
        bundles.values.firstOrNull { it.checksum == checksum }

    override suspend fun insertBatch(batch: ImportBatchEntity): Long {
        val id = batchIds.incrementAndGet()
        batches[id] = batch.copy(id = id)
        return id
    }

    override suspend fun updateBatch(batch: ImportBatchEntity): Int {
        if (!batches.containsKey(batch.id)) return 0
        batches[batch.id] = batch
        return 1
    }

    override suspend fun insertRow(row: ImportRowResultEntity): Long {
        val id = rowIds.incrementAndGet()
        rows.add(row.copy(id = id))
        return id
    }

    override suspend fun batchById(id: Long): ImportBatchEntity? = batches[id]

    override suspend fun recentBatches(limit: Int, offset: Int): List<ImportBatchEntity> =
        batches.values.sortedWith(compareByDescending<ImportBatchEntity> { it.createdAt }.thenByDescending { it.id })
            .drop(offset).take(limit)

    override suspend fun rowsFor(batchId: Long, limit: Int, offset: Int): List<ImportRowResultEntity> =
        rows.filter { it.batchId == batchId }.sortedBy { it.rowIndex }.drop(offset).take(limit)

    override suspend fun userImportsSince(userId: Long, since: Long): Int =
        batches.values.count { it.createdByUserId == userId && it.createdAt >= since }

    override suspend fun totalRowsAllBatches(): Int =
        batches.values.sumOf { it.totalRows }

    override suspend fun totalAcceptedAllBatches(): Int =
        batches.values.sumOf { it.acceptedRows }

    override suspend fun totalRejectedAllBatches(): Int =
        batches.values.sumOf { it.rejectedRows }

    override suspend fun batchCount(): Int = batches.size

    override suspend fun rejectedRowsForUserSince(userId: Long, since: Long): Int =
        rows.count { row ->
            row.outcome == "rejected_with_errors" && row.createdAt >= since &&
                batches.values.any { it.id == row.batchId && it.createdByUserId == userId }
        }

    fun allBundles(): List<ImportedBundleEntity> = bundles.values.toList()
    fun allBatches(): List<ImportBatchEntity> = batches.values.toList()
}
