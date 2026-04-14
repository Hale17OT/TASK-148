package com.eaglepoint.libops.imports

import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.data.db.dao.DuplicateDao
import com.eaglepoint.libops.data.db.dao.ImportDao
import com.eaglepoint.libops.data.db.dao.RecordDao
import com.eaglepoint.libops.data.db.entity.DuplicateCandidateEntity
import com.eaglepoint.libops.data.db.entity.ImportBatchEntity
import com.eaglepoint.libops.data.db.entity.ImportRowResultEntity
import com.eaglepoint.libops.domain.catalog.IsbnValidator
import com.eaglepoint.libops.domain.catalog.RecordValidator
import com.eaglepoint.libops.domain.catalog.TitleNormalizer
import com.eaglepoint.libops.domain.dedup.DuplicateDetector
import com.eaglepoint.libops.domain.orchestration.ImportRateLimiter
import com.eaglepoint.libops.observability.ObservabilityPipeline
import com.eaglepoint.libops.observability.QueryTimer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import org.json.JSONArray

/**
 * Streaming CSV importer (§9.12). Creates an `ImportBatch`, processes rows
 * chunk-by-chunk via [BufferedReader.lineSequence], validates each record,
 * and runs duplicate detection against the live catalog.
 *
 * Row-level failures do NOT fail the batch; they are recorded in
 * `ImportRowResult` with `rejected_with_errors`. Parser-level corruption
 * (e.g. missing required column header) fails the batch via the
 * `rejected_validation_failure` state.
 */
class CsvImporter(
    private val importDao: ImportDao,
    private val recordDao: RecordDao,
    private val duplicateDao: DuplicateDao,
    private val audit: AuditLogger,
    private val attachmentDao: com.eaglepoint.libops.data.db.dao.AttachmentDao? = null,
    private val coverStorageDir: java.io.File? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val observability: ObservabilityPipeline? = null,
) {

    private val queryTimer: QueryTimer? = observability?.let { QueryTimer(it) }

    companion object {
        const val MAX_ROWS = 250_000
        const val REQUIRED_HEADER_TITLE = "title"
        val EXPECTED_COLUMNS = listOf("title", "publisher", "pub_date", "format", "category", "isbn10", "isbn13", "language", "notes", "cover_path")
    }

    data class Summary(val batchId: Long, val accepted: Int, val rejected: Int, val duplicatesSurfaced: Int, val finalState: String)

    suspend fun import(
        filename: String,
        source: InputStream,
        userId: Long,
        userRecentImportsInWindow: Int,
    ): Summary = withContext(Dispatchers.IO) {

        if (!ImportRateLimiter.allowed(userRecentImportsInWindow)) {
            val msg = "rate_limit_exceeded"
            audit.record("import.rate_limited", "import_batch", userId = userId, reason = msg, severity = AuditLogger.Severity.WARN)
            throw IllegalStateException(msg)
        }

        val now = clock()
        val batchInit = ImportBatchEntity(
            bundleId = null,
            filename = filename,
            format = "csv",
            totalRows = 0,
            state = "received",
            createdByUserId = userId,
            createdAt = now,
            completedAt = null,
        )
        val batchId = importDao.insertBatch(batchInit)
        audit.record("import.received", "import_batch", targetId = batchId.toString(), userId = userId)

        val reader = BufferedReader(InputStreamReader(source, Charsets.UTF_8))
        val header = reader.readLine()
        if (header == null) {
            finalize(batchId, 0, 0, "rejected_validation_failure", now)
            return@withContext Summary(batchId, 0, 0, 0, "rejected_validation_failure")
        }
        val columns = header.split(',').map { it.trim() }
        if (REQUIRED_HEADER_TITLE !in columns) {
            audit.record("import.header_invalid", "import_batch", targetId = batchId.toString(), userId = userId, reason = "missing_title_column", severity = AuditLogger.Severity.WARN)
            finalize(batchId, 0, 0, "rejected_validation_failure", now)
            return@withContext Summary(batchId, 0, 0, 0, "rejected_validation_failure")
        }

        importDao.updateBatch(importDao.batchById(batchId)!!.copy(state = "validating"))

        var accepted = 0
        var rejected = 0
        var duplicatesSurfaced = 0
        var rowIndex = 0

        for (line in reader.lineSequence()) {
            if (line.isBlank()) continue
            if (rowIndex >= MAX_ROWS) {
                audit.record("import.row_cap_reached", "import_batch", targetId = batchId.toString(), userId = userId, severity = AuditLogger.Severity.WARN)
                break
            }
            val fields = parseCsvLine(line)
            val fieldMap = columns.mapIndexedNotNull { i, name ->
                val v = fields.getOrNull(i)?.takeIf { it.isNotEmpty() }
                if (v != null) name to v else null
            }.toMap()

            val title = fieldMap["title"].orEmpty()
            val publisher = fieldMap["publisher"]
            val isbn10 = fieldMap["isbn10"]
            val isbn13 = fieldMap["isbn13"]
            val categoryRaw = fieldMap["category"].orEmpty()
            val category = when (categoryRaw) {
                "journal" -> RecordValidator.Category.JOURNAL
                "other" -> RecordValidator.Category.OTHER
                else -> RecordValidator.Category.BOOK
            }
            val errors = RecordValidator.validate(
                RecordValidator.Input(
                    title = title,
                    publisher = publisher,
                    pubDateEpochMillis = null,
                    format = fieldMap["format"],
                    category = category,
                    isbn10 = isbn10,
                    isbn13 = isbn13,
                    nowEpochMillis = now,
                ),
            )

            if (errors.isNotEmpty()) {
                importDao.insertRow(
                    ImportRowResultEntity(
                        batchId = batchId,
                        rowIndex = rowIndex,
                        outcome = "rejected_with_errors",
                        errorsJson = errorsToJson(errors),
                        rawPayload = line,
                        stagedRecordId = null,
                        createdAt = clock(),
                    ),
                )
                rejected++
                rowIndex++
                continue
            }

            // Duplicate detection against existing records (ISBN13 is the
            // primary key when present).
            var duplicateOutcome = false
            val normalizedIsbn13 = IsbnValidator.normalize(isbn13)
            if (normalizedIsbn13 != null) {
                val existing = queryTimer?.let { it.timed("query", "recordDao.byIsbn13") { recordDao.byIsbn13(normalizedIsbn13) } } ?: recordDao.byIsbn13(normalizedIsbn13)
                if (existing != null) {
                    val verdict = DuplicateDetector.evaluate(
                        DuplicateDetector.Candidate(existing.title, existing.publisher, existing.isbn10, existing.isbn13),
                        DuplicateDetector.Candidate(title, publisher, isbn10, isbn13),
                    )
                    when (verdict) {
                        is DuplicateDetector.Verdict.DuplicateCandidate -> {
                            duplicateDao.insert(
                                DuplicateCandidateEntity(
                                    primaryRecordId = existing.id,
                                    candidateRecordId = null,
                                    primaryStagingRef = null,
                                    candidateStagingRef = "batch=$batchId,row=$rowIndex",
                                    score = verdict.score,
                                    algorithm = "jaro_winkler",
                                    status = "detected",
                                    detectedAt = clock(),
                                    reviewedByUserId = null,
                                    reviewedAt = null,
                                ),
                            )
                            duplicatesSurfaced++
                            duplicateOutcome = true
                        }
                        is DuplicateDetector.Verdict.PossibleDuplicate -> {
                            duplicateDao.insert(
                                DuplicateCandidateEntity(
                                    primaryRecordId = existing.id,
                                    candidateRecordId = null,
                                    primaryStagingRef = null,
                                    candidateStagingRef = "batch=$batchId,row=$rowIndex",
                                    score = verdict.score,
                                    algorithm = "jaro_winkler_no_isbn",
                                    status = "detected",
                                    detectedAt = clock(),
                                    reviewedByUserId = null,
                                    reviewedAt = null,
                                ),
                            )
                            duplicatesSurfaced++
                            duplicateOutcome = true
                        }
                        DuplicateDetector.Verdict.NotDuplicate -> Unit
                    }
                }
            }

            // ISBN-10 fallback: check for existing record with matching ISBN-10
            if (!duplicateOutcome && isbn10 != null) {
                val normalizedIsbn10 = IsbnValidator.normalize(isbn10)
                if (normalizedIsbn10 != null) {
                    val existingBy10 = (queryTimer?.let { it.timed("query", "recordDao.search") { recordDao.search(
                        prefix = TitleNormalizer.normalize(title).take(20),
                        q = normalizedIsbn10, limit = 1, offset = 0,
                    ) } } ?: recordDao.search(
                        prefix = TitleNormalizer.normalize(title).take(20),
                        q = normalizedIsbn10, limit = 1, offset = 0,
                    )).firstOrNull()
                    if (existingBy10 != null && existingBy10.isbn10 == normalizedIsbn10) {
                        duplicateDao.insert(
                            DuplicateCandidateEntity(
                                primaryRecordId = existingBy10.id,
                                candidateRecordId = null,
                                primaryStagingRef = null,
                                candidateStagingRef = "batch=$batchId,row=$rowIndex",
                                score = 0.95,
                                algorithm = "isbn10_exact",
                                status = "detected",
                                detectedAt = clock(),
                                reviewedByUserId = null,
                                reviewedAt = null,
                            ),
                        )
                        duplicatesSurfaced++
                        duplicateOutcome = true
                    }
                }
            }

            if (duplicateOutcome) {
                importDao.insertRow(
                    ImportRowResultEntity(
                        batchId = batchId,
                        rowIndex = rowIndex,
                        outcome = "duplicate_pending",
                        errorsJson = null,
                        rawPayload = line,
                        stagedRecordId = null,
                        createdAt = clock(),
                    ),
                )
            } else {
                val stagedRecord = com.eaglepoint.libops.data.db.entity.MasterRecordEntity(
                    title = title,
                    titleNormalized = TitleNormalizer.normalize(title),
                    publisher = publisher,
                    pubDate = null,
                    format = fieldMap["format"],
                    category = categoryRaw.ifEmpty { "book" },
                    isbn10 = IsbnValidator.normalize(isbn10),
                    isbn13 = normalizedIsbn13,
                    language = fieldMap["language"],
                    notes = fieldMap["notes"],
                    status = "active",
                    sourceProvenanceJson = "{\"batchId\":$batchId,\"rowIndex\":$rowIndex}",
                    createdByUserId = userId,
                    createdAt = clock(),
                    updatedAt = clock(),
                )
                val stagedId = recordDao.insert(stagedRecord)
                // Process cover image if path is provided (§18 memory-safe downsample + LRU)
                val coverPath = fieldMap["cover_path"]
                if (coverPath != null && attachmentDao != null && coverStorageDir != null) {
                    val attachment = CoverImageProcessor.processFromPath(coverPath, stagedId, coverStorageDir, clock)
                    if (attachment != null) {
                        attachmentDao.insert(attachment)
                    }
                }
                importDao.insertRow(
                    ImportRowResultEntity(
                        batchId = batchId,
                        rowIndex = rowIndex,
                        outcome = "accepted",
                        errorsJson = null,
                        rawPayload = line,
                        stagedRecordId = stagedId,
                        createdAt = clock(),
                    ),
                )
                accepted++
            }
            rowIndex++
        }

        val finalState = when {
            accepted > 0 && rejected == 0 && duplicatesSurfaced == 0 -> "accepted_all"
            accepted > 0 && (rejected > 0 || duplicatesSurfaced > 0) -> "accepted_partial"
            accepted == 0 && duplicatesSurfaced > 0 -> "awaiting_merge_review"
            else -> "rejected_all"
        }
        finalize(batchId, rowIndex, accepted, finalState, now)
        audit.record(
            "import.completed",
            "import_batch",
            targetId = batchId.toString(),
            userId = userId,
            reason = finalState,
            payloadJson = "{\"accepted\":$accepted,\"rejected\":$rejected,\"duplicates\":$duplicatesSurfaced}",
        )
        Summary(batchId, accepted, rejected, duplicatesSurfaced, finalState)
    }

    private suspend fun finalize(batchId: Long, totalRows: Int, accepted: Int, state: String, createdAt: Long) {
        val existing = (queryTimer?.let { it.timed("query", "importDao.batchById") { importDao.batchById(batchId) } } ?: importDao.batchById(batchId)) ?: return
        importDao.updateBatch(
            existing.copy(
                totalRows = totalRows,
                acceptedRows = accepted,
                rejectedRows = totalRows - accepted,
                state = if (state == "accepted_all" || state == "accepted_partial") "completed" else state,
                completedAt = clock(),
            ),
        )
    }

    /** Minimal CSV parser supporting quoted fields with escaped quotes. */
    internal fun parseCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        var inQuotes = false
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"'); i += 2
                }
                c == '"' -> { inQuotes = !inQuotes; i++ }
                !inQuotes && c == ',' -> { out.add(sb.toString()); sb.clear(); i++ }
                else -> { sb.append(c); i++ }
            }
        }
        out.add(sb.toString())
        return out.map { it.trim() }
    }

    private fun errorsToJson(errors: List<com.eaglepoint.libops.domain.FieldError>): String {
        val arr = JSONArray()
        errors.forEach { e ->
            arr.put(org.json.JSONObject().apply {
                put("field", e.field); put("code", e.code); put("message", e.message)
            })
        }
        return arr.toString()
    }
}
