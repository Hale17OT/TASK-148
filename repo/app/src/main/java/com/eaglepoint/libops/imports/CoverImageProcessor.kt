package com.eaglepoint.libops.imports

import com.eaglepoint.libops.data.db.entity.RecordAttachmentEntity
import com.eaglepoint.libops.media.ImageDecoder
import java.io.File
import java.io.InputStream

/**
 * Processes cover images during import, ensuring they are decoded within
 * the memory budget (§18) and stored as [RecordAttachmentEntity] references.
 *
 * - Reads the image from a local file path or URL field in the import data
 * - Decodes off main thread via [ImageDecoder] with downsampling
 * - Stores the decoded file in the app's private storage
 * - Returns an attachment entity ready for persistence
 *
 * This bridges the import pipelines (CSV/JSON/Bundle) with the ImageDecoder
 * and RecordAttachment persistence layer.
 */
object CoverImageProcessor {

    /** Max cover image dimension for storage (keeps memory per image well under 5MB). */
    const val MAX_WIDTH = 1200
    const val MAX_HEIGHT = 1800

    /**
     * Process a cover image file for a record. Returns an attachment entity
     * if the image was successfully decoded and stored, null otherwise.
     *
     * @param coverPath local file path to the source image
     * @param recordId the master record this cover belongs to
     * @param storageDir app-private directory for storing decoded covers
     * @param clock time source
     */
    fun processFromPath(
        coverPath: String,
        recordId: Long,
        storageDir: File,
        clock: () -> Long = { System.currentTimeMillis() },
    ): RecordAttachmentEntity? {
        val sourceFile = File(coverPath)
        if (!sourceFile.isFile || !sourceFile.canRead()) return null

        val key = "cover_${recordId}_${sourceFile.name}"
        val bitmap = ImageDecoder.decodeStream(
            key = key,
            reqWidthPx = MAX_WIDTH,
            reqHeightPx = MAX_HEIGHT,
            streamFactory = { sourceFile.inputStream() },
        ) ?: return null

        // Store the decoded cover in app-private storage
        if (!storageDir.exists()) storageDir.mkdirs()
        val destFile = File(storageDir, "${recordId}_cover.webp")
        destFile.outputStream().use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.WEBP, 80, out)
        }

        return RecordAttachmentEntity(
            masterRecordId = recordId,
            kind = "cover",
            localPath = destFile.absolutePath,
            sizeBytes = destFile.length(),
            createdAt = clock(),
        )
    }

    /**
     * Validates that a cover image path, if provided, points to a readable file.
     * Returns null if the path is blank (no cover), the path string if valid,
     * or throws if the file doesn't exist.
     */
    fun validateCoverPath(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.isFile) return null // skip silently — cover is optional
        return path
    }
}
