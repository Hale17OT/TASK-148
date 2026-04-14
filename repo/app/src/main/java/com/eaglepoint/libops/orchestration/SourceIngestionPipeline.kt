package com.eaglepoint.libops.orchestration

import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.data.db.dao.CollectionSourceDao
import com.eaglepoint.libops.data.db.dao.DuplicateDao
import com.eaglepoint.libops.data.db.dao.ImportDao
import com.eaglepoint.libops.data.db.dao.RecordDao
import com.eaglepoint.libops.data.db.entity.CollectionSourceEntity
import com.eaglepoint.libops.data.db.entity.ImportBatchEntity
import com.eaglepoint.libops.data.db.entity.ImportRowResultEntity
import com.eaglepoint.libops.data.db.entity.JobEntity
import com.eaglepoint.libops.data.db.entity.MasterRecordEntity
import com.eaglepoint.libops.domain.catalog.IsbnValidator
import com.eaglepoint.libops.domain.catalog.RecordValidator
import com.eaglepoint.libops.domain.catalog.TitleNormalizer
import com.eaglepoint.libops.domain.dedup.DuplicateDetector
import com.eaglepoint.libops.data.db.entity.CrawlRuleEntity
import com.eaglepoint.libops.data.db.entity.DuplicateCandidateEntity
import com.eaglepoint.libops.observability.ObservabilityPipeline
import com.eaglepoint.libops.observability.QueryTimer
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File

/**
 * Real source ingestion pipeline for [CollectionRunWorker].
 *
 * For each job:
 *  1. Load source configuration
 *  2. Determine entry type and refresh mode
 *  3. Generate/read catalog entries from the source
 *  4. Validate each record via [RecordValidator]
 *  5. Run duplicate detection against existing catalog
 *  6. Stage accepted records as [MasterRecordEntity]
 *  7. Track per-row outcomes and batch summary
 *  8. Write performance samples for timing
 */
class SourceIngestionPipeline(
    private val sourceDao: CollectionSourceDao,
    private val importDao: ImportDao,
    private val recordDao: RecordDao,
    private val duplicateDao: DuplicateDao,
    private val audit: AuditLogger,
    private val observability: ObservabilityPipeline,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    data class IngestionResult(
        val batchId: Long,
        val accepted: Int,
        val rejected: Int,
        val duplicates: Int,
    )

    private val queryTimer = QueryTimer(observability)

    suspend fun ingest(job: JobEntity): IngestionResult {
        val startMs = clock()
        val source = queryTimer.timed("query", "sourceDao.byId") { sourceDao.byId(job.sourceId) }
            ?: throw IllegalStateException("Source ${job.sourceId} not found for job ${job.id}")

        val batch = ImportBatchEntity(
            bundleId = null,
            filename = "job:${job.id}:${source.name}",
            format = "orchestrated",
            totalRows = 0,
            state = "received",
            createdByUserId = 0, // system-initiated
            createdAt = clock(),
            completedAt = null,
        )
        val batchId = importDao.insertBatch(batch)
        importDao.updateBatch(importDao.batchById(batchId)!!.copy(state = "validating"))

        audit.record(
            action = "ingestion.started",
            targetType = "job",
            targetId = job.id.toString(),
            reason = "source=${source.name},type=${source.entryType},mode=${source.refreshMode}",
            correlationId = job.correlationId,
        )

        val entries = generateEntries(source, job)

        var accepted = 0
        var rejected = 0
        var duplicates = 0
        val now = clock()

        for ((idx, entry) in entries.withIndex()) {
            val errors = RecordValidator.validate(
                RecordValidator.Input(
                    title = entry.title,
                    publisher = entry.publisher,
                    pubDateEpochMillis = null,
                    format = entry.format,
                    category = entry.category,
                    isbn10 = entry.isbn10,
                    isbn13 = entry.isbn13,
                    nowEpochMillis = now,
                ),
            )

            if (errors.isNotEmpty()) {
                importDao.insertRow(
                    ImportRowResultEntity(
                        batchId = batchId, rowIndex = idx,
                        outcome = "rejected_with_errors",
                        errorsJson = errors.joinToString(",", "[", "]") {
                            """{"field":"${it.field}","code":"${it.code}"}"""
                        },
                        rawPayload = entry.title,
                        stagedRecordId = null, createdAt = clock(),
                    ),
                )
                rejected++
                continue
            }

            // Duplicate detection via ISBN-13
            val normalizedIsbn13 = IsbnValidator.normalize(entry.isbn13)
            var isDuplicate = false
            if (normalizedIsbn13 != null) {
                val existing = queryTimer.timed("query", "recordDao.byIsbn13") { recordDao.byIsbn13(normalizedIsbn13) }
                if (existing != null) {
                    val verdict = DuplicateDetector.evaluate(
                        DuplicateDetector.Candidate(existing.title, existing.publisher, existing.isbn10, existing.isbn13),
                        DuplicateDetector.Candidate(entry.title, entry.publisher, entry.isbn10, entry.isbn13),
                    )
                    if (verdict != DuplicateDetector.Verdict.NotDuplicate) {
                        val score = when (verdict) {
                            is DuplicateDetector.Verdict.DuplicateCandidate -> verdict.score
                            is DuplicateDetector.Verdict.PossibleDuplicate -> verdict.score
                            else -> 0.0
                        }
                        duplicateDao.insert(
                            DuplicateCandidateEntity(
                                primaryRecordId = existing.id,
                                candidateRecordId = null,
                                primaryStagingRef = null,
                                candidateStagingRef = "job=${job.id},batch=$batchId,row=$idx",
                                score = score, algorithm = "jaro_winkler",
                                status = "detected", detectedAt = clock(),
                                reviewedByUserId = null, reviewedAt = null,
                            ),
                        )
                        duplicates++
                        isDuplicate = true
                    }
                }
            }

            // Duplicate detection via ISBN-10
            if (!isDuplicate) {
                val normalizedIsbn10 = IsbnValidator.normalize(entry.isbn10)
                if (normalizedIsbn10 != null) {
                    val existingByTitle = queryTimer.timed("query", "recordDao.search") {
                        recordDao.search(
                            prefix = TitleNormalizer.normalize(entry.title).take(20),
                            q = normalizedIsbn10, limit = 1, offset = 0,
                        )
                    }.firstOrNull()
                    if (existingByTitle != null && existingByTitle.isbn10 == normalizedIsbn10) {
                        duplicateDao.insert(
                            DuplicateCandidateEntity(
                                primaryRecordId = existingByTitle.id,
                                candidateRecordId = null,
                                primaryStagingRef = null,
                                candidateStagingRef = "job=${job.id},batch=$batchId,row=$idx",
                                score = 0.95, algorithm = "isbn10_exact",
                                status = "detected", detectedAt = clock(),
                                reviewedByUserId = null, reviewedAt = null,
                            ),
                        )
                        duplicates++
                        isDuplicate = true
                    }
                }
            }

            if (isDuplicate) {
                importDao.insertRow(
                    ImportRowResultEntity(
                        batchId = batchId, rowIndex = idx,
                        outcome = "duplicate_pending", errorsJson = null,
                        rawPayload = entry.title,
                        stagedRecordId = null, createdAt = clock(),
                    ),
                )
            } else {
                val record = MasterRecordEntity(
                    title = entry.title,
                    titleNormalized = TitleNormalizer.normalize(entry.title),
                    publisher = entry.publisher,
                    pubDate = null,
                    format = entry.format,
                    category = entry.categoryRaw,
                    isbn10 = IsbnValidator.normalize(entry.isbn10),
                    isbn13 = normalizedIsbn13,
                    language = entry.language,
                    notes = null,
                    status = "active",
                    sourceProvenanceJson = """{"job":${job.id},"source":${source.id},"batch":$batchId,"row":$idx}""",
                    createdByUserId = 0,
                    createdAt = clock(), updatedAt = clock(),
                )
                val recordId = queryTimer.timed("query", "recordDao.insert") { recordDao.insert(record) }
                importDao.insertRow(
                    ImportRowResultEntity(
                        batchId = batchId, rowIndex = idx,
                        outcome = "accepted", errorsJson = null,
                        rawPayload = entry.title,
                        stagedRecordId = recordId, createdAt = clock(),
                    ),
                )
                accepted++
            }
        }

        val totalRows = entries.size
        val finalState = when {
            accepted > 0 && rejected == 0 && duplicates == 0 -> "completed"
            accepted > 0 -> "completed"
            duplicates > 0 -> "awaiting_merge_review"
            else -> "rejected_all"
        }
        val existingBatch = queryTimer.timed("query", "importDao.batchById") { importDao.batchById(batchId)!! }
        importDao.updateBatch(
            existingBatch.copy(
                totalRows = totalRows, acceptedRows = accepted,
                rejectedRows = rejected,
                state = finalState, completedAt = clock(),
            ),
        )

        val durationMs = clock() - startMs
        observability.recordPerformanceSample("work", "ingestion:${source.name}", durationMs)

        audit.record(
            action = "ingestion.completed",
            targetType = "job",
            targetId = job.id.toString(),
            reason = "accepted=$accepted,rejected=$rejected,duplicates=$duplicates",
            correlationId = job.correlationId,
            payloadJson = """{"batch":$batchId,"total":$totalRows,"durationMs":$durationMs}""",
        )

        return IngestionResult(batchId, accepted, rejected, duplicates)
    }

    /**
     * Reads catalog entry candidates from the source based on entry type
     * and crawl rules. Each entry type has a dedicated adapter:
     *
     * - `imported_file`: reads a local JSON/CSV file path from crawl rules
     * - `site` / `ranking_list` / `artist` / `album`: reads entries from
     *   configured crawl-rule patterns or seed file paths
     *
     * Crawl rules drive the adapter:
     * - `file_path` → local file to read (JSON array or CSV)
     * - `file_format` → "json" or "csv" (defaults to json)
     * - `title_prefix` → prefix for generated entries when no file is available
     * - `publisher` → default publisher
     * - `category_override` → force category for all entries
     */
    private suspend fun generateEntries(
        source: CollectionSourceEntity,
        job: JobEntity,
    ): List<CatalogEntry> {
        val rules = queryTimer.timed("query", "sourceDao.rulesFor") { sourceDao.rulesFor(source.id) }
        val entries = readFromLocalFile(rules, source)
        if (entries.isEmpty()) {
            val hasFilePath = rules.any { it.ruleKey == "file_path" && it.include }
            if (hasFilePath) {
                throw IllegalStateException(
                    "Source '${source.name}' has a file_path rule but the file could not be read. " +
                    "Verify the file exists and is accessible."
                )
            }
            throw IllegalStateException(
                "Source '${source.name}' has no configured file_path rule. " +
                "Add a file_path crawl rule in the Source Editor before running collection jobs."
            )
        }
        return entries
    }

    /** Reads entries from a local file referenced by a `file_path` crawl rule. */
    private fun readFromLocalFile(rules: List<CrawlRuleEntity>, source: CollectionSourceEntity): List<CatalogEntry> {
        val filePath = rules.firstOrNull { it.ruleKey == "file_path" && it.include }?.ruleValue
            ?: return emptyList()
        val file = File(filePath)
        if (!file.isFile || !file.canRead()) return emptyList()

        val format = rules.firstOrNull { it.ruleKey == "file_format" }?.ruleValue ?: "json"
        val defaultPublisher = rules.firstOrNull { it.ruleKey == "publisher" }?.ruleValue
        val catOverride = rules.firstOrNull { it.ruleKey == "category_override" }?.ruleValue

        return when (format) {
            "csv" -> parseCsvFile(file, defaultPublisher, catOverride, source)
            else -> parseJsonFile(file, defaultPublisher, catOverride, source)
        }
    }

    private fun parseJsonFile(file: File, defaultPublisher: String?, catOverride: String?, source: CollectionSourceEntity): List<CatalogEntry> {
        val raw = file.readText(Charsets.UTF_8).trim()
        val records: JSONArray = when {
            raw.startsWith("[") -> runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
            raw.startsWith("{") -> {
                val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
                obj.optJSONArray("records") ?: return emptyList()
            }
            else -> return emptyList()
        }
        return (0 until records.length()).mapNotNull { i ->
            val obj = records.optJSONObject(i) ?: return@mapNotNull null
            val title = safeStr(obj, "title").ifBlank { return@mapNotNull null }
            val categoryRaw = catOverride ?: safeStr(obj, "category").ifBlank { categoryForEntryType(source.entryType) }
            CatalogEntry(
                title = title,
                publisher = safeStr(obj, "publisher").ifBlank { defaultPublisher },
                isbn10 = safeStr(obj, "isbn10").ifBlank { null },
                isbn13 = safeStr(obj, "isbn13").ifBlank { null },
                format = safeStr(obj, "format").ifBlank { null },
                category = categoryEnum(categoryRaw),
                categoryRaw = categoryRaw,
                language = safeStr(obj, "language").ifBlank { "en" },
            )
        }
    }

    private fun parseCsvFile(file: File, defaultPublisher: String?, catOverride: String?, source: CollectionSourceEntity): List<CatalogEntry> {
        val lines = file.readLines(Charsets.UTF_8)
        if (lines.size < 2) return emptyList()
        val header = lines[0].split(",").map { it.trim().lowercase() }
        val titleIdx = header.indexOf("title").takeIf { it >= 0 } ?: return emptyList()
        val publisherIdx = header.indexOf("publisher")
        val isbn10Idx = header.indexOf("isbn10")
        val isbn13Idx = header.indexOf("isbn13")
        val formatIdx = header.indexOf("format")
        val categoryIdx = header.indexOf("category")
        val languageIdx = header.indexOf("language")

        return lines.drop(1).mapNotNull { line ->
            val cols = line.split(",").map { it.trim() }
            val title = cols.getOrNull(titleIdx)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val categoryRaw = catOverride ?: cols.getOrNull(categoryIdx)?.ifBlank { categoryForEntryType(source.entryType) } ?: categoryForEntryType(source.entryType)
            CatalogEntry(
                title = title,
                publisher = cols.getOrNull(publisherIdx)?.ifBlank { defaultPublisher } ?: defaultPublisher,
                isbn10 = cols.getOrNull(isbn10Idx)?.ifBlank { null },
                isbn13 = cols.getOrNull(isbn13Idx)?.ifBlank { null },
                format = cols.getOrNull(formatIdx)?.ifBlank { null },
                category = categoryEnum(categoryRaw),
                categoryRaw = categoryRaw,
                language = cols.getOrNull(languageIdx)?.ifBlank { "en" } ?: "en",
            )
        }
    }


    /** Null-safe string extraction from JSONObject — works on both Android and JVM org.json. */
    private fun safeStr(obj: JSONObject, key: String): String {
        if (!obj.has(key)) return ""
        val v = obj.get(key)
        if (v == JSONObject.NULL || v == null) return ""
        return v.toString()
    }

    private fun categoryForEntryType(entryType: String): String = when (entryType) {
        "album", "ranking_list", "artist" -> "other"
        else -> "book"
    }

    private fun categoryEnum(raw: String): RecordValidator.Category = when (raw) {
        "journal" -> RecordValidator.Category.JOURNAL
        "other" -> RecordValidator.Category.OTHER
        else -> RecordValidator.Category.BOOK
    }

    data class CatalogEntry(
        val title: String,
        val publisher: String?,
        val isbn10: String?,
        val isbn13: String?,
        val format: String?,
        val category: RecordValidator.Category,
        val categoryRaw: String,
        val language: String?,
    )
}
