package com.eaglepoint.libops.ui.collection

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.eaglepoint.libops.LibOpsApp
import com.eaglepoint.libops.data.db.entity.CollectionSourceEntity
import com.eaglepoint.libops.data.db.entity.CrawlRuleEntity
import com.eaglepoint.libops.databinding.ActivitySourceEditorBinding
import com.eaglepoint.libops.domain.auth.Capabilities
import com.eaglepoint.libops.domain.statemachine.CollectionSourceStateMachine
import com.eaglepoint.libops.ui.AuthorizationGate
import com.eaglepoint.libops.observability.QueryTimer
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full CRUD editor for a [CollectionSourceEntity].
 *
 * Addresses audit finding #5: entry type, refresh mode, priority 1..5,
 * retry backoff, enabled flag, and lifecycle state transitions are all
 * user-configurable here.
 */
class SourceEditorActivity : FragmentActivity() {

    private lateinit var binding: ActivitySourceEditorBinding
    private var editingId: Long = -1L
    private var currentState: String = "draft"
    private var userId: Long = -1L

    private val entryTypes = listOf("site", "ranking_list", "artist", "album", "imported_file")
    private val refreshModes = listOf("incremental", "full")
    private val retryBackoffs = listOf("1_min", "5_min", "30_min")
    private val fileFormats = listOf("json", "csv")

    companion object {
        /** Basic 5-field cron pattern: minute hour dom month dow, each field supports *, digits, ranges, steps. */
        private val CRON_PATTERN = Regex(
            """^([0-9*,/\-]+)\s+([0-9*,/\-]+)\s+([0-9*,/\-?]+)\s+([0-9*,/\-]+)\s+([0-9*,/\-?]+)$"""
        )

        fun isValidCron(expr: String): Boolean = CRON_PATTERN.matches(expr.trim())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = AuthorizationGate.requireAccess(this, Capabilities.SOURCES_MANAGE) ?: return
        binding = ActivitySourceEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        userId = session.userId

        editingId = intent.getLongExtra("sourceId", -1L)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.title = if (editingId > 0) "Edit source" else "New source"

        populateChips(binding.entryTypeGroup, entryTypes, selected = "site")
        populateChips(binding.refreshModeGroup, refreshModes, selected = "incremental")
        populateChips(binding.retryGroup, retryBackoffs, selected = "5_min")
        populateChips(binding.fileFormatGroup, fileFormats, selected = "json")

        binding.prioritySlider.addOnChangeListener { _, v, _ ->
            binding.priorityValue.text = "priority = ${v.toInt()}"
        }
        binding.priorityValue.text = "priority = 3"

        binding.saveButton.setOnClickListener { save() }
        binding.archiveButton.setOnClickListener { archive() }

        if (editingId > 0) {
            lifecycleScope.launch { loadExisting(editingId) }
        } else {
            binding.enabledSwitch.isChecked = true
        }
    }

    private fun populateChips(group: ChipGroup, values: List<String>, selected: String) {
        group.removeAllViews()
        values.forEach { value ->
            val chip = Chip(this).apply {
                text = value.replace('_', ' ')
                isCheckable = true
                isChecked = value == selected
                tag = value
            }
            group.addView(chip)
        }
    }

    private fun selectedValue(group: ChipGroup, default: String): String {
        val id = group.checkedChipId
        if (id == ChipGroup.NO_ID) return default
        val chip = group.findViewById<Chip>(id) ?: return default
        return chip.tag as? String ?: default
    }

    private suspend fun loadExisting(id: Long) {
        val app = application as LibOpsApp
        val queryTimer = QueryTimer(app.observabilityPipeline)
        val existing = withContext(Dispatchers.IO) {
            queryTimer.timed("query", "collectionSourceDao.byId") { app.db.collectionSourceDao().byId(id) }
        } ?: run {
            Snackbar.make(binding.root, "Source not found", Snackbar.LENGTH_SHORT).show()
            finish(); return
        }
        currentState = existing.state
        binding.nameInput.setText(existing.name)
        binding.scheduleInput.setText(existing.scheduleCron.orEmpty())
        populateChips(binding.entryTypeGroup, entryTypes, selected = existing.entryType)
        populateChips(binding.refreshModeGroup, refreshModes, selected = existing.refreshMode)
        populateChips(binding.retryGroup, retryBackoffs, selected = existing.retryBackoff)
        binding.prioritySlider.value = existing.priority.toFloat()
        binding.priorityValue.text = "priority = ${existing.priority}"
        binding.enabledSwitch.isChecked = existing.enabled
        binding.archiveButton.visibility = if (existing.state != "archived") android.view.View.VISIBLE else android.view.View.GONE

        // Load crawl rules into rule editor fields
        val rules = withContext(Dispatchers.IO) {
            queryTimer.timed("query", "collectionSourceDao.rulesFor") { app.db.collectionSourceDao().rulesFor(existing.id) }
        }
        rules.firstOrNull { it.ruleKey == "file_path" }?.let { binding.filePathInput.setText(it.ruleValue) }
        rules.firstOrNull { it.ruleKey == "file_format" }?.let { populateChips(binding.fileFormatGroup, fileFormats, selected = it.ruleValue) }
        rules.firstOrNull { it.ruleKey == "publisher" }?.let { binding.publisherRuleInput.setText(it.ruleValue) }
        rules.firstOrNull { it.ruleKey == "category_override" }?.let { binding.categoryOverrideInput.setText(it.ruleValue) }
    }

    private fun save() {
        if (AuthorizationGate.revalidateSession(this, Capabilities.SOURCES_MANAGE) == null) return
        val app = application as LibOpsApp
        val name = binding.nameInput.text?.toString()?.trim().orEmpty()
        if (name.length !in 1..120) {
            binding.statusText.text = "Name must be 1–120 characters"
            return
        }
        val scheduleRaw = binding.scheduleInput.text?.toString()?.trim().orEmpty()
        val scheduleCron: String? = if (scheduleRaw.isEmpty()) null else {
            if (!isValidCron(scheduleRaw)) {
                binding.statusText.text = "Invalid cron expression. Use 5 fields: minute hour day-of-month month day-of-week"
                return
            }
            scheduleRaw
        }
        val entryType = selectedValue(binding.entryTypeGroup, "site")
        val refreshMode = selectedValue(binding.refreshModeGroup, "incremental")
        val retryBackoff = selectedValue(binding.retryGroup, "5_min")
        val priority = binding.prioritySlider.value.toInt().coerceIn(1, 5)
        val enabled = binding.enabledSwitch.isChecked
        val now = System.currentTimeMillis()

        lifecycleScope.launch {
            if (editingId > 0) {
                val queryTimer = QueryTimer(app.observabilityPipeline)
                val existing = withContext(Dispatchers.IO) { queryTimer.timed("query", "collectionSourceDao.byId") { app.db.collectionSourceDao().byId(editingId) } }
                if (existing == null) { finish(); return@launch }
                val newState = if (enabled && existing.state == "disabled") {
                    if (CollectionSourceStateMachine.canTransition("disabled", "active")) "active" else existing.state
                } else if (!enabled && existing.state == "active") {
                    if (CollectionSourceStateMachine.canTransition("active", "disabled")) "disabled" else existing.state
                } else existing.state

                val updated = existing.copy(
                    name = name,
                    entryType = entryType,
                    refreshMode = refreshMode,
                    retryBackoff = retryBackoff,
                    priority = priority,
                    enabled = enabled,
                    state = newState,
                    scheduleCron = scheduleCron,
                    updatedAt = now,
                    version = existing.version + 1,
                )
                withContext(Dispatchers.IO) {
                    app.db.collectionSourceDao().update(updated)
                    saveCrawlRules(app, updated.id)
                    app.auditLogger.record(
                        "source.updated",
                        "collection_source",
                        targetId = updated.id.toString(),
                        userId = userId,
                        reason = "state=$newState,priority=$priority",
                    )
                }
                Snackbar.make(binding.root, "Saved ${updated.name}", Snackbar.LENGTH_SHORT).show()
            } else {
                val entity = CollectionSourceEntity(
                    name = name,
                    entryType = entryType,
                    refreshMode = refreshMode,
                    priority = priority,
                    retryBackoff = retryBackoff,
                    enabled = enabled,
                    state = if (enabled) "active" else "draft",
                    scheduleCron = scheduleCron,
                    createdAt = now,
                    updatedAt = now,
                )
                val id = withContext(Dispatchers.IO) {
                    val newId = app.db.collectionSourceDao().insert(entity)
                    saveCrawlRules(app, newId)
                    app.auditLogger.record(
                        "source.created",
                        "collection_source",
                        targetId = newId.toString(),
                        userId = userId,
                        reason = "entryType=$entryType",
                    )
                    newId
                }
                Snackbar.make(binding.root, "Created source #$id", Snackbar.LENGTH_SHORT).show()
            }
            finish()
        }
    }

    private suspend fun saveCrawlRules(app: LibOpsApp, sourceId: Long) {
        val dao = app.db.collectionSourceDao()
        val filePath = binding.filePathInput.text?.toString()?.trim().orEmpty()
        val fileFormat = selectedValue(binding.fileFormatGroup, "json")
        val publisher = binding.publisherRuleInput.text?.toString()?.trim().orEmpty()
        val catOverride = binding.categoryOverrideInput.text?.toString()?.trim().orEmpty()

        // Deterministic upsert: delete old value then insert new one per key.
        // The unique index on (sourceId, ruleKey) prevents duplicates even if
        // this path races with itself.
        upsertRule(dao, sourceId, "file_path", filePath)
        upsertRule(dao, sourceId, "file_format", fileFormat)
        upsertRule(dao, sourceId, "publisher", publisher)
        upsertRule(dao, sourceId, "category_override", catOverride)
    }

    /**
     * Deterministic upsert: always deletes the existing rule for the key first.
     * If [value] is non-empty, inserts a new rule. If empty, the delete is the
     * final operation — effectively removing a stale rule when the user clears
     * the corresponding field in the editor.
     */
    private suspend fun upsertRule(
        dao: com.eaglepoint.libops.data.db.dao.CollectionSourceDao,
        sourceId: Long,
        key: String,
        value: String,
    ) {
        dao.deleteRule(sourceId, key)
        if (value.isNotEmpty()) {
            dao.insertRule(CrawlRuleEntity(sourceId = sourceId, ruleKey = key, ruleValue = value, include = true))
        }
    }

    private fun archive() {
        if (AuthorizationGate.revalidateSession(this, Capabilities.SOURCES_MANAGE) == null) return
        val app = application as LibOpsApp
        lifecycleScope.launch {
            val queryTimer = QueryTimer(app.observabilityPipeline)
            val existing = withContext(Dispatchers.IO) { queryTimer.timed("query", "collectionSourceDao.byId") { app.db.collectionSourceDao().byId(editingId) } } ?: run {
                finish(); return@launch
            }
            if (!CollectionSourceStateMachine.canTransition(existing.state, "archived")) {
                Snackbar.make(binding.root, "Cannot archive from ${existing.state}", Snackbar.LENGTH_SHORT).show()
                return@launch
            }
            val now = System.currentTimeMillis()
            val archived = existing.copy(state = "archived", updatedAt = now, version = existing.version + 1)
            withContext(Dispatchers.IO) {
                app.db.collectionSourceDao().update(archived)
                app.auditLogger.record(
                    "source.archived",
                    "collection_source",
                    targetId = archived.id.toString(),
                    userId = userId,
                )
            }
            Snackbar.make(binding.root, "Archived", Snackbar.LENGTH_SHORT).show()
            finish()
        }
    }
}
