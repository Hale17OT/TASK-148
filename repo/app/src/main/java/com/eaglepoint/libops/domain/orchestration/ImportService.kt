package com.eaglepoint.libops.domain.orchestration

import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.data.db.dao.AttachmentDao
import com.eaglepoint.libops.data.db.dao.DuplicateDao
import com.eaglepoint.libops.data.db.dao.ImportDao
import com.eaglepoint.libops.data.db.dao.RecordDao
import com.eaglepoint.libops.domain.auth.Authorizer
import com.eaglepoint.libops.domain.auth.Capabilities
import com.eaglepoint.libops.imports.BundleImporter
import com.eaglepoint.libops.imports.CsvImporter
import com.eaglepoint.libops.imports.JsonImporter
import com.eaglepoint.libops.observability.ObservabilityPipeline
import java.io.File
import java.io.InputStream
import java.security.PublicKey

/**
 * Service-layer authorization boundary for import/export operations.
 *
 * Enforces [Capabilities.IMPORTS_RUN] or [Capabilities.EXPORTS_RUN] before
 * delegating to the underlying importers. This ensures capability checks
 * are enforced independently of the UI layer.
 */
class ImportService(
    private val authz: Authorizer,
    private val importDao: ImportDao,
    private val recordDao: RecordDao,
    private val duplicateDao: DuplicateDao,
    private val audit: AuditLogger,
    private val attachmentDao: AttachmentDao? = null,
    private val coverStorageDir: File? = null,
    private val observability: ObservabilityPipeline? = null,
) {

    suspend fun importCsv(
        filename: String,
        source: InputStream,
        userId: Long,
        userRecentImportsInWindow: Int,
    ): CsvImporter.Summary {
        authz.require(Capabilities.IMPORTS_RUN)
        val importer = CsvImporter(importDao, recordDao, duplicateDao, audit, attachmentDao, coverStorageDir, observability = observability)
        return importer.import(filename, source, userId, userRecentImportsInWindow)
    }

    suspend fun importJson(
        filename: String,
        source: InputStream,
        userId: Long,
        userRecentImportsInWindow: Int,
    ): JsonImporter.Summary {
        authz.require(Capabilities.IMPORTS_RUN)
        val importer = JsonImporter(importDao, recordDao, duplicateDao, audit, attachmentDao, coverStorageDir, observability = observability)
        return importer.import(filename, source, userId, userRecentImportsInWindow)
    }

    suspend fun importBundle(
        bundleDir: File,
        trustedKeys: Collection<PublicKey>,
        userId: Long,
        userRecentImportsInWindow: Int,
    ): BundleImporter.ImportResult {
        authz.require(Capabilities.IMPORTS_RUN)
        val importer = BundleImporter(importDao, recordDao, duplicateDao, audit, attachmentDao, coverStorageDir, observability = observability)
        return importer.import(bundleDir, trustedKeys, userId, userRecentImportsInWindow)
    }
}
