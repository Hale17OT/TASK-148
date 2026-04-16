package com.eaglepoint.libops.tests

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
import com.eaglepoint.libops.domain.catalog.CatalogService
import com.eaglepoint.libops.tests.fakes.FakeAuditDao
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * Tests for [CatalogService] verifying that every mutating operation enforces
 * its required capability via [Authorizer.require] and delegates correctly to
 * the underlying DAOs (§11 authorization boundary).
 *
 * Pure JVM — all dependencies are in-memory stubs with no Android SDK usage.
 */
class CatalogServiceTest {

    private lateinit var recordDao: CatalogRecordStub
    private lateinit var taxonomyDao: CatalogTaxonomyStub
    private lateinit var holdingDao: CatalogHoldingStub
    private lateinit var barcodeDao: CatalogBarcodeStub
    private lateinit var auditDao: FakeAuditDao

    private val clockMs = 1_700_000_000_000L

    private fun serviceWith(vararg caps: String): CatalogService =
        CatalogService(
            authz = Authorizer(caps.toSet()),
            recordDao = recordDao,
            taxonomyDao = taxonomyDao,
            holdingDao = holdingDao,
            barcodeDao = barcodeDao,
            audit = AuditLogger(auditDao, clock = { clockMs }),
            clock = { clockMs },
        )

    @Before
    fun setUp() {
        recordDao = CatalogRecordStub()
        taxonomyDao = CatalogTaxonomyStub()
        holdingDao = CatalogHoldingStub()
        barcodeDao = CatalogBarcodeStub()
        auditDao = FakeAuditDao()
    }

    private fun fakeRecord(title: String = "Test Record") = MasterRecordEntity(
        title = title,
        titleNormalized = title.lowercase(),
        publisher = null, pubDate = null, format = null,
        category = "book", isbn10 = null, isbn13 = null,
        language = null, notes = null, status = "active",
        sourceProvenanceJson = null, createdByUserId = 1L,
        createdAt = clockMs, updatedAt = clockMs,
    )

    private fun fakeVersion(recordId: Long = 1L) = MasterRecordVersionEntity(
        recordId = recordId, version = 2, snapshotJson = "{}",
        editorUserId = 1L, changeSummary = null, createdAt = clockMs,
    )

    private fun fakeTaxonomyNode() = TaxonomyNodeEntity(
        parentId = null, name = "Fiction", description = null,
        createdAt = clockMs, updatedAt = clockMs,
    )

    private fun fakeHolding(recordId: Long = 1L) = HoldingCopyEntity(
        masterRecordId = recordId, location = "Main Library",
        totalCount = 3, availableCount = 2,
        lastAdjustmentReason = null, lastAdjustmentUserId = null,
        createdAt = clockMs, updatedAt = clockMs,
    )

    private fun fakeBarcode(recordId: Long = 1L) = BarcodeEntity(
        code = "BC001", masterRecordId = recordId, holdingId = null,
        state = "available", assignedAt = null, retiredAt = null,
        reservedUntil = null, createdAt = clockMs, updatedAt = clockMs,
    )

    // ── insertRecord ──────────────────────────────────────────────────────────

    @Test(expected = SecurityException::class)
    fun insert_record_without_records_manage_throws(): Unit = runBlocking {
        serviceWith().insertRecord(fakeRecord(), userId = 1L)
    }

    @Test
    fun insert_record_with_capability_inserts_and_returns_id(): Unit = runBlocking {
        val id = serviceWith(Capabilities.RECORDS_MANAGE).insertRecord(fakeRecord("Novel"), userId = 1L)
        assertThat(id).isGreaterThan(0L)
        assertThat(recordDao.insertedRecords).hasSize(1)
        assertThat(recordDao.insertedRecords[0].title).isEqualTo("Novel")
    }

    @Test
    fun insert_record_emits_record_created_audit_event(): Unit = runBlocking {
        serviceWith(Capabilities.RECORDS_MANAGE).insertRecord(fakeRecord(), userId = 3L)
        val events = auditDao.allEventsChronological()
        assertThat(events.any { it.action == "record.created" && it.userId == 3L }).isTrue()
    }

    // ── updateRecord ──────────────────────────────────────────────────────────

    @Test(expected = SecurityException::class)
    fun update_record_without_capability_throws(): Unit = runBlocking {
        serviceWith().updateRecord(fakeRecord(), userId = 1L)
    }

    @Test
    fun update_record_with_capability_calls_dao(): Unit = runBlocking {
        val result = serviceWith(Capabilities.RECORDS_MANAGE).updateRecord(fakeRecord(), userId = 1L)
        assertThat(result).isEqualTo(1)
        assertThat(recordDao.updatedRecords).hasSize(1)
    }

    // ── insertVersion ─────────────────────────────────────────────────────────

    @Test(expected = SecurityException::class)
    fun insert_version_without_capability_throws(): Unit = runBlocking {
        serviceWith().insertVersion(fakeVersion(), userId = 1L)
    }

    @Test
    fun insert_version_with_capability_delegates_to_record_dao(): Unit = runBlocking {
        val id = serviceWith(Capabilities.RECORDS_MANAGE).insertVersion(fakeVersion(recordId = 5L), userId = 1L)
        assertThat(id).isGreaterThan(0L)
    }

    // ── createTaxonomyNode ────────────────────────────────────────────────────

    @Test(expected = SecurityException::class)
    fun create_taxonomy_node_without_capability_throws(): Unit = runBlocking {
        serviceWith().createTaxonomyNode(fakeTaxonomyNode(), userId = 1L)
    }

    @Test
    fun create_taxonomy_node_with_capability_inserts_node(): Unit = runBlocking {
        val id = serviceWith(Capabilities.TAXONOMY_MANAGE).createTaxonomyNode(fakeTaxonomyNode(), userId = 1L)
        assertThat(id).isGreaterThan(0L)
        assertThat(taxonomyDao.insertedNodes[0].name).isEqualTo("Fiction")
    }

    // ── assignTaxonomy ────────────────────────────────────────────────────────

    @Test(expected = SecurityException::class)
    fun assign_taxonomy_without_capability_throws(): Unit = runBlocking {
        serviceWith().assignTaxonomy(RecordTaxonomyEntity(recordId = 1L, taxonomyId = 1L), userId = 1L)
    }

    @Test
    fun assign_taxonomy_with_capability_assigns_binding(): Unit = runBlocking {
        val binding = RecordTaxonomyEntity(recordId = 2L, taxonomyId = 3L)
        serviceWith(Capabilities.TAXONOMY_MANAGE).assignTaxonomy(binding, userId = 1L)
        assertThat(taxonomyDao.assignments).contains(binding)
    }

    // ── addHolding ────────────────────────────────────────────────────────────

    @Test(expected = SecurityException::class)
    fun add_holding_without_capability_throws(): Unit = runBlocking {
        serviceWith().addHolding(fakeHolding(), userId = 1L)
    }

    @Test
    fun add_holding_with_capability_inserts_holding(): Unit = runBlocking {
        val id = serviceWith(Capabilities.HOLDINGS_MANAGE).addHolding(fakeHolding(recordId = 7L), userId = 1L)
        assertThat(id).isGreaterThan(0L)
        assertThat(holdingDao.insertedHoldings[0].masterRecordId).isEqualTo(7L)
    }

    // ── assignBarcode ─────────────────────────────────────────────────────────

    @Test(expected = SecurityException::class)
    fun assign_barcode_without_capability_throws(): Unit = runBlocking {
        serviceWith().assignBarcode(fakeBarcode(), userId = 1L)
    }

    @Test(expected = IllegalStateException::class)
    fun assign_barcode_with_duplicate_code_throws(): Unit = runBlocking {
        barcodeDao.countForCode = 1  // pre-existing barcode with same code
        serviceWith(Capabilities.BARCODES_MANAGE).assignBarcode(fakeBarcode(), userId = 1L)
    }

    @Test(expected = IllegalStateException::class)
    fun assign_barcode_with_no_holding_throws(): Unit = runBlocking {
        barcodeDao.countForCode = 0
        holdingDao.holdingsForRecord = emptyList()  // no holding → should throw
        serviceWith(Capabilities.BARCODES_MANAGE).assignBarcode(fakeBarcode(recordId = 99L), userId = 1L)
    }

    @Test
    fun assign_barcode_with_valid_holding_inserts_and_returns_id(): Unit = runBlocking {
        barcodeDao.countForCode = 0
        holdingDao.holdingsForRecord = listOf(
            HoldingCopyEntity(
                id = 42L, masterRecordId = 1L, location = "Main Library",
                totalCount = 1, availableCount = 1,
                lastAdjustmentReason = null, lastAdjustmentUserId = null,
                createdAt = clockMs, updatedAt = clockMs,
            ),
        )
        val id = serviceWith(Capabilities.BARCODES_MANAGE).assignBarcode(fakeBarcode(recordId = 1L), userId = 1L)
        assertThat(id).isGreaterThan(0L)
        assertThat(barcodeDao.insertedBarcodes[0].holdingId).isEqualTo(42L)
    }
}

// ── Test doubles ──────────────────────────────────────────────────────────────

class CatalogRecordStub : RecordDao {
    val insertedRecords = mutableListOf<MasterRecordEntity>()
    val updatedRecords = mutableListOf<MasterRecordEntity>()
    private val ids = AtomicLong(100)

    override suspend fun insert(record: MasterRecordEntity): Long {
        val id = ids.incrementAndGet()
        insertedRecords.add(record.copy(id = id))
        return id
    }

    override suspend fun update(record: MasterRecordEntity): Int { updatedRecords.add(record); return 1 }
    override suspend fun byId(id: Long): MasterRecordEntity? = insertedRecords.firstOrNull { it.id == id }
    override suspend fun byIsbn13(isbn13: String): MasterRecordEntity? = null
    override suspend fun search(prefix: String, q: String, limit: Int, offset: Int): List<MasterRecordEntity> = emptyList()
    override suspend fun activeCount(): Int = insertedRecords.size
    override suspend fun insertVersion(version: MasterRecordVersionEntity): Long = ids.incrementAndGet()
    override suspend fun versionsFor(recordId: Long): List<MasterRecordVersionEntity> = emptyList()
}

class CatalogTaxonomyStub : TaxonomyDao {
    val insertedNodes = mutableListOf<TaxonomyNodeEntity>()
    val assignments = mutableListOf<RecordTaxonomyEntity>()
    private val ids = AtomicLong(200)

    override suspend fun insert(node: TaxonomyNodeEntity): Long {
        val id = ids.incrementAndGet()
        insertedNodes.add(node.copy(id = id))
        return id
    }

    override suspend fun update(node: TaxonomyNodeEntity): Int = 1
    override suspend fun byId(id: Long): TaxonomyNodeEntity? = null
    override suspend fun children(parentId: Long?): List<TaxonomyNodeEntity> = emptyList()
    override suspend fun childCount(id: Long): Int = 0
    override suspend fun assignToRecord(binding: RecordTaxonomyEntity) { assignments.add(binding) }
    override suspend fun detachFromRecord(recordId: Long, taxonomyId: Long): Int = 0
}

class CatalogHoldingStub : HoldingDao {
    val insertedHoldings = mutableListOf<HoldingCopyEntity>()
    var holdingsForRecord: List<HoldingCopyEntity> = emptyList()
    private val ids = AtomicLong(300)

    override suspend fun insert(holding: HoldingCopyEntity): Long {
        val id = ids.incrementAndGet()
        insertedHoldings.add(holding.copy(id = id))
        return id
    }

    override suspend fun update(holding: HoldingCopyEntity): Int = 1
    override suspend fun forRecord(recordId: Long): List<HoldingCopyEntity> = holdingsForRecord
    override suspend fun totalForRecord(recordId: Long): Int? =
        holdingsForRecord.sumOf { it.totalCount }.takeIf { holdingsForRecord.isNotEmpty() }
}

class CatalogBarcodeStub : BarcodeDao {
    val insertedBarcodes = mutableListOf<BarcodeEntity>()
    var countForCode: Int = 0
    private val ids = AtomicLong(400)

    override suspend fun insert(barcode: BarcodeEntity): Long {
        val id = ids.incrementAndGet()
        insertedBarcodes.add(barcode.copy(id = id))
        return id
    }

    override suspend fun update(barcode: BarcodeEntity): Int = 1
    override suspend fun byCode(code: String): BarcodeEntity? = null
    override suspend fun forHolding(holdingId: Long): List<BarcodeEntity> = emptyList()
    override suspend fun countCode(code: String): Int = countForCode
}
