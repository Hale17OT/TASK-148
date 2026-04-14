package com.eaglepoint.libops.domain.catalog

import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.data.db.dao.BarcodeDao
import com.eaglepoint.libops.data.db.dao.HoldingDao
import com.eaglepoint.libops.data.db.dao.RecordDao
import com.eaglepoint.libops.data.db.dao.TaxonomyDao
import com.eaglepoint.libops.data.db.entity.BarcodeEntity
import com.eaglepoint.libops.data.db.entity.HoldingCopyEntity
import com.eaglepoint.libops.data.db.entity.MasterRecordEntity
import com.eaglepoint.libops.data.db.entity.MasterRecordVersionEntity
import com.eaglepoint.libops.data.db.entity.RecordTaxonomyEntity
import com.eaglepoint.libops.data.db.entity.TaxonomyNodeEntity
import com.eaglepoint.libops.domain.auth.Authorizer
import com.eaglepoint.libops.domain.auth.Capabilities
import com.eaglepoint.libops.observability.ObservabilityPipeline
import com.eaglepoint.libops.observability.QueryTimer

/**
 * Service-layer authorization boundary for catalog mutations.
 *
 * Every mutating operation in this class enforces the required capability
 * via [Authorizer.require] before touching the DAO. This ensures that
 * even if a caller bypasses UI gating (e.g., a future API endpoint or
 * test harness), write operations are still capability-checked.
 *
 * Callers must construct with the session's [Authorizer] so that
 * capability checks reflect the authenticated user's grants.
 */
class CatalogService(
    private val authz: Authorizer,
    private val recordDao: RecordDao,
    private val taxonomyDao: TaxonomyDao,
    private val holdingDao: HoldingDao,
    private val barcodeDao: BarcodeDao,
    private val audit: AuditLogger,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val observability: ObservabilityPipeline? = null,
) {

    private val queryTimer: QueryTimer? = observability?.let { QueryTimer(it) }

    suspend fun insertRecord(entity: MasterRecordEntity, userId: Long): Long {
        authz.require(Capabilities.RECORDS_MANAGE)
        val id = recordDao.insert(entity)
        audit.record("record.created", "master_record", targetId = id.toString(), userId = userId)
        return id
    }

    suspend fun updateRecord(entity: MasterRecordEntity, userId: Long): Int {
        authz.require(Capabilities.RECORDS_MANAGE)
        val rows = recordDao.update(entity)
        audit.record("record.updated", "master_record", targetId = entity.id.toString(), userId = userId)
        return rows
    }

    suspend fun insertVersion(version: MasterRecordVersionEntity, userId: Long): Long {
        authz.require(Capabilities.RECORDS_MANAGE)
        return recordDao.insertVersion(version)
    }

    suspend fun createTaxonomyNode(node: TaxonomyNodeEntity, userId: Long): Long {
        authz.require(Capabilities.TAXONOMY_MANAGE)
        val id = taxonomyDao.insert(node)
        audit.record("taxonomy.created", "taxonomy_node", targetId = id.toString(), userId = userId)
        return id
    }

    suspend fun assignTaxonomy(binding: RecordTaxonomyEntity, userId: Long) {
        authz.require(Capabilities.TAXONOMY_MANAGE)
        taxonomyDao.assignToRecord(binding)
        audit.record("taxonomy.assigned", "master_record", targetId = binding.recordId.toString(), userId = userId, reason = "taxonomy=${binding.taxonomyId}")
    }

    suspend fun addHolding(holding: HoldingCopyEntity, userId: Long): Long {
        authz.require(Capabilities.HOLDINGS_MANAGE)
        val id = holdingDao.insert(holding)
        audit.record("holding.created", "holding_copy", targetId = id.toString(), userId = userId, reason = "record=${holding.masterRecordId},count=${holding.totalCount}")
        return id
    }

    suspend fun assignBarcode(barcode: BarcodeEntity, userId: Long): Long {
        authz.require(Capabilities.BARCODES_MANAGE)
        val existing = queryTimer?.let { it.timed("query", "barcodeDao.countCode") { barcodeDao.countCode(barcode.code) } } ?: barcodeDao.countCode(barcode.code)
        if (existing > 0) throw IllegalStateException("Barcode '${barcode.code}' already assigned")
        val holdings = queryTimer?.let { it.timed("query", "holdingDao.forRecord") { holdingDao.forRecord(barcode.masterRecordId ?: throw IllegalArgumentException("masterRecordId required")) } } ?: holdingDao.forRecord(barcode.masterRecordId ?: throw IllegalArgumentException("masterRecordId required"))
        val holdingId = holdings.firstOrNull()?.id
            ?: throw IllegalStateException("No holding copies for this record — add one first")
        val entity = barcode.copy(holdingId = holdingId)
        val id = barcodeDao.insert(entity)
        audit.record("barcode.assigned", "barcode", targetId = barcode.code, userId = userId, reason = "record=${barcode.masterRecordId}")
        return id
    }
}
