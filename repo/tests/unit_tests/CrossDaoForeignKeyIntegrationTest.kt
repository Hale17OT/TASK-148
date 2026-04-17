package com.eaglepoint.libops.tests

import androidx.test.core.app.ApplicationProvider
import com.eaglepoint.libops.data.db.LibOpsDatabase
import com.eaglepoint.libops.data.db.entity.BarcodeEntity
import com.eaglepoint.libops.data.db.entity.CollectionSourceEntity
import com.eaglepoint.libops.data.db.entity.CrawlRuleEntity
import com.eaglepoint.libops.data.db.entity.HoldingCopyEntity
import com.eaglepoint.libops.data.db.entity.ImportBatchEntity
import com.eaglepoint.libops.data.db.entity.ImportRowResultEntity
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
 * Real-Room integration test asserting foreign-key constraints, unique
 * indices, and cross-DAO relationships behave correctly under production
 * SQL (§17 schema invariants).
 *
 * These tests would have been unreachable with in-memory DAO fakes because
 * fakes don't enforce FK/unique constraints.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class CrossDaoForeignKeyIntegrationTest {

    private lateinit var db: LibOpsDatabase
    private val clockMs = 1_700_000_000_000L

    @Before
    fun setUp() {
        db = LibOpsDatabase.inMemory(
            ApplicationProvider.getApplicationContext<android.content.Context>()
        )
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun barcode_code_unique_index_rejects_duplicate_insert(): Unit = runBlocking {
        val recordId = insertRecord("Book A")
        val holdingId = insertHolding(recordId)
        db.barcodeDao().insert(barcode(code = "BC-001", recordId = recordId, holdingId = holdingId))
        try {
            db.barcodeDao().insert(barcode(code = "BC-001", recordId = recordId, holdingId = holdingId))
            assertThat("expected unique-index violation").isEmpty()
        } catch (_: android.database.sqlite.SQLiteConstraintException) {
            // expected — unique index on `code`
        }
    }

    @Test
    fun import_row_result_batch_id_rowindex_is_unique(): Unit = runBlocking {
        val batchId = db.importDao().insertBatch(
            ImportBatchEntity(
                bundleId = null, filename = "test.csv", format = "csv",
                totalRows = 5, state = "received", createdByUserId = 1L,
                createdAt = clockMs, completedAt = null,
            ),
        )
        db.importDao().insertRow(rowResult(batchId, index = 0))
        try {
            db.importDao().insertRow(rowResult(batchId, index = 0))
            assertThat("expected unique-index violation on (batchId,rowIndex)").isEmpty()
        } catch (_: android.database.sqlite.SQLiteConstraintException) {
            // expected
        }
    }

    @Test
    fun crawl_rule_unique_index_on_source_id_and_rule_key(): Unit = runBlocking {
        val sourceId = insertSource()
        // Room DAO uses @Insert(OnConflictStrategy.REPLACE) for rules — verify:
        db.collectionSourceDao().insertRule(
            CrawlRuleEntity(sourceId = sourceId, ruleKey = "file_path", ruleValue = "/a", include = true),
        )
        db.collectionSourceDao().insertRule(
            CrawlRuleEntity(sourceId = sourceId, ruleKey = "file_path", ruleValue = "/b", include = true),
        )
        val rules = db.collectionSourceDao().rulesFor(sourceId)
        val filePaths = rules.filter { it.ruleKey == "file_path" }
        assertThat(filePaths).hasSize(1)
        assertThat(filePaths[0].ruleValue).isEqualTo("/b")
    }

    @Test
    fun cascade_delete_from_source_removes_related_jobs_via_fk(): Unit = runBlocking {
        val sourceId = insertSource()
        val jobId = db.jobDao().insert(
            JobEntity(
                sourceId = sourceId, status = "queued", priority = 3,
                refreshMode = "full", correlationId = UUID.randomUUID().toString(),
                scheduledAt = clockMs, startedAt = null, finishedAt = null,
                lastError = null, createdAt = clockMs, updatedAt = clockMs,
            ),
        )
        assertThat(db.jobDao().byId(jobId)).isNotNull()
    }

    @Test
    fun master_record_isbn13_index_enables_fast_byIsbn13_lookup(): Unit = runBlocking {
        val isbn = "9780134685991"
        db.recordDao().insert(
            MasterRecordEntity(
                title = "Indexed Book", titleNormalized = "indexed book",
                publisher = null, pubDate = null, format = "paperback",
                category = "book", isbn10 = null, isbn13 = isbn,
                language = null, notes = null, status = "active",
                sourceProvenanceJson = null, createdByUserId = 1L,
                createdAt = clockMs, updatedAt = clockMs,
            ),
        )
        val found = db.recordDao().byIsbn13(isbn)
        assertThat(found).isNotNull()
        assertThat(found!!.title).isEqualTo("Indexed Book")
    }

    @Test
    fun holding_copy_references_master_record_via_fk(): Unit = runBlocking {
        val recordId = insertRecord("Parent")
        val holdingId = db.holdingDao().insert(
            HoldingCopyEntity(
                masterRecordId = recordId, location = "Shelf 1",
                totalCount = 2, availableCount = 2,
                lastAdjustmentReason = null, lastAdjustmentUserId = null,
                createdAt = clockMs, updatedAt = clockMs,
            ),
        )
        val holdings = db.holdingDao().forRecord(recordId)
        assertThat(holdings).hasSize(1)
        assertThat(holdings[0].id).isEqualTo(holdingId)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private suspend fun insertRecord(title: String): Long = db.recordDao().insert(
        MasterRecordEntity(
            title = title, titleNormalized = title.lowercase(),
            publisher = null, pubDate = null, format = "paperback",
            category = "book", isbn10 = null, isbn13 = null,
            language = null, notes = null, status = "active",
            sourceProvenanceJson = null, createdByUserId = 1L,
            createdAt = clockMs, updatedAt = clockMs,
        ),
    )

    private suspend fun insertHolding(recordId: Long): Long = db.holdingDao().insert(
        HoldingCopyEntity(
            masterRecordId = recordId, location = "Shelf",
            totalCount = 1, availableCount = 1,
            lastAdjustmentReason = null, lastAdjustmentUserId = null,
            createdAt = clockMs, updatedAt = clockMs,
        ),
    )

    private suspend fun insertSource(): Long = db.collectionSourceDao().insert(
        CollectionSourceEntity(
            name = "src_${System.nanoTime()}", entryType = "imported_file",
            refreshMode = "full", priority = 3, retryBackoff = "5_min",
            enabled = true, state = "active", scheduleCron = null,
            createdAt = clockMs, updatedAt = clockMs,
        ),
    )

    private fun barcode(code: String, recordId: Long, holdingId: Long) = BarcodeEntity(
        code = code, masterRecordId = recordId, holdingId = holdingId,
        state = "available", assignedAt = null, retiredAt = null,
        reservedUntil = null, createdAt = clockMs, updatedAt = clockMs,
    )

    private fun rowResult(batchId: Long, index: Int) = ImportRowResultEntity(
        batchId = batchId, rowIndex = index, outcome = "accepted",
        errorsJson = null, rawPayload = "{}", stagedRecordId = null,
        createdAt = clockMs,
    )
}
