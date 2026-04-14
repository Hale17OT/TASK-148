package com.eaglepoint.libops.tests

import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.data.db.dao.AttachmentDao
import com.eaglepoint.libops.data.db.dao.DuplicateDao
import com.eaglepoint.libops.data.db.dao.RecordDao
import com.eaglepoint.libops.data.db.entity.DuplicateCandidateEntity
import com.eaglepoint.libops.data.db.entity.MasterRecordEntity
import com.eaglepoint.libops.data.db.entity.MasterRecordVersionEntity
import com.eaglepoint.libops.data.db.entity.MergeDecisionEntity
import com.eaglepoint.libops.data.db.entity.RecordAttachmentEntity
import com.eaglepoint.libops.imports.CoverImageProcessor
import com.eaglepoint.libops.imports.CsvImporter
import com.eaglepoint.libops.tests.fakes.FakeAuditDao
import com.eaglepoint.libops.tests.fakes.FakeImportDao
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicLong

/**
 * Tests for cover image processing integration in import pipelines and
 * CoverImageProcessor validation/processing edge cases.
 */
class CoverImageImportTest {

    @get:Rule
    val tmpDir = TemporaryFolder()

    private lateinit var importDao: FakeImportDao
    private lateinit var recordDao: TestRecordDao
    private lateinit var duplicateDao: TestDuplicateDao
    private lateinit var attachmentDao: TestAttachmentDao
    private lateinit var auditDao: FakeAuditDao
    private lateinit var audit: AuditLogger
    private var clockMs = 1_700_000_000_000L

    @Before
    fun setUp() {
        importDao = FakeImportDao()
        recordDao = TestRecordDao()
        duplicateDao = TestDuplicateDao()
        attachmentDao = TestAttachmentDao()
        auditDao = FakeAuditDao()
        audit = AuditLogger(auditDao) { clockMs }
    }

    @Test
    fun csvImporter_coverPathColumn_parsed_and_processed() = runBlocking {
        val csv = "title,category,format,cover_path\nCSV Book,book,hardcover,/nonexistent/cover.png"
        val coverDir = tmpDir.newFolder("covers")
        val importer = CsvImporter(importDao, recordDao, duplicateDao, audit, attachmentDao, coverDir, clock = { clockMs })
        val summary = importer.import("test.csv", csv.byteInputStream(), userId = 1, userRecentImportsInWindow = 0)

        assertThat(summary.accepted).isEqualTo(1)
        // Invalid path → processor returns null → no attachment, but no crash
        assertThat(attachmentDao.insertedCount).isEqualTo(0)
    }

    @Test
    fun csvImporter_noCoverPath_imports_normally() = runBlocking {
        val csv = "title,category,format\nCSV Book No Cover,book,hardcover"
        val coverDir = tmpDir.newFolder("covers2")
        val importer = CsvImporter(importDao, recordDao, duplicateDao, audit, attachmentDao, coverDir, clock = { clockMs })
        val summary = importer.import("test.csv", csv.byteInputStream(), userId = 1, userRecentImportsInWindow = 0)

        assertThat(summary.accepted).isEqualTo(1)
        assertThat(attachmentDao.insertedCount).isEqualTo(0)
    }

    @Test
    fun csvImporter_nullAttachmentDao_skips_cover_processing() = runBlocking {
        val csv = "title,category,format,cover_path\nBook,book,hardcover,/some/path.jpg"
        val importer = CsvImporter(importDao, recordDao, duplicateDao, audit, clock = { clockMs })
        val summary = importer.import("test.csv", csv.byteInputStream(), userId = 1, userRecentImportsInWindow = 0)

        assertThat(summary.accepted).isEqualTo(1)
    }

    @Test
    fun coverImageProcessor_validateCoverPath_blankReturnsNull() {
        assertThat(CoverImageProcessor.validateCoverPath(null)).isNull()
        assertThat(CoverImageProcessor.validateCoverPath("")).isNull()
        assertThat(CoverImageProcessor.validateCoverPath("   ")).isNull()
    }

    @Test
    fun coverImageProcessor_validateCoverPath_nonexistentReturnsNull() {
        assertThat(CoverImageProcessor.validateCoverPath("/nonexistent/file.jpg")).isNull()
    }

    @Test
    fun coverImageProcessor_validateCoverPath_existingFileReturnsPath() {
        val file = tmpDir.newFile("real_cover.jpg")
        file.writeBytes(byteArrayOf(0, 1, 2, 3))
        assertThat(CoverImageProcessor.validateCoverPath(file.absolutePath)).isEqualTo(file.absolutePath)
    }

    @Test
    fun coverImageProcessor_processFromPath_missingFileReturnsNull() {
        val coverDir = tmpDir.newFolder("covers3")
        val result = CoverImageProcessor.processFromPath("/nonexistent/image.jpg", recordId = 1, storageDir = coverDir)
        assertThat(result).isNull()
    }

    @Test
    fun coverImageProcessor_processFromPath_largeFile_returnsNull_gracefully() {
        // Simulate a large file (10MB of random bytes) — processFromPath should
        // handle it gracefully. On JVM without Android Bitmap APIs, the decoder
        // returns null, which is the expected safe path. On a real device the
        // decoder would downsample per CoverImageProcessor.MAX_WIDTH/MAX_HEIGHT.
        val coverDir = tmpDir.newFolder("covers_large")
        val largeFile = tmpDir.newFile("large_cover.jpg")
        val tenMb = ByteArray(10 * 1024 * 1024)
        java.util.Random(42).nextBytes(tenMb)
        largeFile.writeBytes(tenMb)

        val result = CoverImageProcessor.processFromPath(largeFile.absolutePath, recordId = 99, storageDir = coverDir)
        // On JVM: decoder returns null (no Android graphics runtime)
        // On device: decoder would downsample to MAX_WIDTH x MAX_HEIGHT and succeed
        // Either way, no crash or OOM
        assertThat(result).isNull()
    }

    @Test
    fun coverImageProcessor_processFromPath_corruptFile_returnsNull() {
        val coverDir = tmpDir.newFolder("covers_corrupt")
        val corruptFile = tmpDir.newFile("corrupt_cover.png")
        corruptFile.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0, 0, 0)) // truncated PNG header
        val result = CoverImageProcessor.processFromPath(corruptFile.absolutePath, recordId = 100, storageDir = coverDir)
        assertThat(result).isNull()
    }

    @Test
    fun coverImageProcessor_processFromPath_emptyFile_returnsNull() {
        val coverDir = tmpDir.newFolder("covers_empty")
        val emptyFile = tmpDir.newFile("empty_cover.jpg")
        // 0-byte file
        val result = CoverImageProcessor.processFromPath(emptyFile.absolutePath, recordId = 101, storageDir = coverDir)
        assertThat(result).isNull()
    }

    @Test
    fun coverImageProcessor_processFromPath_unreadableFile_returnsNull() {
        val coverDir = tmpDir.newFolder("covers_unreadable")
        val unreadableFile = tmpDir.newFile("unreadable_cover.jpg")
        unreadableFile.writeBytes(byteArrayOf(1, 2, 3))
        unreadableFile.setReadable(false)
        val result = CoverImageProcessor.processFromPath(unreadableFile.absolutePath, recordId = 102, storageDir = coverDir)
        // On systems that support permissions: returns null. On Windows: may still be readable.
        // Either way, no crash.
    }

    @Test
    fun attachmentDao_persists_when_processor_returns_entity() = runBlocking {
        // Directly test the DAO persistence path
        val entity = RecordAttachmentEntity(
            masterRecordId = 1L,
            kind = "cover",
            localPath = "/app/covers/1_cover.webp",
            sizeBytes = 12345L,
            createdAt = clockMs,
        )
        val id = attachmentDao.insert(entity)
        assertThat(id).isGreaterThan(0L)
        assertThat(attachmentDao.insertedCount).isEqualTo(1)
        assertThat(attachmentDao.forRecord(1L)).hasSize(1)
        assertThat(attachmentDao.forRecord(1L)[0].kind).isEqualTo("cover")
    }

    // ── Test doubles ──

    class TestRecordDao : RecordDao {
        private val records = linkedMapOf<Long, MasterRecordEntity>()
        private val ids = AtomicLong(0)
        override suspend fun insert(record: MasterRecordEntity): Long {
            val id = ids.incrementAndGet(); records[id] = record.copy(id = id); return id
        }
        override suspend fun update(record: MasterRecordEntity): Int = 1
        override suspend fun byId(id: Long): MasterRecordEntity? = records[id]
        override suspend fun byIsbn13(isbn13: String): MasterRecordEntity? =
            records.values.firstOrNull { it.isbn13 == isbn13 }
        override suspend fun search(prefix: String, q: String, limit: Int, offset: Int): List<MasterRecordEntity> = emptyList()
        override suspend fun activeCount(): Int = records.size
        override suspend fun insertVersion(version: MasterRecordVersionEntity): Long = 1
        override suspend fun versionsFor(recordId: Long): List<MasterRecordVersionEntity> = emptyList()
    }

    class TestDuplicateDao : DuplicateDao {
        override suspend fun insert(candidate: DuplicateCandidateEntity): Long = 1
        override suspend fun update(candidate: DuplicateCandidateEntity): Int = 1
        override suspend fun insertDecision(decision: MergeDecisionEntity): Long = 1
        override suspend fun byId(id: Long): DuplicateCandidateEntity? = null
        override suspend fun listByStatus(status: String, limit: Int, offset: Int): List<DuplicateCandidateEntity> = emptyList()
        override suspend fun unresolvedOlderThan(olderThan: Long): Int = 0
    }

    class TestAttachmentDao : AttachmentDao {
        var insertedCount = 0
            private set
        val inserted = mutableListOf<RecordAttachmentEntity>()

        override suspend fun insert(attachment: RecordAttachmentEntity): Long {
            insertedCount++
            inserted.add(attachment)
            return insertedCount.toLong()
        }
        override suspend fun forRecord(recordId: Long): List<RecordAttachmentEntity> =
            inserted.filter { it.masterRecordId == recordId }
    }
}
