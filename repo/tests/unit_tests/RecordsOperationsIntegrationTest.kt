package com.eaglepoint.libops.tests

import androidx.test.core.app.ApplicationProvider
import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.data.db.LibOpsDatabase
import com.eaglepoint.libops.data.db.entity.BarcodeEntity
import com.eaglepoint.libops.data.db.entity.HoldingCopyEntity
import com.eaglepoint.libops.data.db.entity.MasterRecordEntity
import com.eaglepoint.libops.data.db.entity.TaxonomyNodeEntity
import com.eaglepoint.libops.domain.auth.Authorizer
import com.eaglepoint.libops.domain.auth.Capabilities
import com.eaglepoint.libops.domain.catalog.CatalogService
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real-Room integration test mirroring the operations performed by
 * [com.eaglepoint.libops.ui.records.RecordsActivity] (§9.7).
 *
 * Exercises CatalogService against real [LibOpsDatabase.inMemory] DAOs across
 * the full workflow: create → add holding → assign barcode → assign taxonomy.
 * Validates schema constraints, foreign-key persistence, and audit side-effects.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RecordsOperationsIntegrationTest {

    private lateinit var db: LibOpsDatabase
    private lateinit var audit: AuditLogger
    private lateinit var service: CatalogService
    private val clockMs = 1_700_000_000_000L
    private val userId = 42L

    @Before
    fun setUp() {
        db = LibOpsDatabase.inMemory(
            ApplicationProvider.getApplicationContext<android.content.Context>()
        )
        audit = AuditLogger(db.auditDao(), clock = { clockMs })
        service = CatalogService(
            authz = Authorizer(setOf(
                Capabilities.RECORDS_MANAGE,
                Capabilities.TAXONOMY_MANAGE,
                Capabilities.HOLDINGS_MANAGE,
                Capabilities.BARCODES_MANAGE,
            )),
            recordDao = db.recordDao(),
            taxonomyDao = db.taxonomyDao(),
            holdingDao = db.holdingDao(),
            barcodeDao = db.barcodeDao(),
            audit = audit,
            clock = { clockMs },
        )
    }

    @After
    fun tearDown() { db.close() }

    private fun bookRecord(title: String) = MasterRecordEntity(
        title = title, titleNormalized = title.lowercase(),
        publisher = null, pubDate = null, format = "paperback",
        category = "book", isbn10 = null, isbn13 = null,
        language = null, notes = null, status = "active",
        sourceProvenanceJson = null, createdByUserId = userId,
        createdAt = clockMs, updatedAt = clockMs,
    )

    @Test
    fun full_record_workflow_create_holding_barcode_taxonomy(): Unit = runBlocking {
        // 1. Create record
        val recordId = service.insertRecord(bookRecord("Workflow Book"), userId = userId)
        assertThat(db.recordDao().byId(recordId)).isNotNull()

        // 2. Create a taxonomy node and assign it to the record
        val taxId = service.createTaxonomyNode(
            TaxonomyNodeEntity(
                parentId = null, name = "Fiction", description = "Fiction category",
                createdAt = clockMs, updatedAt = clockMs,
            ),
            userId = userId,
        )
        service.assignTaxonomy(
            com.eaglepoint.libops.data.db.entity.RecordTaxonomyEntity(
                recordId = recordId, taxonomyId = taxId,
            ),
            userId = userId,
        )

        // 3. Add a holding
        val holdingId = service.addHolding(
            HoldingCopyEntity(
                masterRecordId = recordId, location = "Main Library",
                totalCount = 2, availableCount = 2,
                lastAdjustmentReason = null, lastAdjustmentUserId = null,
                createdAt = clockMs, updatedAt = clockMs,
            ),
            userId = userId,
        )
        assertThat(db.holdingDao().forRecord(recordId)).hasSize(1)

        // 4. Assign a barcode to the record
        val barcodeId = service.assignBarcode(
            BarcodeEntity(
                code = "BC-WORKFLOW-001", masterRecordId = recordId,
                holdingId = null, state = "available",
                assignedAt = null, retiredAt = null, reservedUntil = null,
                createdAt = clockMs, updatedAt = clockMs,
            ),
            userId = userId,
        )
        val storedBarcode = db.barcodeDao().byCode("BC-WORKFLOW-001")
        assertThat(storedBarcode).isNotNull()
        assertThat(storedBarcode!!.holdingId).isEqualTo(holdingId)

        // 5. Verify every step produced an audit event in real Room audit_events
        val events = db.auditDao().allEventsChronological().map { it.action }
        assertThat(events).containsAtLeast(
            "record.created",
            "taxonomy.created",
            "taxonomy.assigned",
            "holding.created",
            "barcode.assigned",
        )
    }

    @Test
    fun assign_barcode_to_record_without_holding_throws_and_does_not_persist(): Unit = runBlocking {
        val recordId = service.insertRecord(bookRecord("No Holding Book"), userId = userId)

        try {
            service.assignBarcode(
                BarcodeEntity(
                    code = "BC-ORPHAN", masterRecordId = recordId,
                    holdingId = null, state = "available",
                    assignedAt = null, retiredAt = null, reservedUntil = null,
                    createdAt = clockMs, updatedAt = clockMs,
                ),
                userId = userId,
            )
            assertThat("expected IllegalStateException").isEmpty()
        } catch (_: IllegalStateException) {
            // expected
        }
        assertThat(db.barcodeDao().byCode("BC-ORPHAN")).isNull()
    }

    @Test
    fun duplicate_barcode_code_is_rejected_by_unique_index(): Unit = runBlocking {
        val recordId = service.insertRecord(bookRecord("Dup Book"), userId = userId)
        service.addHolding(
            HoldingCopyEntity(
                masterRecordId = recordId, location = "Shelf A",
                totalCount = 1, availableCount = 1,
                lastAdjustmentReason = null, lastAdjustmentUserId = null,
                createdAt = clockMs, updatedAt = clockMs,
            ),
            userId = userId,
        )
        service.assignBarcode(
            BarcodeEntity(
                code = "BC-DUP-001", masterRecordId = recordId,
                holdingId = null, state = "available",
                assignedAt = null, retiredAt = null, reservedUntil = null,
                createdAt = clockMs, updatedAt = clockMs,
            ),
            userId = userId,
        )
        // Second call with same code must be rejected by duplicate-code check.
        try {
            service.assignBarcode(
                BarcodeEntity(
                    code = "BC-DUP-001", masterRecordId = recordId,
                    holdingId = null, state = "available",
                    assignedAt = null, retiredAt = null, reservedUntil = null,
                    createdAt = clockMs, updatedAt = clockMs,
                ),
                userId = userId,
            )
            assertThat("expected IllegalStateException").isEmpty()
        } catch (_: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun insert_and_update_record_persists_changes(): Unit = runBlocking {
        val id = service.insertRecord(bookRecord("Version 1"), userId = userId)
        val updated = db.recordDao().byId(id)!!.copy(
            title = "Version 2", titleNormalized = "version 2",
            updatedAt = clockMs + 1000,
        )
        service.updateRecord(updated, userId = userId)
        assertThat(db.recordDao().byId(id)!!.title).isEqualTo("Version 2")
    }
}
