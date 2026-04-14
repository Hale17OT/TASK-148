package com.eaglepoint.libops.ui.imports

import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.eaglepoint.libops.LibOpsApp
import com.eaglepoint.libops.databinding.ActivityFeatureBinding
import com.eaglepoint.libops.domain.auth.Authorizer
import com.eaglepoint.libops.domain.auth.Capabilities
import com.eaglepoint.libops.domain.orchestration.ImportRateLimiter
import com.eaglepoint.libops.domain.orchestration.ImportService
import com.eaglepoint.libops.observability.QueryTimer
import com.eaglepoint.libops.exports.BundleExporter
import com.eaglepoint.libops.exports.BundleSigner
import com.eaglepoint.libops.exports.BundleVerifier
import com.eaglepoint.libops.imports.BundleImporter
import com.eaglepoint.libops.imports.CsvImporter
import com.eaglepoint.libops.imports.JsonImporter
import com.eaglepoint.libops.security.SigningKeyStore
import com.eaglepoint.libops.ui.AuthorizationGate
import com.eaglepoint.libops.ui.FeatureScreenHelper
import com.eaglepoint.libops.ui.TwoLineRow
import com.eaglepoint.libops.ui.chipToneFor
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ImportsActivity : FragmentActivity() {

    private lateinit var binding: ActivityFeatureBinding
    private lateinit var helper: FeatureScreenHelper
    private lateinit var signingKeyStore: SigningKeyStore
    private var userId: Long = -1L
    private var canExport = false
    private lateinit var importService: ImportService

    private val signer by lazy { BundleSigner(signingKeyStore.keyProvider()) }

    private val pickCsvFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFromUri(uri, "csv")
    }

    private val pickJsonFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFromUri(uri, "json")
    }

    private val pickBundleDir = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri != null) importBundleFromTree(treeUri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = AuthorizationGate.requireAccess(this, Capabilities.IMPORTS_RUN) ?: return
        binding = ActivityFeatureBinding.inflate(layoutInflater)
        setContentView(binding.root)
        helper = FeatureScreenHelper(this, binding)
        userId = session.userId
        signingKeyStore = (application as LibOpsApp).signingKeyStore
        val app = application as LibOpsApp
        val authz = Authorizer(session.capabilities)
        canExport = authz.has(Capabilities.EXPORTS_RUN)
        val coverDir = File(filesDir, "covers")
        importService = ImportService(authz, app.db.importDao(), app.db.recordDao(), app.db.duplicateDao(), app.auditLogger, app.db.attachmentDao(), coverDir, observability = app.observabilityPipeline)

        helper.setup(
            eyebrow = "Integration",
            title = "Imports & Exports",
            subtitle = "CSV, JSON, and signed bundles — offline only.",
            fabLabel = "Choose action",
            onFabClick = { showActionMenu() },
        )
        lifecycleScope.launch { refresh() }
    }

    override fun onResume() {
        super.onResume()
        if (::helper.isInitialized) lifecycleScope.launch { refresh() }
    }

    private suspend fun refresh() {
        val app = application as LibOpsApp
        val queryTimer = QueryTimer(app.observabilityPipeline)
        val batches = withContext(Dispatchers.IO) {
            queryTimer.timed("query", "importDao.recentBatches") {
                app.db.importDao().recentBatches(limit = 500, offset = 0)
            }
        }
        val rows = batches.map { b ->
            TwoLineRow(
                id = "batch-${b.id}",
                primary = "${b.filename}  (#${b.id})",
                secondary = "${b.format} • rows=${b.totalRows} • accepted=${b.acceptedRows} • rejected=${b.rejectedRows}",
                chipLabel = b.state.replace('_', ' '),
                chipTone = chipToneFor(b.state),
            )
        }
        helper.submit(
            rows,
            emptyTitle = "No import batches",
            emptyBody = "Tap \u201CChoose action\u201D to run a sample CSV or JSON import.",
        )
    }

    private fun showActionMenu() {
        val items = buildList {
            add("Import CSV from file")
            add("Import JSON from file")
            add("Import signed bundle from folder")
            add("Import sample CSV")
            add("Import sample JSON")
            add("Re-import last exported bundle")
            if (canExport) add("Export signed bundle")
            if (canExport) add("Verify last exported bundle")
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Action")
            .setItems(items) { _, i ->
                when (items[i]) {
                    "Import CSV from file" -> pickCsvFile.launch(arrayOf("text/csv", "text/comma-separated-values", "application/csv", "*/*"))
                    "Import JSON from file" -> pickJsonFile.launch(arrayOf("application/json", "text/json", "*/*"))
                    "Import signed bundle from folder" -> pickBundleDir.launch(null)
                    "Import sample CSV" -> runCsvImport()
                    "Import sample JSON" -> runJsonImport()
                    "Re-import last exported bundle" -> runBundleImport()
                    "Export signed bundle" -> runExport()
                    "Verify last exported bundle" -> runVerify()
                }
            }.show()
    }

    private fun importFromUri(uri: Uri, format: String) {
        if (AuthorizationGate.revalidateSession(this, Capabilities.IMPORTS_RUN) == null) return
        val app = application as LibOpsApp
        lifecycleScope.launch {
            val queryTimer = QueryTimer(app.observabilityPipeline)
            val now = System.currentTimeMillis()
            val recent = withContext(Dispatchers.IO) {
                queryTimer.timed("query", "importDao.userImportsSince") {
                    app.db.importDao().userImportsSince(userId, now - ImportRateLimiter.WINDOW_MILLIS)
                }
            }
            val filename = uri.lastPathSegment?.substringAfterLast('/') ?: "user_file.$format"
            try {
                val stream = contentResolver.openInputStream(uri)
                    ?: run { Snackbar.make(binding.root, "Could not open file", Snackbar.LENGTH_SHORT).show(); return@launch }
                val msg = withContext(Dispatchers.IO) {
                    stream.use { input ->
                        val label = format.uppercase()
                        when (format) {
                            "csv" -> {
                                val s = importService.importCsv(filename, input, userId, recent)
                                "$label file: ${s.accepted} ok / ${s.rejected} rej / ${s.duplicatesSurfaced} dup (${s.finalState})"
                            }
                            else -> {
                                val s = importService.importJson(filename, input, userId, recent)
                                "$label file: ${s.accepted} ok / ${s.rejected} rej / ${s.duplicatesSurfaced} dup (${s.finalState})"
                            }
                        }
                    }
                }
                Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
            } catch (e: Exception) {
                lifecycleScope.launch { app.observabilityPipeline.recordException("ImportsActivity.importFromUri", e) }
                Snackbar.make(binding.root, "Import failed: ${e.message}", Snackbar.LENGTH_LONG).show()
            }
            refresh()
        }
    }

    private fun runCsvImport() {
        if (AuthorizationGate.revalidateSession(this, Capabilities.IMPORTS_RUN) == null) return
        val app = application as LibOpsApp
        lifecycleScope.launch {
            val queryTimer = QueryTimer(app.observabilityPipeline)
            val now = System.currentTimeMillis()
            val recent = withContext(Dispatchers.IO) {
                queryTimer.timed("query", "importDao.userImportsSince") {
                    app.db.importDao().userImportsSince(userId, now - ImportRateLimiter.WINDOW_MILLIS)
                }
            }
            val stream = sampleCsv().byteInputStream(Charsets.UTF_8)
            try {
                val s = withContext(Dispatchers.IO) { importService.importCsv("sample.csv", stream, userId, recent) }
                Snackbar.make(binding.root, "CSV: ${s.accepted} ok / ${s.rejected} rej / ${s.duplicatesSurfaced} dup (${s.finalState})", Snackbar.LENGTH_LONG).show()
            } catch (e: Exception) {
                Snackbar.make(binding.root, "Import failed: ${e.message}", Snackbar.LENGTH_LONG).show()
            }
            refresh()
        }
    }

    private fun runJsonImport() {
        if (AuthorizationGate.revalidateSession(this, Capabilities.IMPORTS_RUN) == null) return
        val app = application as LibOpsApp
        lifecycleScope.launch {
            val queryTimer = QueryTimer(app.observabilityPipeline)
            val now = System.currentTimeMillis()
            val recent = withContext(Dispatchers.IO) {
                queryTimer.timed("query", "importDao.userImportsSince") {
                    app.db.importDao().userImportsSince(userId, now - ImportRateLimiter.WINDOW_MILLIS)
                }
            }
            val stream = sampleJson().byteInputStream(Charsets.UTF_8)
            try {
                val s = withContext(Dispatchers.IO) { importService.importJson("sample.json", stream, userId, recent) }
                Snackbar.make(binding.root, "JSON: ${s.accepted} ok / ${s.rejected} rej / ${s.duplicatesSurfaced} dup (${s.finalState})", Snackbar.LENGTH_LONG).show()
            } catch (e: Exception) {
                Snackbar.make(binding.root, "Import failed: ${e.message}", Snackbar.LENGTH_LONG).show()
            }
            refresh()
        }
    }

    private fun importBundleFromTree(treeUri: Uri) {
        if (AuthorizationGate.revalidateSession(this, Capabilities.IMPORTS_RUN) == null) return
        val app = application as LibOpsApp
        lifecycleScope.launch {
            val treeDoc = DocumentFile.fromTreeUri(this@ImportsActivity, treeUri)
            if (treeDoc == null || !treeDoc.isDirectory) {
                Snackbar.make(binding.root, "Selected path is not a directory", Snackbar.LENGTH_SHORT).show()
                return@launch
            }
            val manifestDoc = treeDoc.findFile("manifest.json")
            if (manifestDoc == null || !manifestDoc.isFile) {
                Snackbar.make(binding.root, "No manifest.json found in selected folder", Snackbar.LENGTH_SHORT).show()
                return@launch
            }
            // Copy bundle files to temp dir for File-based BundleVerifier/Importer
            val tmpBundleDir = File(cacheDir, "bundle_import_${System.currentTimeMillis()}")
            try {
                withContext(Dispatchers.IO) {
                    tmpBundleDir.mkdirs()
                    for (child in treeDoc.listFiles()) {
                        if (child.isFile && child.name != null) {
                            val dest = File(tmpBundleDir, child.name!!)
                            contentResolver.openInputStream(child.uri)?.use { input ->
                                dest.outputStream().use { output -> input.copyTo(output) }
                            }
                        }
                    }
                }
                val queryTimer = QueryTimer(app.observabilityPipeline)
                val now = System.currentTimeMillis()
                val recent = withContext(Dispatchers.IO) {
                    queryTimer.timed("query", "importDao.userImportsSince") {
                        app.db.importDao().userImportsSince(userId, now - ImportRateLimiter.WINDOW_MILLIS)
                    }
                }
                val trustedKeys = signingKeyStore.trustedPublicKeys().values
                val result = withContext(Dispatchers.IO) { importService.importBundle(tmpBundleDir, trustedKeys, userId, recent) }
                val msg = when (result) {
                    is BundleImporter.ImportResult.Success -> {
                        val s = result.summary
                        "Bundle imported: ${s.accepted} ok / ${s.rejected} rej / ${s.duplicatesSurfaced} dup (${s.finalState})"
                    }
                    is BundleImporter.ImportResult.VerificationFailed ->
                        "Bundle rejected: ${result.reason}"
                    is BundleImporter.ImportResult.AlreadyImported ->
                        "Bundle already imported (checksum ${result.checksum.take(12)}…)"
                    BundleImporter.ImportResult.RateLimited ->
                        "Rate limit exceeded — max ${ImportRateLimiter.DEFAULT_LIMIT} imports per hour"
                }
                Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
            } catch (e: Exception) {
                Snackbar.make(binding.root, "Bundle import failed: ${e.message}", Snackbar.LENGTH_LONG).show()
            } finally {
                tmpBundleDir.deleteRecursively()
            }
            refresh()
        }
    }

    private fun runBundleImport() {
        if (AuthorizationGate.revalidateSession(this, Capabilities.IMPORTS_RUN) == null) return
        val app = application as LibOpsApp
        lifecycleScope.launch {
            val dir = File(filesDir, "exports/latest")
            if (!File(dir, "manifest.json").isFile) {
                Snackbar.make(binding.root, "No bundle to import — export first", Snackbar.LENGTH_SHORT).show()
                return@launch
            }
            val queryTimer = QueryTimer(app.observabilityPipeline)
            val now = System.currentTimeMillis()
            val recent = withContext(Dispatchers.IO) {
                queryTimer.timed("query", "importDao.userImportsSince") {
                    app.db.importDao().userImportsSince(userId, now - ImportRateLimiter.WINDOW_MILLIS)
                }
            }
            val trustedKeys = signingKeyStore.trustedPublicKeys().values
            val result = withContext(Dispatchers.IO) { importService.importBundle(dir, trustedKeys, userId, recent) }
            val msg = when (result) {
                is BundleImporter.ImportResult.Success -> {
                    val s = result.summary
                    "Bundle imported: ${s.accepted} ok / ${s.rejected} rej / ${s.duplicatesSurfaced} dup (${s.finalState})"
                }
                is BundleImporter.ImportResult.VerificationFailed ->
                    "Bundle rejected: ${result.reason}"
                is BundleImporter.ImportResult.AlreadyImported ->
                    "Bundle already imported (checksum ${result.checksum.take(12)}…)"
                BundleImporter.ImportResult.RateLimited ->
                    "Rate limit exceeded — max ${ImportRateLimiter.DEFAULT_LIMIT} imports per hour"
            }
            Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
            refresh()
        }
    }

    private fun runExport() {
        if (AuthorizationGate.revalidateSession(this, Capabilities.EXPORTS_RUN) == null) return
        val app = application as LibOpsApp
        lifecycleScope.launch {
            val targetDir = File(filesDir, "exports/latest")
            val exporter = BundleExporter(app.db.recordDao(), app.db.auditDao(), signer, app.auditLogger, observability = app.observabilityPipeline)
            try {
                val result = withContext(Dispatchers.IO) {
                    exporter.exportSnapshot(targetDir, userId, includeAudit = true)
                }
                Snackbar.make(
                    binding.root,
                    "Exported to ${result.manifestPath.substringAfterLast('/')} • sha256=${result.sha256.take(12)}…",
                    Snackbar.LENGTH_LONG,
                ).show()
            } catch (e: Exception) {
                Snackbar.make(binding.root, "Export failed: ${e.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun runVerify() {
        if (AuthorizationGate.revalidateSession(this, Capabilities.EXPORTS_RUN) == null) return
        val app = application as LibOpsApp
        lifecycleScope.launch {
            val dir = File(filesDir, "exports/latest")
            if (!File(dir, "manifest.json").isFile) {
                Snackbar.make(binding.root, "No bundle to verify — export first", Snackbar.LENGTH_SHORT).show()
                return@launch
            }
            val trustedKeys = signingKeyStore.trustedPublicKeys().values
            val result = withContext(Dispatchers.IO) { BundleVerifier.verifyWithTrustedKeys(dir, trustedKeys) }
            val msg = when (result) {
                is BundleVerifier.Result.Ok -> "Signed bundle OK • sha256=${result.sha256.take(12)}…"
                is BundleVerifier.Result.InvalidManifest -> "Invalid manifest: ${result.reason}"
                BundleVerifier.Result.DigestMismatch -> "Digest mismatch — content tampered"
                BundleVerifier.Result.SignatureInvalid -> "Signature invalid — reject import"
                is BundleVerifier.Result.ContentMissing -> "Content file missing: ${result.path}"
            }
            app.auditLogger.record(
                action = "export.verified",
                targetType = "export_bundle",
                targetId = dir.name,
                userId = userId,
                reason = result.javaClass.simpleName,
            )
            Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun sampleCsv(): String = """
        title,publisher,pub_date,format,category,isbn10,isbn13,language,notes
        The Lord of the Rings,Allen & Unwin,1954-07-29,hardcover,book,,9780261103252,en,One volume edition
        Deep Learning,MIT Press,2016-11-18,hardcover,book,,9780262035613,en,Canonical ML textbook
        Invalid Row With Bad ISBN,Bogus,,hardcover,book,,1234567890123,en,
        Nature,Nature Publishing Group,,,journal,,,en,Weekly scientific journal
    """.trimIndent()

    private fun sampleJson(): String = """
        {
          "schema_version": "1.0",
          "records": [
            { "title": "Communications of the ACM", "publisher": "ACM", "category": "journal", "language": "en" },
            { "title": "Pattern Recognition and Machine Learning", "publisher": "Springer", "category": "book", "format": "hardcover", "isbn13": "9780387310732", "language": "en" },
            { "title": "Bad ISBN Book", "publisher": "Test", "category": "book", "format": "hardcover", "isbn13": "1111111111111" }
          ]
        }
    """.trimIndent()
}
