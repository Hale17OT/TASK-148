package com.eaglepoint.libops.ui.records

import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.eaglepoint.libops.LibOpsApp
import com.eaglepoint.libops.data.db.entity.BarcodeEntity
import com.eaglepoint.libops.data.db.entity.FieldDefinitionEntity
import com.eaglepoint.libops.data.db.entity.HoldingCopyEntity
import com.eaglepoint.libops.data.db.entity.MasterRecordEntity
import com.eaglepoint.libops.data.db.entity.MasterRecordVersionEntity
import com.eaglepoint.libops.data.db.entity.RecordCustomFieldEntity
import com.eaglepoint.libops.data.db.entity.RecordTaxonomyEntity
import com.eaglepoint.libops.data.db.entity.TaxonomyNodeEntity
import com.eaglepoint.libops.databinding.ActivityFeatureBinding
import com.eaglepoint.libops.domain.auth.Authorizer
import com.eaglepoint.libops.domain.auth.Capabilities
import com.eaglepoint.libops.domain.catalog.BarcodeValidator
import com.eaglepoint.libops.domain.catalog.CatalogService
import com.eaglepoint.libops.domain.catalog.IsbnValidator
import com.eaglepoint.libops.domain.catalog.RecordValidator
import com.eaglepoint.libops.domain.catalog.TitleNormalizer
import com.eaglepoint.libops.observability.QueryTimer
import com.eaglepoint.libops.ui.AuthorizationGate
import com.eaglepoint.libops.ui.FeatureScreenHelper
import com.eaglepoint.libops.ui.TwoLineRow
import com.eaglepoint.libops.ui.chipToneFor
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecordsActivity : FragmentActivity() {

    private lateinit var binding: ActivityFeatureBinding
    private lateinit var helper: FeatureScreenHelper
    private var canManage = false
    private var canTaxonomy = false
    private var canHoldings = false
    private var canBarcodes = false
    private var userId: Long = -1L
    private lateinit var catalogService: CatalogService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = AuthorizationGate.requireAccess(this, Capabilities.RECORDS_READ) ?: return
        binding = ActivityFeatureBinding.inflate(layoutInflater)
        setContentView(binding.root)
        helper = FeatureScreenHelper(this, binding)
        userId = session.userId

        val app = application as LibOpsApp
        val authz = Authorizer(session.capabilities)
        canManage = authz.has(Capabilities.RECORDS_MANAGE)
        canTaxonomy = authz.has(Capabilities.TAXONOMY_MANAGE)
        canHoldings = authz.has(Capabilities.HOLDINGS_MANAGE)
        canBarcodes = authz.has(Capabilities.BARCODES_MANAGE)
        catalogService = CatalogService(authz, app.db.recordDao(), app.db.taxonomyDao(), app.db.holdingDao(), app.db.barcodeDao(), app.auditLogger, observability = app.observabilityPipeline)

        helper.setup(
            eyebrow = "Catalog",
            title = "Master Records",
            subtitle = "Active catalog. Tap a record to edit/manage.",
            fabLabel = if (canManage) "Actions" else null,
            onRowClick = { row ->
                val recordId = row.id.removePrefix("r-").toLongOrNull() ?: return@setup
                showRecordActions(recordId)
            },
            onFabClick = { showCatalogMenu() },
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
        val records = withContext(Dispatchers.IO) {
            queryTimer.timed("query", "recordDao.search") {
                app.db.recordDao().search(prefix = "", q = "", limit = 200, offset = 0)
            }
        }
        val rows = records.map { r ->
            TwoLineRow(
                id = "r-${r.id}",
                primary = r.title,
                secondary = "${r.category} \u2022 ${r.publisher ?: "\u2014"} \u2022 isbn13 ${r.isbn13 ?: "\u2014"}",
                chipLabel = r.status,
                chipTone = chipToneFor(r.status),
            )
        }
        helper.submit(
            rows,
            emptyTitle = "Catalog is empty",
            emptyBody = "Run an import or tap \u201CActions\u201D to create records.",
        )
    }

    private fun showCatalogMenu() {
        val items = mutableListOf<String>()
        if (canManage) items.add("Add sample record")
        if (canTaxonomy) items.add("Create taxonomy node")
        if (canManage) items.add("Manage metadata fields")
        if (items.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Catalog actions")
            .setItems(items.toTypedArray()) { _, i ->
                when (items[i]) {
                    "Add sample record" -> addSample()
                    "Create taxonomy node" -> showCreateTaxonomyDialog()
                    "Manage metadata fields" -> showFieldDefinitionMenu()
                }
            }.show()
    }

    private fun showRecordActions(recordId: Long) {
        val items = mutableListOf<String>()
        if (canManage) items.add("Edit record")
        if (canTaxonomy) items.add("Assign taxonomy")
        if (canHoldings) items.add("Add holding copy")
        if (canBarcodes) items.add("Assign barcode")
        items.add("View holdings")

        AlertDialog.Builder(this)
            .setTitle("Record #$recordId")
            .setItems(items.toTypedArray()) { _, i ->
                when (items[i]) {
                    "Edit record" -> showEditRecordDialog(recordId)
                    "Assign taxonomy" -> showAssignTaxonomyDialog(recordId)
                    "Add holding copy" -> showAddHoldingDialog(recordId)
                    "Assign barcode" -> showAssignBarcodeDialog(recordId)
                    "View holdings" -> showHoldings(recordId)
                }
            }.show()
    }

    /**
     * Resolves the current value of a system (built-in) field from the record entity.
     */
    private fun systemFieldValue(record: MasterRecordEntity, column: String?): String = when (column) {
        "title" -> record.title
        "publisher" -> record.publisher.orEmpty()
        "isbn13" -> record.isbn13.orEmpty()
        "isbn10" -> record.isbn10.orEmpty()
        "pubDate" -> record.pubDate?.toString().orEmpty()
        "format" -> record.format.orEmpty()
        "category" -> record.category
        "language" -> record.language.orEmpty()
        "notes" -> record.notes.orEmpty()
        else -> ""
    }

    private fun showEditRecordDialog(recordId: Long) {
        val app = application as LibOpsApp
        lifecycleScope.launch {
            val queryTimer = QueryTimer(app.observabilityPipeline)
            val record = withContext(Dispatchers.IO) { queryTimer.timed("query", "recordDao.byId") { app.db.recordDao().byId(recordId) } } ?: return@launch
            // Ensure system field definitions are seeded (handles race with app startup)
            withContext(Dispatchers.IO) {
                com.eaglepoint.libops.data.db.FieldDefinitionSeeder.ensureSeeded(app.db.fieldDefinitionDao())
            }
            val fieldDefs = withContext(Dispatchers.IO) { queryTimer.timed("query", "fieldDefinitionDao.activeFields") { app.db.fieldDefinitionDao().activeFields() } }
            val existingCustom = withContext(Dispatchers.IO) { queryTimer.timed("query", "recordCustomFieldDao.forRecord") { app.db.recordCustomFieldDao().forRecord(recordId) } }
            val customByDefId = existingCustom.associateBy { it.fieldDefinitionId }

            val dp16 = (16 * resources.displayMetrics.density).toInt()
            val layout = LinearLayout(this@RecordsActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp16, dp16, dp16, dp16 / 2)
            }

            // Render ALL fields from field definitions — both system and custom
            val fieldInputs = mutableMapOf<Long, EditText>()
            for (def in fieldDefs) {
                val currentValue = if (def.system) {
                    systemFieldValue(record, def.entityColumn)
                } else {
                    customByDefId[def.id]?.value.orEmpty()
                }
                val input = EditText(this@RecordsActivity).apply {
                    hint = if (def.required) "${def.label} *" else def.label
                    setText(currentValue)
                    when (def.fieldType) {
                        "number" -> inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                        "date" -> inputType = android.text.InputType.TYPE_CLASS_DATETIME
                        else -> inputType = android.text.InputType.TYPE_CLASS_TEXT
                    }
                }
                layout.addView(input)
                fieldInputs[def.id] = input
            }

            AlertDialog.Builder(this@RecordsActivity)
                .setTitle("Edit record #$recordId")
                .setView(layout)
                .setPositiveButton("Save") { _, _ ->
                    // Validate required fields
                    for (def in fieldDefs) {
                        if (def.required && fieldInputs[def.id]?.text.toString().isBlank()) {
                            Snackbar.make(binding.root, "${def.label} is required", Snackbar.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }
                    }
                    val allValues = fieldInputs.mapValues { it.value.text.toString() }
                    updateRecord(record, fieldDefs, allValues)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun updateRecord(
        record: MasterRecordEntity,
        fieldDefs: List<FieldDefinitionEntity>,
        allValues: Map<Long, String>,
    ) {
        if (AuthorizationGate.revalidateSession(this, Capabilities.RECORDS_MANAGE) == null) return
        val app = application as LibOpsApp
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                // Build updated entity from system field values
                val valByCol = mutableMapOf<String, String>()
                for (def in fieldDefs) {
                    if (def.system && def.entityColumn != null) {
                        valByCol[def.entityColumn] = allValues[def.id].orEmpty().trim()
                    }
                }
                val title = valByCol["title"] ?: record.title
                val updated = record.copy(
                    title = title,
                    titleNormalized = TitleNormalizer.normalize(title),
                    publisher = valByCol["publisher"]?.ifEmpty { null } ?: record.publisher,
                    isbn13 = IsbnValidator.normalize(valByCol["isbn13"]?.ifEmpty { null }) ?: record.isbn13,
                    isbn10 = IsbnValidator.normalize(valByCol["isbn10"]?.ifEmpty { null }) ?: record.isbn10,
                    format = valByCol["format"]?.ifEmpty { null } ?: record.format,
                    category = valByCol["category"]?.ifEmpty { null } ?: record.category,
                    language = valByCol["language"]?.ifEmpty { null } ?: record.language,
                    notes = valByCol["notes"]?.ifEmpty { null },
                    updatedAt = now,
                )
                catalogService.updateRecord(updated, userId)
                catalogService.insertVersion(
                    MasterRecordVersionEntity(
                        recordId = record.id, version = (record.id + now).toInt(),
                        snapshotJson = """{"title":"${title.replace("\"", "\\\"")}"}""",
                        editorUserId = userId, changeSummary = "ui_edit", createdAt = now,
                    ),
                    userId,
                )
                // Persist custom (non-system) field values
                for (def in fieldDefs) {
                    if (def.system) continue
                    val value = allValues[def.id].orEmpty().trim()
                    if (value.isNotEmpty()) {
                        app.db.recordCustomFieldDao().upsert(
                            RecordCustomFieldEntity(
                                masterRecordId = record.id,
                                fieldDefinitionId = def.id,
                                value = value,
                                updatedAt = now,
                            ),
                        )
                    } else {
                        app.db.recordCustomFieldDao().delete(record.id, def.id)
                    }
                }
                app.auditLogger.record("record.updated", "master_record", targetId = record.id.toString(), userId = userId)
            }
            refresh()
            Snackbar.make(binding.root, "Record updated", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun showCreateTaxonomyDialog() {
        val dp16 = (16 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp16, dp16, dp16, dp16 / 2)
        }
        val nameInput = EditText(this).apply { hint = "Node name" }
        val parentInput = EditText(this).apply { hint = "Parent ID (blank for root)"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        layout.addView(nameInput); layout.addView(parentInput)

        AlertDialog.Builder(this)
            .setTitle("Create taxonomy node")
            .setView(layout)
            .setPositiveButton("Create") { _, _ ->
                val name = nameInput.text.toString().trim()
                val parentId = parentInput.text.toString().toLongOrNull()
                if (name.isEmpty()) return@setPositiveButton
                createTaxonomyNode(name, parentId)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createTaxonomyNode(name: String, parentId: Long?) {
        if (AuthorizationGate.revalidateSession(this, Capabilities.TAXONOMY_MANAGE) == null) return
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                val node = TaxonomyNodeEntity(name = name, parentId = parentId, description = null, createdAt = now, updatedAt = now)
                catalogService.createTaxonomyNode(node, userId)
            }
            Snackbar.make(binding.root, "Taxonomy node '$name' created", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun showAssignTaxonomyDialog(recordId: Long) {
        val taxIdInput = EditText(this).apply { hint = "Taxonomy node ID"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        AlertDialog.Builder(this)
            .setTitle("Assign taxonomy to record #$recordId")
            .setView(taxIdInput)
            .setPositiveButton("Assign") { _, _ ->
                val taxId = taxIdInput.text.toString().toLongOrNull() ?: return@setPositiveButton
                assignTaxonomy(recordId, taxId)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun assignTaxonomy(recordId: Long, taxonomyId: Long) {
        if (AuthorizationGate.revalidateSession(this, Capabilities.TAXONOMY_MANAGE) == null) return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                catalogService.assignTaxonomy(RecordTaxonomyEntity(recordId = recordId, taxonomyId = taxonomyId), userId)
            }
            Snackbar.make(binding.root, "Taxonomy assigned", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun showAddHoldingDialog(recordId: Long) {
        val dp16 = (16 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp16, dp16, dp16, dp16 / 2)
        }
        val locationInput = EditText(this).apply { hint = "Location (e.g. 'Main Branch - Shelf A3')" }
        val countInput = EditText(this).apply { hint = "Copy count"; inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText("1") }
        layout.addView(locationInput); layout.addView(countInput)

        AlertDialog.Builder(this)
            .setTitle("Add holding copy for record #$recordId")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val location = locationInput.text.toString().trim()
                val count = countInput.text.toString().toIntOrNull() ?: 1
                if (location.isEmpty()) return@setPositiveButton
                addHolding(recordId, location, count)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addHolding(recordId: Long, location: String, count: Int) {
        if (AuthorizationGate.revalidateSession(this, Capabilities.HOLDINGS_MANAGE) == null) return
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                val holding = HoldingCopyEntity(masterRecordId = recordId, location = location, totalCount = count, availableCount = count, lastAdjustmentReason = "initial", lastAdjustmentUserId = userId, createdAt = now, updatedAt = now)
                catalogService.addHolding(holding, userId)
            }
            Snackbar.make(binding.root, "Holding added: $count at $location", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun showAssignBarcodeDialog(recordId: Long) {
        val codeInput = EditText(this).apply { hint = "Barcode (8-14 alphanumeric)" }
        AlertDialog.Builder(this)
            .setTitle("Assign barcode")
            .setView(codeInput)
            .setPositiveButton("Assign") { _, _ ->
                val code = BarcodeValidator.canonicalize(codeInput.text.toString()) ?: ""
                if (!BarcodeValidator.isValid(code)) {
                    Snackbar.make(binding.root, "Invalid barcode: must be 8\u201314 alphanumeric characters", Snackbar.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                assignBarcode(recordId, code)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun assignBarcode(recordId: Long, code: String) {
        if (AuthorizationGate.revalidateSession(this, Capabilities.BARCODES_MANAGE) == null) return
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            try {
                withContext(Dispatchers.IO) {
                    catalogService.assignBarcode(
                        BarcodeEntity(code = code, masterRecordId = recordId, holdingId = null, state = "assigned", assignedAt = now, retiredAt = null, reservedUntil = null, createdAt = now, updatedAt = now),
                        userId,
                    )
                }
                Snackbar.make(binding.root, "Barcode $code assigned", Snackbar.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Snackbar.make(binding.root, "Error: ${e.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun showHoldings(recordId: Long) {
        val app = application as LibOpsApp
        lifecycleScope.launch {
            val queryTimer = QueryTimer(app.observabilityPipeline)
            val holdings = withContext(Dispatchers.IO) { queryTimer.timed("query", "holdingDao.forRecord") { app.db.holdingDao().forRecord(recordId) } }
            val barcodes = withContext(Dispatchers.IO) {
                holdings.flatMap { h -> queryTimer.timed("query", "barcodeDao.forHolding") { app.db.barcodeDao().forHolding(h.id) }.map { b -> h to b } }
            }
            val msg = if (holdings.isEmpty()) "No holdings for record #$recordId" else {
                val lines = holdings.map { h ->
                    val codes = barcodes.filter { it.first.id == h.id }.map { it.second.code }
                    "${h.location}: ${h.totalCount} copies" + if (codes.isNotEmpty()) " [${codes.joinToString()}]" else ""
                }
                lines.joinToString("\n")
            }
            AlertDialog.Builder(this@RecordsActivity)
                .setTitle("Holdings for record #$recordId")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun showFieldDefinitionMenu() {
        val app = application as LibOpsApp
        lifecycleScope.launch {
            val queryTimer = QueryTimer(app.observabilityPipeline)
            val fields = withContext(Dispatchers.IO) { queryTimer.timed("query", "fieldDefinitionDao.allFields") { app.db.fieldDefinitionDao().allFields() } }
            val items = mutableListOf("Add new field")
            fields.forEach { f ->
                val status = if (f.archived) " [archived]" else ""
                items.add("${f.label} (${f.fieldType})$status")
            }
            AlertDialog.Builder(this@RecordsActivity)
                .setTitle("Metadata field definitions")
                .setItems(items.toTypedArray()) { _, i ->
                    if (i == 0) showAddFieldDialog()
                    else showEditFieldDialog(fields[i - 1])
                }.show()
        }
    }

    private fun showAddFieldDialog() {
        val dp16 = (16 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp16, dp16, dp16, dp16 / 2)
        }
        val keyInput = EditText(this).apply { hint = "Field key (e.g. edition_notes)" }
        val labelInput = EditText(this).apply { hint = "Display label" }
        val typeInput = EditText(this).apply { hint = "Type: text, number, date, select" }
        val orderInput = EditText(this).apply { hint = "Display order (0=first)"; inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText("0") }
        layout.addView(keyInput); layout.addView(labelInput); layout.addView(typeInput); layout.addView(orderInput)

        AlertDialog.Builder(this)
            .setTitle("Add metadata field")
            .setView(layout)
            .setPositiveButton("Create") { _, _ ->
                val key = keyInput.text.toString().trim()
                val label = labelInput.text.toString().trim()
                val type = typeInput.text.toString().trim().ifEmpty { "text" }
                val order = orderInput.text.toString().toIntOrNull() ?: 0
                if (key.isEmpty() || label.isEmpty()) return@setPositiveButton
                createFieldDefinition(key, label, type, order)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createFieldDefinition(key: String, label: String, type: String, order: Int) {
        if (AuthorizationGate.revalidateSession(this, Capabilities.RECORDS_MANAGE) == null) return
        val app = application as LibOpsApp
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                app.db.fieldDefinitionDao().insert(
                    FieldDefinitionEntity(
                        fieldKey = key, label = label, fieldType = type,
                        displayOrder = order, createdByUserId = userId,
                        createdAt = now, updatedAt = now,
                    ),
                )
                app.auditLogger.record("field_definition.created", "field_definition", userId = userId, reason = "key=$key")
            }
            Snackbar.make(binding.root, "Field '$label' created", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun showEditFieldDialog(field: FieldDefinitionEntity) {
        val items = mutableListOf<String>()
        if (!field.system) items.add(if (field.archived) "Restore" else "Archive")
        items.add("Update label")
        AlertDialog.Builder(this)
            .setTitle(field.label + if (field.system) " (system)" else "")
            .setItems(items.toTypedArray()) { _, i ->
                when (items[i]) {
                    "Archive", "Restore" -> toggleFieldArchive(field)
                    "Update label" -> showUpdateFieldLabelDialog(field)
                }
            }.show()
    }

    private fun toggleFieldArchive(field: FieldDefinitionEntity) {
        if (field.system) {
            Snackbar.make(binding.root, "System fields cannot be archived", Snackbar.LENGTH_SHORT).show()
            return
        }
        if (AuthorizationGate.revalidateSession(this, Capabilities.RECORDS_MANAGE) == null) return
        val app = application as LibOpsApp
        lifecycleScope.launch {
            val updated = withContext(Dispatchers.IO) {
                val rows = app.db.fieldDefinitionDao().setArchived(field.id, !field.archived, System.currentTimeMillis())
                if (rows > 0) {
                    app.auditLogger.record("field_definition.updated", "field_definition", targetId = field.id.toString(), userId = userId, reason = "archived=${!field.archived}")
                }
                rows
            }
            if (updated > 0) {
                Snackbar.make(binding.root, if (field.archived) "Field restored" else "Field archived", Snackbar.LENGTH_SHORT).show()
            } else {
                Snackbar.make(binding.root, "Cannot archive system fields", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun showUpdateFieldLabelDialog(field: FieldDefinitionEntity) {
        val input = EditText(this).apply { setText(field.label) }
        AlertDialog.Builder(this)
            .setTitle("Update label")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newLabel = input.text.toString().trim()
                if (newLabel.isEmpty()) return@setPositiveButton
                if (AuthorizationGate.revalidateSession(this, Capabilities.RECORDS_MANAGE) == null) return@setPositiveButton
                val app = application as LibOpsApp
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        app.db.fieldDefinitionDao().update(field.copy(label = newLabel, updatedAt = System.currentTimeMillis()))
                    }
                    Snackbar.make(binding.root, "Label updated", Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addSample() {
        if (AuthorizationGate.revalidateSession(this, Capabilities.RECORDS_MANAGE) == null) return
        val app = application as LibOpsApp
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val titleSuffix = now.toString().takeLast(4)
            val isbn13 = "9780262035613"
            val title = "Deep Learning \u2014 sample $titleSuffix"
            val errors = RecordValidator.validate(
                RecordValidator.Input(title = title, publisher = "MIT Press", pubDateEpochMillis = now - 86_400_000, format = "hardcover", category = RecordValidator.Category.BOOK, isbn10 = null, isbn13 = isbn13, nowEpochMillis = now),
            )
            if (errors.isNotEmpty()) { Snackbar.make(binding.root, errors.joinToString { it.message }, Snackbar.LENGTH_LONG).show(); return@launch }
            withContext(Dispatchers.IO) {
                val entity = MasterRecordEntity(title = title, titleNormalized = TitleNormalizer.normalize(title), publisher = "MIT Press", pubDate = now - 86_400_000, format = "hardcover", category = "book", isbn10 = null, isbn13 = IsbnValidator.normalize(isbn13), language = "en", notes = null, status = "active", sourceProvenanceJson = """{"source":"ui_sample"}""", createdByUserId = userId, createdAt = now, updatedAt = now)
                val id = catalogService.insertRecord(entity, userId)
                catalogService.insertVersion(MasterRecordVersionEntity(recordId = id, version = 1, snapshotJson = """{"title":"${title.replace("\"", "\\\"")}"}""", editorUserId = userId, changeSummary = "initial_create", createdAt = now), userId)
            }
            refresh()
            Snackbar.make(binding.root, "Record added", Snackbar.LENGTH_SHORT).show()
        }
    }
}
