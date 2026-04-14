package com.eaglepoint.libops.exports

import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.data.db.dao.AuditDao
import com.eaglepoint.libops.data.db.dao.RecordDao
import com.eaglepoint.libops.observability.ObservabilityPipeline
import com.eaglepoint.libops.observability.QueryTimer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/**
 * Writes signed export bundles (§9.12, §16).
 *
 * Format (v1):
 *   bundle/
 *     manifest.json    -- { version, created_at, creator_user_id, files[], signature{} }
 *     content.json     -- { records: [...], audit: [...] }
 *
 * The manifest's signature covers `content.json` bytes. Importers verify
 * digest + signature before accepting.
 */
class BundleExporter(
    private val recordDao: RecordDao,
    private val auditDao: AuditDao,
    private val signer: BundleSigner,
    private val audit: AuditLogger,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val observability: ObservabilityPipeline? = null,
) {

    private val queryTimer: QueryTimer? = observability?.let { QueryTimer(it) }

    data class Result(val manifestPath: String, val contentPath: String, val sha256: String)

    suspend fun exportSnapshot(
        targetDir: File,
        creatorUserId: Long,
        includeAudit: Boolean,
    ): Result = withContext(Dispatchers.IO) {
        if (!targetDir.exists()) targetDir.mkdirs()

        // Paginated export: fetch all records in batches to support datasets up to 1M+
        val recordsArr = JSONArray()
        var totalExported = 0
        var offset = 0
        val pageSize = 10_000
        while (true) {
            val page = recordDao.search(prefix = "", q = "", limit = pageSize, offset = offset)
            if (page.isEmpty()) break
            for (r in page) {
                recordsArr.put(
                    JSONObject()
                        .put("id", r.id)
                        .put("title", r.title)
                        .put("titleNormalized", r.titleNormalized)
                        .put("publisher", r.publisher)
                        .put("category", r.category)
                        .put("isbn10", r.isbn10)
                        .put("isbn13", r.isbn13)
                        .put("format", r.format)
                        .put("language", r.language)
                        .put("status", r.status)
                        .put("createdAt", r.createdAt)
                        .put("updatedAt", r.updatedAt),
                )
            }
            totalExported += page.size
            offset += page.size
            if (page.size < pageSize) break // last page
        }
        val content = JSONObject().put("records", recordsArr)
        if (includeAudit) {
            val auditArr = JSONArray()
            for (e in (queryTimer?.let { it.timed("query", "auditDao.allEventsChronological") { auditDao.allEventsChronological() } } ?: auditDao.allEventsChronological())) {
                auditArr.put(
                    JSONObject()
                        .put("id", e.id)
                        .put("correlationId", e.correlationId)
                        .put("userId", e.userId)
                        .put("action", e.action)
                        .put("targetType", e.targetType)
                        .put("targetId", e.targetId)
                        .put("severity", e.severity)
                        .put("eventHash", e.eventHash)
                        .put("previousEventHash", e.previousEventHash)
                        .put("createdAt", e.createdAt),
                )
            }
            content.put("audit", auditArr)
        }
        val contentBytes = content.toString(2).toByteArray(Charsets.UTF_8)
        val sha256 = MessageDigest.getInstance("SHA-256").digest(contentBytes).joinToString("") { "%02x".format(it) }

        val contentFile = File(targetDir, "content.json")
        contentFile.writeBytes(contentBytes)

        val signed = signer.sign(contentBytes)
        val manifest = JSONObject()
            .put("manifest_version", "1.0")
            .put("created_at", Instant.ofEpochMilli(clock()).toString())
            .put("creator_user_id", creatorUserId)
            .put(
                "content_files",
                JSONArray().put(
                    JSONObject()
                        .put("path", "content.json")
                        .put("sha256", sha256)
                        .put("size_bytes", contentBytes.size),
                ),
            )
            .put(
                "signature",
                JSONObject()
                    .put("algorithm", signed.algorithm)
                    .put("key_id", signed.keyId)
                    .put("value", android.util.Base64.encodeToString(signed.signature, android.util.Base64.NO_WRAP))
                    .put("content_sha256", sha256),
            )
        val manifestFile = File(targetDir, "manifest.json")
        manifestFile.writeBytes(manifest.toString(2).toByteArray(Charsets.UTF_8))

        audit.record(
            "export.generated",
            "export_bundle",
            targetId = targetDir.name,
            userId = creatorUserId,
            reason = "records_count=$totalExported",
            payloadJson = "{\"records\":$totalExported,\"sha256\":\"$sha256\"}",
        )
        Result(manifestPath = manifestFile.absolutePath, contentPath = contentFile.absolutePath, sha256 = sha256)
    }
}
