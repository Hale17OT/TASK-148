package com.eaglepoint.libops.imports

import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.data.db.dao.DuplicateDao
import com.eaglepoint.libops.data.db.dao.ImportDao
import com.eaglepoint.libops.data.db.dao.RecordDao
import com.eaglepoint.libops.data.db.entity.DuplicateCandidateEntity
import com.eaglepoint.libops.data.db.entity.ImportBatchEntity
import com.eaglepoint.libops.data.db.entity.ImportRowResultEntity
import com.eaglepoint.libops.data.db.entity.ImportedBundleEntity
import com.eaglepoint.libops.data.db.entity.MasterRecordEntity
import com.eaglepoint.libops.domain.FieldError
import com.eaglepoint.libops.domain.catalog.IsbnValidator
import com.eaglepoint.libops.domain.catalog.RecordValidator
import com.eaglepoint.libops.domain.catalog.TitleNormalizer
import com.eaglepoint.libops.domain.dedup.DuplicateDetector
import com.eaglepoint.libops.domain.orchestration.ImportRateLimiter
import com.eaglepoint.libops.exports.BundleVerifier
import com.eaglepoint.libops.observability.ObservabilityPipeline
import com.eaglepoint.libops.observability.QueryTimer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.PublicKey

/**
 * Signed-bundle import processor (§9.12).
 *
 * Workflow:
 *  1. Verify manifest digest and signature via [BundleVerifier]
 *  2. Persist [ImportedBundleEntity] for deduplication
 *  3. Parse content.json records
 *  4. Validate each record through [RecordValidator]
 *  5. Run duplicate detection
 *  6. Ingest accepted records as [MasterRecordEntity]
 */
class BundleImporter(
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

    data class Summary(
        val batchId: Long,
        val bundleEntityId: Long,
        val accepted: Int,
        val rejected: Int,
        val duplicatesSurfaced: Int,
        val finalState: String,
        val manifestVersion: String,
    )

    sealed interface ImportResult {
        data class Success(val summary: Summary) : ImportResult
        data class VerificationFailed(val reason: String) : ImportResult
        data class AlreadyImported(val checksum: String) : ImportResult
        data object RateLimited : ImportResult
    }

    suspend fun import(
        bundleDir: File,
        publicKey: PublicKey,
        userId: Long,
        userRecentImportsInWindow: Int,
    ): ImportResult = import(bundleDir, listOf(publicKey), userId, userRecentImportsInWindow)

    suspend fun import(
        bundleDir: File,
        trustedKeys: Collection<PublicKey>,
        userId: Long,
        userRecentImportsInWindow: Int,
    ): ImportResult = withContext(Dispatchers.IO) {
        // Step 0: Enforce per-user rate limit (max 30 imports/hour)
        if (!ImportRateLimiter.allowed(userRecentImportsInWindow)) {
            audit.record(
                "bundle_import.rate_limited", "import_bundle",
                userId = userId, reason = "bundle_over_limit",
                severity = AuditLogger.Severity.WARN,
            )
            return@withContext ImportResult.RateLimited
        }

        // Step 1: Verify the signed bundle against all trusted keys
        val verifyResult = BundleVerifier.verifyWithTrustedKeys(bundleDir, trustedKeys)
        val (sha256, manifestVersion) = when (verifyResult) {
            is BundleVerifier.Result.Ok -> verifyResult.sha256 to verifyResult.manifestVersion
            is BundleVerifier.Result.InvalidManifest -> {
                audit.record(
                    "bundle_import.rejected", "import_bundle",
                    userId = userId, reason = "invalid_manifest: ${verifyResult.reason}",
                    severity = AuditLogger.Severity.WARN,
                )
                return@withContext ImportResult.VerificationFailed("Invalid manifest: ${verifyResult.reason}")
            }
            BundleVerifier.Result.DigestMismatch -> {
                audit.record(
                    "bundle_import.rejected", "import_bundle",
                    userId = userId, reason = "digest_mismatch",
                    severity = AuditLogger.Severity.CRITICAL,
                )
                return@withContext ImportResult.VerificationFailed("Content digest mismatch — bundle tampered")
            }
            BundleVerifier.Result.SignatureInvalid -> {
                audit.record(
                    "bundle_import.rejected", "import_bundle",
                    userId = userId, reason = "signature_invalid",
                    severity = AuditLogger.Severity.CRITICAL,
                )
                return@withContext ImportResult.VerificationFailed("Signature verification failed")
            }
            is BundleVerifier.Result.ContentMissing -> {
                audit.record(
                    "bundle_import.rejected", "import_bundle",
                    userId = userId, reason = "content_missing: ${verifyResult.path}",
                    severity = AuditLogger.Severity.WARN,
                )
                return@withContext ImportResult.VerificationFailed("Content file missing: ${verifyResult.path}")
            }
        }

        // Step 2: Check for duplicate bundle import
        val existing = queryTimer?.let { it.timed("query", "importDao.bundleByChecksum") { importDao.bundleByChecksum(sha256) } } ?: importDao.bundleByChecksum(sha256)
        if (existing != null) {
            audit.record(
                "bundle_import.duplicate", "import_bundle",
                userId = userId, reason = "checksum=$sha256",
            )
            return@withContext ImportResult.AlreadyImported(sha256)
        }

        // Step 3: Persist ImportedBundleEntity
        val bundleEntity = ImportedBundleEntity(
            manifestVersion = manifestVersion,
            checksum = sha256,
            signatureValid = true,
            creatorUserId = userId,
            receivedAt = clock(),
        )
        val bundleEntityId = importDao.insertBundle(bundleEntity)

        // Step 4: Parse content.json records
        val contentFile = File(bundleDir, "content.json")
        val contentJson = JSONObject(contentFile.readText(Charsets.UTF_8))
        val records = contentJson.optJSONArray("records")

        val now = clock()
        val batch = ImportBatchEntity(
            bundleId = bundleEntityId,
            filename = "bundle:${bundleDir.name}",
            format = "signed_bundle",
            totalRows = records?.length() ?: 0,
            state = "received",
            createdByUserId = userId,
            createdAt = now,
            completedAt = null,
        )
        val batchId = importDao.insertBatch(batch)
        audit.record("import.received", "import_batch", targetId = batchId.toString(), userId = userId, reason = "signed_bundle")

        if (records == null || records.length() == 0) {
            finalize(batchId, 0, 0, "rejected_invalid_bundle")
            return@withContext ImportResult.Success(
                Summary(batchId, bundleEntityId, 0, 0, 0, "rejected_invalid_bundle", manifestVersion)
            )
        }

        importDao.updateBatch(importDao.batchById(batchId)!!.copy(state = "validating"))

        // Step 5: Validate and ingest each record
        var accepted = 0
        var rejected = 0
        var duplicatesSurfaced = 0
        val total = minOf(records.length(), CsvImporter.MAX_ROWS)

        for (i in 0 until total) {
            val row = records.optJSONObject(i)
            if (row == null) {
                importDao.insertRow(
                    ImportRowResultEntity(
                        batchId = batchId, rowIndex = i,
                        outcome = "rejected_with_errors",
                        errorsJson = "[{\"code\":\"not_object\"}]",
                        rawPayload = records.opt(i)?.toString().orEmpty(),
                        stagedRecordId = null, createdAt = clock(),
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
                    title = title, publisher = publisher,
                    pubDateEpochMillis = null, format = format,
                    category = category, isbn10 = isbn10, isbn13 = isbn13,
                    nowEpochMillis = now,
                ),
            )
            if (errors.isNotEmpty()) {
                importDao.insertRow(
                    ImportRowResultEntity(
                        batchId = batchId, rowIndex = i,
                        outcome = "rejected_with_errors",
                        errorsJson = errorsToJson(errors),
                        rawPayload = row.toString(),
                        stagedRecordId = null, createdAt = clock(),
                    ),
                )
                rejected++
                continue
            }

            val normalizedIsbn13 = IsbnValidator.normalize(isbn13)
            var duplicateOutcome = false
            if (normalizedIsbn13 != null) {
                val existingRecord = queryTimer?.let { it.timed("query", "recordDao.byIsbn13") { recordDao.byIsbn13(normalizedIsbn13) } } ?: recordDao.byIsbn13(normalizedIsbn13)
                if (existingRecord != null) {
                    val verdict = DuplicateDetector.evaluate(
                        DuplicateDetector.Candidate(existingRecord.title, existingRecord.publisher, existingRecord.isbn10, existingRecord.isbn13),
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
                                primaryRecordId = existingRecord.id,
                                candidateRecordId = null,
                                primaryStagingRef = null,
                                candidateStagingRef = "batch=$batchId,row=$i",
                                score = score, algorithm = "jaro_winkler",
                                status = "detected", detectedAt = clock(),
                                reviewedByUserId = null, reviewedAt = null,
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
                        batchId = batchId, rowIndex = i,
                        outcome = "duplicate_pending",
                        errorsJson = null, rawPayload = row.toString(),
                        stagedRecordId = null, createdAt = clock(),
                    ),
                )
            } else {
                val staged = MasterRecordEntity(
                    title = title!!,
                    titleNormalized = TitleNormalizer.normalize(title),
                    publisher = publisher, pubDate = null,
                    format = format, category = categoryRaw,
                    isbn10 = IsbnValidator.normalize(isbn10),
                    isbn13 = normalizedIsbn13,
                    language = language, notes = notes,
                    status = "active",
                    sourceProvenanceJson = "{\"batchId\":$batchId,\"rowIndex\":$i,\"format\":\"signed_bundle\",\"bundleChecksum\":\"$sha256\"}",
                    createdByUserId = userId,
                    createdAt = clock(), updatedAt = clock(),
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
                        batchId = batchId, rowIndex = i,
                        outcome = "accepted", errorsJson = null,
                        rawPayload = row.toString(),
                        stagedRecordId = stagedId, createdAt = clock(),
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
            "bundle_import.completed", "import_batch",
            targetId = batchId.toString(), userId = userId,
            reason = finalState,
            payloadJson = "{\"format\":\"signed_bundle\",\"accepted\":$accepted,\"rejected\":$rejected,\"duplicates\":$duplicatesSurfaced,\"manifest\":\"$manifestVersion\",\"checksum\":\"$sha256\"}",
        )
        ImportResult.Success(
            Summary(batchId, bundleEntityId, accepted, rejected, duplicatesSurfaced, finalState, manifestVersion)
        )
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
        val arr = org.json.JSONArray()
        errors.forEach { e ->
            arr.put(JSONObject().apply {
                put("field", e.field); put("code", e.code); put("message", e.message)
            })
        }
        return arr.toString()
    }
}
