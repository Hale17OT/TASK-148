package com.eaglepoint.libops.imports

import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.data.db.dao.DuplicateDao
import com.eaglepoint.libops.data.db.dao.ImportDao
import com.eaglepoint.libops.data.db.dao.RecordDao
import com.eaglepoint.libops.data.db.entity.DuplicateCandidateEntity
import com.eaglepoint.libops.data.db.entity.ImportBatchEntity
import com.eaglepoint.libops.data.db.entity.ImportRowResultEntity
import com.eaglepoint.libops.data.db.entity.MasterRecordEntity
import com.eaglepoint.libops.domain.FieldError
import com.eaglepoint.libops.domain.catalog.IsbnValidator
import com.eaglepoint.libops.domain.catalog.RecordValidator
import com.eaglepoint.libops.domain.catalog.TitleNormalizer
import com.eaglepoint.libops.domain.dedup.DuplicateDetector
import com.eaglepoint.libops.domain.orchestration.ImportRateLimiter
import com.eaglepoint.libops.observability.ObservabilityPipeline
import com.eaglepoint.libops.observability.QueryTimer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.InputStream

/**
 * JSON-array and `{"schema_version":..,"records":[...]}` importer.
 *
 * Shares the staging + duplicate-detection semantics of [CsvImporter]; any
 * future format (NDJSON, MARC etc.) should delegate to the same
 * validate/stage/dedupe pipeline rather than reimplement it.
 */
class JsonImporter(
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

    data class Summary(val batchId: Long, val accepted: Int, val rejected: Int, val duplicatesSurfaced: Int, val finalState: String, val schemaVersion: String?)

    suspend fun import(
        filename: String,
        source: InputStream,
        userId: Long,
        userRecentImportsInWindow: Int,
    ): Summary = withContext(Dispatchers.IO) {

        if (!ImportRateLimiter.allowed(userRecentImportsInWindow)) {
            audit.record("import.rate_limited", "import_batch", userId = userId, reason = "json_over_limit", severity = AuditLogger.Severity.WARN)
            throw IllegalStateException("rate_limit_exceeded")
        }

        val now = clock()
        val initial = ImportBatchEntity(
            bundleId = null,
            filename = filename,
            format = "json",
            totalRows = 0,
            state = "received",
            createdByUserId = userId,
            createdAt = now,
            completedAt = null,
        )
        val batchId = importDao.insertBatch(initial)
        audit.record("import.received", "import_batch", targetId = batchId.toString(), userId = userId)

        val raw = source.bufferedReader(Charsets.UTF_8).readText()
        val root = runCatching { JSONTokener(raw).nextValue() }.getOrNull()
        if (root == null) {
            finalize(batchId, 0, 0, "rejected_invalid_bundle")
            return@withContext Summary(batchId, 0, 0, 0, "rejected_invalid_bundle", null)
        }

        val records: JSONArray
        val schemaVersion: String?
        when (root) {
            is JSONObject -> {
                schemaVersion = root.optString("schema_version").ifBlank { null }
                val arr = root.optJSONArray("records")
                if (arr == null) {
                    finalize(batchId, 0, 0, "rejected_validation_failure")
                    return@withContext Summary(batchId, 0, 0, 0, "rejected_validation_failure", schemaVersion)
                }
                records = arr
            }
            is JSONArray -> {
                schemaVersion = null
                records = root
            }
            else -> {
                finalize(batchId, 0, 0, "rejected_validation_failure")
                return@withContext Summary(batchId, 0, 0, 0, "rejected_validation_failure", null)
            }
        }

        importDao.updateBatch(importDao.batchById(batchId)!!.copy(state = "validating"))

        var accepted = 0
        var rejected = 0
        var duplicatesSurfaced = 0
        val total = minOf(records.length(), CsvImporter.MAX_ROWS)

        for (i in 0 until total) {
            val row = records.optJSONObject(i)
            if (row == null) {
                importDao.insertRow(
                    ImportRowResultEntity(
                        batchId = batchId,
                        rowIndex = i,
                        outcome = "rejected_with_errors",
                        errorsJson = "[{\"code\":\"not_object\"}]",
                        rawPayload = records.opt(i)?.toString().orEmpty(),
                        stagedRecordId = null,
                        createdAt = clock(),
                    ),
                )
                rejected++
                continue
            }

            val title = row.optString("title").takeIf { it.isNotBlank() }
            val publisher = row.optString("publisher").ifBlank { null }
            val isbn10 = row.optString("isbn10").ifBlank { null }
            val isbn13 = row.optString("isbn13").ifBlank { null }
            val format = row.optString("format").ifBlank { null }
            val language = row.optString("language").ifBlank { null }
            val notes = row.optString("notes").ifBlank { null }
            val coverPath = row.optString("cover_path").ifBlank { null }
            val categoryRaw = row.optString("category").ifBlank { "book" }
            val category = when (categoryRaw) {
                "journal" -> RecordValidator.Category.JOURNAL
                "other" -> RecordValidator.Category.OTHER
                else -> RecordValidator.Category.BOOK
            }

            val errors: List<FieldError> = RecordValidator.validate(
                RecordValidator.Input(
                    title = title,
                    publisher = publisher,
                    pubDateEpochMillis = null,
                    format = format,
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
                        rowIndex = i,
                        outcome = "rejected_with_errors",
                        errorsJson = errorsToJson(errors),
                        rawPayload = row.toString(),
                        stagedRecordId = null,
                        createdAt = clock(),
                    ),
                )
                rejected++
                continue
            }

            val normalizedIsbn13 = IsbnValidator.normalize(isbn13)
            var duplicateOutcome = false
            if (normalizedIsbn13 != null) {
                val existing = queryTimer?.let { it.timed("query", "recordDao.byIsbn13") { recordDao.byIsbn13(normalizedIsbn13) } } ?: recordDao.byIsbn13(normalizedIsbn13)
                if (existing != null) {
                    val verdict = DuplicateDetector.evaluate(
                        DuplicateDetector.Candidate(existing.title, existing.publisher, existing.isbn10, existing.isbn13),
                        DuplicateDetector.Candidate(title!!, publisher, isbn10, isbn13),
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
                                candidateStagingRef = "batch=$batchId,row=$i",
                                score = score,
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
                }
            }

            // ISBN-10 fallback: check for existing record with matching ISBN-10
            if (!duplicateOutcome && isbn10 != null) {
                val normalizedIsbn10 = IsbnValidator.normalize(isbn10)
                if (normalizedIsbn10 != null) {
                    val existingBy10 = (queryTimer?.let { it.timed("query", "recordDao.search") { recordDao.search(
                        prefix = TitleNormalizer.normalize(title!!).take(20),
                        q = normalizedIsbn10, limit = 1, offset = 0,
                    ) } } ?: recordDao.search(
                        prefix = TitleNormalizer.normalize(title!!).take(20),
                        q = normalizedIsbn10, limit = 1, offset = 0,
                    )).firstOrNull()
                    if (existingBy10 != null && existingBy10.isbn10 == normalizedIsbn10) {
                        duplicateDao.insert(
                            DuplicateCandidateEntity(
                                primaryRecordId = existingBy10.id,
                                candidateRecordId = null,
                                primaryStagingRef = null,
                                candidateStagingRef = "batch=$batchId,row=$i",
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
                        rowIndex = i,
                        outcome = "duplicate_pending",
                        errorsJson = null,
                        rawPayload = row.toString(),
                        stagedRecordId = null,
                        createdAt = clock(),
                    ),
                )
            } else {
                val staged = MasterRecordEntity(
                    title = title!!,
                    titleNormalized = TitleNormalizer.normalize(title),
                    publisher = publisher,
                    pubDate = null,
                    format = format,
                    category = categoryRaw,
                    isbn10 = IsbnValidator.normalize(isbn10),
                    isbn13 = normalizedIsbn13,
                    language = language,
                    notes = notes,
                    status = "active",
                    sourceProvenanceJson = "{\"batchId\":$batchId,\"rowIndex\":$i,\"format\":\"json\"}",
                    createdByUserId = userId,
                    createdAt = clock(),
                    updatedAt = clock(),
                )
                val stagedId = recordDao.insert(staged)
                // Process cover image if path is provided (§18 memory-safe downsample + LRU)
                if (coverPath != null && attachmentDao != null && coverStorageDir != null) {
                    val attachment = CoverImageProcessor.processFromPath(coverPath, stagedId, coverStorageDir, clock)
                    if (attachment != null) {
                        attachmentDao.insert(attachment)
                    }
                }
                importDao.insertRow(
                    ImportRowResultEntity(
                        batchId = batchId,
                        rowIndex = i,
                        outcome = "accepted",
                        errorsJson = null,
                        rawPayload = row.toString(),
                        stagedRecordId = stagedId,
                        createdAt = clock(),
                    ),
                )
                accepted++
            }
        }

        val finalState = when {
            accepted > 0 && rejected == 0 && duplicatesSurfaced == 0 -> "accepted_all"
            accepted > 0 && (rejected > 0 || duplicatesSurfaced > 0) -> "accepted_partial"
            accepted == 0 && duplicatesSurfaced > 0 -> "awaiting_merge_review"
            else -> "rejected_all"
        }
        finalize(batchId, total, accepted, finalState)
        audit.record(
            "import.completed",
            "import_batch",
            targetId = batchId.toString(),
            userId = userId,
            reason = finalState,
            payloadJson = "{\"format\":\"json\",\"accepted\":$accepted,\"rejected\":$rejected,\"duplicates\":$duplicatesSurfaced,\"schema\":\"${schemaVersion.orEmpty()}\"}",
        )
        Summary(batchId, accepted, rejected, duplicatesSurfaced, finalState, schemaVersion)
    }

    private suspend fun finalize(batchId: Long, totalRows: Int, accepted: Int, state: String) {
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

    private fun errorsToJson(errors: List<FieldError>): String {
        val arr = JSONArray()
        errors.forEach { e ->
            arr.put(JSONObject().apply {
                put("field", e.field); put("code", e.code); put("message", e.message)
            })
        }
        return arr.toString()
    }
}
