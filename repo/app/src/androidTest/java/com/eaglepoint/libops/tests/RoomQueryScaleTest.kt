package com.eaglepoint.libops.tests

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eaglepoint.libops.data.db.LibOpsDatabase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

/**
 * Instrumented Room/SQLite performance test at stated NFR scale (§18).
 *
 * Seeds a real in-memory Room database with 1,000,000-row datasets and
 * asserts that target queries complete within the <50ms budget on
 * indexed columns. This proves the database schema, indices, and Room
 * query compilation meet the acceptance-critical performance SLO against
 * actual SQLite — not in-memory JVM proxies.
 *
 * Datasets per test:
 *  - MasterRecord: 1,000,000 rows (seeded via raw SQL compiled statements)
 *  - Job: 1,000,000 rows
 *  - AuditEvent: 1,000,000 rows
 *  - Alert: 1,000,000 rows
 *
 * Query budgets: <50ms per indexed lookup/query (§18 NFR target).
 */
@RunWith(AndroidJUnit4::class)
class RoomQueryScaleTest {

    private lateinit var db: LibOpsDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, LibOpsDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun masterRecord_byIsbn13_1M_rows_under_50ms() = runBlocking {
        val count = 1_000_000
        seedMasterRecords(count)

        // Warm up SQLite page cache
        db.recordDao().byIsbn13("978${"%010d".format(0L)}")

        val targetIsbn = "978${"%010d".format(500_000L)}"
        val ms = measureTimeMillis {
            val result = db.recordDao().byIsbn13(targetIsbn)
            assertThat(result).isNotNull()
            assertThat(result!!.isbn13).isEqualTo(targetIsbn)
        }
        println("[room-perf] byIsbn13 in $count rows: ${ms}ms")
        assertThat(ms).isLessThan(50L)
    }

    @Test
    fun masterRecord_byId_1M_rows_under_50ms() = runBlocking {
        val count = 1_000_000
        seedMasterRecords(count)

        // Warm up
        db.recordDao().byId(1)

        val targetId = 500_000L
        val ms = measureTimeMillis {
            val result = db.recordDao().byId(targetId)
            assertThat(result).isNotNull()
        }
        println("[room-perf] byId in $count rows: ${ms}ms")
        assertThat(ms).isLessThan(50L)
    }

    @Test
    fun masterRecord_search_1M_rows_under_50ms() = runBlocking {
        val count = 1_000_000
        seedMasterRecords(count)

        // Warm up
        db.recordDao().search(prefix = "record title 42", q = "record title 42", limit = 20, offset = 0)

        val ms = measureTimeMillis {
            val results = db.recordDao().search(
                prefix = "record title 500000",
                q = "record title 500000",
                limit = 20,
                offset = 0,
            )
            assertThat(results).isNotEmpty()
        }
        println("[room-perf] search in $count rows: ${ms}ms")
        assertThat(ms).isLessThan(50L)
    }

    @Test
    fun job_nextRunnable_1M_rows_under_50ms() = runBlocking {
        val count = 1_000_000
        seedJobs(count)

        val nowMs = 1_700_000_000_000L + (count / 2) * 1000L

        // Warm up
        db.jobDao().nextRunnable(limit = 20, nowMs = nowMs)

        val ms = measureTimeMillis {
            val results = db.jobDao().nextRunnable(limit = 20, nowMs = nowMs)
            assertThat(results).isNotEmpty()
        }
        println("[room-perf] nextRunnable in $count rows: ${ms}ms")
        assertThat(ms).isLessThan(50L)
    }

    @Test
    fun auditEvent_search_1M_rows_under_50ms() = runBlocking {
        val count = 1_000_000
        seedAuditEvents(count)

        val fromMs = 1_700_000_000_000L + 400_000L * 100L
        val toMs = 1_700_000_000_000L + 600_000L * 100L

        // Warm up
        db.auditDao().search(userId = 42, correlationPrefix = null, fromMs = fromMs, toMs = toMs, limit = 50, offset = 0)

        val ms = measureTimeMillis {
            val results = db.auditDao().search(
                userId = 42,
                correlationPrefix = null,
                fromMs = fromMs,
                toMs = toMs,
                limit = 50,
                offset = 0,
            )
            assertThat(results).isNotEmpty()
        }
        println("[room-perf] audit search in $count rows: ${ms}ms")
        assertThat(ms).isLessThan(50L)
    }

    @Test
    fun alert_listByStatus_1M_rows_under_50ms() = runBlocking {
        val count = 1_000_000
        seedAlerts(count)

        // Warm up
        db.alertDao().listByStatus("open", limit = 20, offset = 0)

        val ms = measureTimeMillis {
            val results = db.alertDao().listByStatus("open", limit = 20, offset = 0)
            assertThat(results).isNotEmpty()
        }
        println("[room-perf] alert listByStatus in $count rows: ${ms}ms")
        assertThat(ms).isLessThan(50L)
    }

    // ── Seed helpers using raw SQL compiled statements for speed ──

    private fun seedMasterRecords(count: Int) {
        val sqdb = db.openHelper.writableDatabase
        sqdb.execSQL("BEGIN TRANSACTION")
        val stmt = sqdb.compileStatement(
            """INSERT INTO master_records
               (title, titleNormalized, publisher, pubDate, format, category,
                isbn10, isbn13, language, notes, status, sourceProvenanceJson,
                createdByUserId, createdAt, updatedAt, version)
               VALUES (?,?,?,NULL,?,?,NULL,?,?,NULL,?,?,?,?,?,1)"""
        )
        for (i in 0 until count) {
            stmt.clearBindings()
            stmt.bindString(1, "Record Title $i: A Library Book")
            stmt.bindString(2, "record title $i a library book")
            stmt.bindString(3, "Publisher ${i % 50}")
            stmt.bindString(4, "hardcover")
            stmt.bindString(5, "book")
            stmt.bindString(6, "978${"%010d".format(i.toLong())}")
            stmt.bindString(7, "en")
            stmt.bindString(8, "active")
            stmt.bindString(9, "{}")
            stmt.bindLong(10, 1)
            stmt.bindLong(11, 1_700_000_000_000L + i)
            stmt.bindLong(12, 1_700_000_000_000L + i)
            stmt.executeInsert()
        }
        sqdb.execSQL("COMMIT")
    }

    private fun seedJobs(count: Int) {
        val sqdb = db.openHelper.writableDatabase
        sqdb.execSQL(
            """INSERT INTO collection_sources
               (name, entryType, refreshMode, priority, retryBackoff, enabled, state,
                scheduleCron, batteryThresholdPercent, maxRetries, createdAt, updatedAt, version)
               VALUES ('seed_source','site','full',3,'5_min',1,'active',NULL,15,3,0,0,1)"""
        )
        sqdb.execSQL("BEGIN TRANSACTION")
        val statuses = arrayOf("scheduled", "queued", "running", "retry_waiting", "succeeded", "failed")
        val stmt = sqdb.compileStatement(
            """INSERT INTO jobs
               (sourceId, status, priority, retryCount, refreshMode, correlationId,
                scheduledAt, startedAt, finishedAt, lastError, progressChunk, totalChunks,
                createdAt, updatedAt)
               VALUES (1,?,?,0,'full',?,?,NULL,NULL,NULL,0,0,?,?)"""
        )
        for (i in 0 until count) {
            stmt.clearBindings()
            stmt.bindString(1, statuses[i % statuses.size])
            stmt.bindLong(2, (i % 5 + 1).toLong())
            stmt.bindString(3, "corr-$i")
            stmt.bindLong(4, 1_700_000_000_000L + i * 1000L)
            stmt.bindLong(5, 1_700_000_000_000L + i)
            stmt.bindLong(6, 1_700_000_000_000L + i)
            stmt.executeInsert()
        }
        sqdb.execSQL("COMMIT")
    }

    private fun seedAuditEvents(count: Int) {
        val sqdb = db.openHelper.writableDatabase
        sqdb.execSQL("BEGIN TRANSACTION")
        val stmt = sqdb.compileStatement(
            """INSERT INTO audit_events
               (correlationId, userId, action, targetType, targetId,
                severity, reason, payloadJson,
                previousEventHash, eventHash, createdAt)
               VALUES (?,?,?,?,?,?,NULL,NULL,?,?,?)"""
        )
        var prevHash = "0000000000000000000000000000000000000000000000000000000000000000"
        for (i in 0 until count) {
            stmt.clearBindings()
            stmt.bindString(1, "corr-${i / 100}")
            stmt.bindLong(2, (i % 500).toLong())
            stmt.bindString(3, "test.action")
            stmt.bindString(4, "record")
            stmt.bindString(5, i.toString())
            stmt.bindString(6, "info")
            stmt.bindString(7, prevHash)
            val hash = "%064x".format(java.math.BigInteger.valueOf(i.toLong()))
            stmt.bindString(8, hash)
            stmt.bindLong(9, 1_700_000_000_000L + i * 100L)
            stmt.executeInsert()
            prevHash = hash
        }
        sqdb.execSQL("COMMIT")
    }

    private fun seedAlerts(count: Int) {
        val sqdb = db.openHelper.writableDatabase
        sqdb.execSQL("BEGIN TRANSACTION")
        val statuses = arrayOf("open", "acknowledged", "overdue", "resolved")
        val stmt = sqdb.compileStatement(
            """INSERT INTO alerts
               (category, severity, title, body, status,
                ownerUserId, correlationId, dueAt, createdAt, updatedAt, version)
               VALUES (?,?,?,?,?,?,?,?,?,?,1)"""
        )
        for (i in 0 until count) {
            stmt.clearBindings()
            stmt.bindString(1, "job_failure")
            stmt.bindString(2, "warn")
            stmt.bindString(3, "Alert $i")
            stmt.bindString(4, "Body $i")
            stmt.bindString(5, statuses[i % statuses.size])
            stmt.bindLong(6, (i % 50).toLong())
            stmt.bindString(7, "corr-$i")
            stmt.bindLong(8, 1_700_000_000_000L + 7 * 86_400_000L)
            stmt.bindLong(9, 1_700_000_000_000L + i * 100L)
            stmt.bindLong(10, 1_700_000_000_000L + i * 100L)
            stmt.executeInsert()
        }
        sqdb.execSQL("COMMIT")
    }
}
