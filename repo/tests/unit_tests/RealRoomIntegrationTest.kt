package com.eaglepoint.libops.tests

import androidx.test.core.app.ApplicationProvider
import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.data.db.LibOpsDatabase
import com.eaglepoint.libops.data.db.entity.AlertEntity
import com.eaglepoint.libops.data.db.entity.HoldingCopyEntity
import com.eaglepoint.libops.data.db.entity.MasterRecordEntity
import com.eaglepoint.libops.data.db.entity.TaxonomyNodeEntity
import com.eaglepoint.libops.domain.auth.Authorizer
import com.eaglepoint.libops.domain.auth.Capabilities
import com.eaglepoint.libops.domain.catalog.CatalogService
import com.eaglepoint.libops.observability.OverdueSweeper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real Room integration tests — exercises the full persistence layer with
 * [LibOpsDatabase.inMemory] (no in-memory DAO fakes). Validates that entity
 * schemas, Room query logic, and Kotlin domain code integrate correctly.
 *
 * Addresses the "heavy reliance on in-memory fakes for persistence and
 * integration boundaries" finding by exercising the real schema, real query
 * execution, and real cross-DAO relationships.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RealRoomIntegrationTest {

    private lateinit var db: LibOpsDatabase
    private val clockMs = 1_700_000_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = LibOpsDatabase.inMemory(context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── real Room: record insertion and retrieval ─────────────────────────────

    @Test
    fun real_room_persists_and_retrieves_master_record(): Unit = runBlocking {
        val record = MasterRecordEntity(
            title = "Integration Book",
            titleNormalized = "integration book",
            publisher = "Real Publisher",
            pubDate = null, format = "paperback", category = "book",
            isbn10 = null, isbn13 = null, language = null, notes = null,
            status = "active", sourceProvenanceJson = null,
            createdByUserId = 1L, createdAt = clockMs, updatedAt = clockMs,
        )
        val id = db.recordDao().insert(record)
        assertThat(id).isGreaterThan(0L)

        val fetched = db.recordDao().byId(id)
        assertThat(fetched).isNotNull()
        assertThat(fetched!!.title).isEqualTo("Integration Book")
        assertThat(fetched.publisher).isEqualTo("Real Publisher")
    }

    @Test
    fun real_room_search_finds_record_by_title_prefix(): Unit = runBlocking {
        val record = MasterRecordEntity(
            title = "Searchable Book", titleNormalized = "searchable book",
            publisher = "Pub", pubDate = null, format = "paperback",
            category = "book", isbn10 = null, isbn13 = null,
            language = null, notes = null, status = "active",
            sourceProvenanceJson = null, createdByUserId = 1L,
            createdAt = clockMs, updatedAt = clockMs,
        )
        db.recordDao().insert(record)

        val results = db.recordDao().search(prefix = "search", q = "search", limit = 10, offset = 0)
        assertThat(results).hasSize(1)
        assertThat(results[0].title).isEqualTo("Searchable Book")
    }

    @Test
    fun real_room_active_count_increments_on_insert(): Unit = runBlocking {
        assertThat(db.recordDao().activeCount()).isEqualTo(0)

        repeat(3) { i ->
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
        assertThat(db.recordDao().activeCount()).isEqualTo(3)
    }

    // ── CatalogService wired to real Room ──────────────────────────────────────

    @Test
    fun catalog_service_against_real_room_inserts_record_and_audits(): Unit = runBlocking {
        val audit = AuditLogger(db.auditDao(), clock = { clockMs })
        val service = CatalogService(
            authz = Authorizer(setOf(Capabilities.RECORDS_MANAGE)),
            recordDao = db.recordDao(),
            taxonomyDao = db.taxonomyDao(),
            holdingDao = db.holdingDao(),
            barcodeDao = db.barcodeDao(),
            audit = audit,
            clock = { clockMs },
        )

        val id = service.insertRecord(
            MasterRecordEntity(
                title = "Service Book", titleNormalized = "service book",
                publisher = null, pubDate = null, format = "paperback",
                category = "book", isbn10 = null, isbn13 = null,
                language = null, notes = null, status = "active",
                sourceProvenanceJson = null, createdByUserId = 1L,
                createdAt = clockMs, updatedAt = clockMs,
            ),
            userId = 42L,
        )
        assertThat(id).isGreaterThan(0L)

        // Verify the record exists in real Room
        val fetched = db.recordDao().byId(id)
        assertThat(fetched).isNotNull()
        assertThat(fetched!!.title).isEqualTo("Service Book")

        // Verify audit event was recorded in real audit_events table
        val events = db.auditDao().allEventsChronological()
        assertThat(events.any { it.action == "record.created" && it.userId == 42L }).isTrue()
    }

    @Test
    fun catalog_service_taxonomy_and_holding_integration(): Unit = runBlocking {
        val audit = AuditLogger(db.auditDao(), clock = { clockMs })
        val service = CatalogService(
            authz = Authorizer(setOf(
                Capabilities.RECORDS_MANAGE,
                Capabilities.TAXONOMY_MANAGE,
                Capabilities.HOLDINGS_MANAGE,
            )),
            recordDao = db.recordDao(),
            taxonomyDao = db.taxonomyDao(),
            holdingDao = db.holdingDao(),
            barcodeDao = db.barcodeDao(),
            audit = audit,
            clock = { clockMs },
        )

        // Create a record
        val recordId = service.insertRecord(
            MasterRecordEntity(
                title = "Parent Record", titleNormalized = "parent record",
                publisher = null, pubDate = null, format = "paperback",
                category = "book", isbn10 = null, isbn13 = null,
                language = null, notes = null, status = "active",
                sourceProvenanceJson = null, createdByUserId = 1L,
                createdAt = clockMs, updatedAt = clockMs,
            ),
            userId = 1L,
        )

        // Create a taxonomy node
        val taxonomyId = service.createTaxonomyNode(
            TaxonomyNodeEntity(
                parentId = null, name = "Fiction", description = null,
                createdAt = clockMs, updatedAt = clockMs,
            ),
            userId = 1L,
        )
        assertThat(taxonomyId).isGreaterThan(0L)

        // Add a holding for the record
        val holdingId = service.addHolding(
            HoldingCopyEntity(
                masterRecordId = recordId, location = "Main Library",
                totalCount = 5, availableCount = 5,
                lastAdjustmentReason = null, lastAdjustmentUserId = null,
                createdAt = clockMs, updatedAt = clockMs,
            ),
            userId = 1L,
        )
        assertThat(holdingId).isGreaterThan(0L)

        // Verify all relationships persisted in real Room
        val holdings = db.holdingDao().forRecord(recordId)
        assertThat(holdings).hasSize(1)
        assertThat(holdings[0].totalCount).isEqualTo(5)
    }

    // ── OverdueSweeper wired to real Room ──────────────────────────────────────

    @Test
    fun overdue_sweeper_against_real_room_transitions_alerts(): Unit = runBlocking {
        val audit = AuditLogger(db.auditDao(), clock = { clockMs })
        val sweeper = OverdueSweeper(db.alertDao(), audit, clock = { clockMs })

        val eightDaysAgo = clockMs - 8L * 24 * 60 * 60 * 1000L
        db.alertDao().insert(
            AlertEntity(
                category = "job_failure", severity = "warn",
                title = "Overdue alert", body = "body",
                status = "acknowledged",
                ownerUserId = null, correlationId = null,
                dueAt = eightDaysAgo + 7L * 24 * 60 * 60 * 1000L,
                createdAt = eightDaysAgo, updatedAt = eightDaysAgo, version = 1,
            ),
        )

        val result = sweeper.sweep()
        assertThat(result.transitioned).isEqualTo(1)

        // Verify the alert was updated in real Room
        val overdue = db.alertDao().listByStatus("overdue", 100, 0)
        assertThat(overdue).hasSize(1)

        // Verify audit event landed in real Room audit_events table
        val events = db.auditDao().allEventsChronological()
        assertThat(events.any { it.action == "alert.overdue_sweep" }).isTrue()
    }
}
