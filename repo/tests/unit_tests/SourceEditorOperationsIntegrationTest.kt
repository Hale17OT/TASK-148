package com.eaglepoint.libops.tests

import androidx.test.core.app.ApplicationProvider
import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.data.db.LibOpsDatabase
import com.eaglepoint.libops.data.db.entity.CollectionSourceEntity
import com.eaglepoint.libops.data.db.entity.CrawlRuleEntity
import com.eaglepoint.libops.domain.statemachine.CollectionSourceStateMachine
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real-Room integration test mirroring operations performed by
 * [com.eaglepoint.libops.ui.collection.SourceEditorActivity] (§9.3, §10.3).
 *
 * Exercises the full source editor lifecycle: create → update → add rules →
 * archive — with real `collection_sources` and `crawl_rules` tables and
 * state-machine enforcement.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SourceEditorOperationsIntegrationTest {

    private lateinit var db: LibOpsDatabase
    private lateinit var audit: AuditLogger
    private val clockMs = 1_700_000_000_000L
    private val userId = 5L

    @Before
    fun setUp() {
        db = LibOpsDatabase.inMemory(
            ApplicationProvider.getApplicationContext<android.content.Context>()
        )
        audit = AuditLogger(db.auditDao(), clock = { clockMs })
    }

    @After
    fun tearDown() { db.close() }

    private fun newSource(name: String = "acme_catalog", state: String = "draft") = CollectionSourceEntity(
        name = name, entryType = "imported_file", refreshMode = "full",
        priority = 3, retryBackoff = "5_min", enabled = true,
        state = state, scheduleCron = null,
        createdAt = clockMs, updatedAt = clockMs,
    )

    // ── create source (SourceEditorActivity "save new") ───────────────────────

    @Test
    fun create_new_source_persists_entity_and_emits_audit(): Unit = runBlocking {
        val id = db.collectionSourceDao().insert(newSource())
        audit.record(
            action = "source.created",
            targetType = "collection_source",
            targetId = id.toString(),
            userId = userId,
            reason = "entryType=imported_file",
        )

        val stored = db.collectionSourceDao().byId(id)
        assertThat(stored).isNotNull()
        assertThat(stored!!.name).isEqualTo("acme_catalog")
        assertThat(stored.state).isEqualTo("draft")

        val events = db.auditDao().allEventsChronological()
        assertThat(events.any { it.action == "source.created" && it.userId == userId }).isTrue()
    }

    // ── update source ─────────────────────────────────────────────────────────

    @Test
    fun update_source_persists_state_transition_and_audits(): Unit = runBlocking {
        val id = db.collectionSourceDao().insert(newSource(state = "draft"))
        val existing = db.collectionSourceDao().byId(id)!!

        // draft → active is a legal state-machine transition
        assertThat(CollectionSourceStateMachine.canTransition("draft", "active")).isTrue()
        db.collectionSourceDao().update(
            existing.copy(state = "active", priority = 5, updatedAt = clockMs + 1),
        )
        audit.record(
            action = "source.updated",
            targetType = "collection_source",
            targetId = id.toString(),
            userId = userId,
            reason = "state=active,priority=5",
        )

        val after = db.collectionSourceDao().byId(id)!!
        assertThat(after.state).isEqualTo("active")
        assertThat(after.priority).isEqualTo(5)
    }

    // ── crawl rules ───────────────────────────────────────────────────────────

    @Test
    fun insert_crawl_rules_persists_and_can_be_queried(): Unit = runBlocking {
        val sourceId = db.collectionSourceDao().insert(newSource())

        db.collectionSourceDao().insertRule(
            CrawlRuleEntity(sourceId = sourceId, ruleKey = "file_path", ruleValue = "/tmp/data.csv", include = true),
        )
        db.collectionSourceDao().insertRule(
            CrawlRuleEntity(sourceId = sourceId, ruleKey = "file_format", ruleValue = "csv", include = true),
        )
        db.collectionSourceDao().insertRule(
            CrawlRuleEntity(sourceId = sourceId, ruleKey = "publisher", ruleValue = "Random House", include = true),
        )

        val rules = db.collectionSourceDao().rulesFor(sourceId)
        assertThat(rules).hasSize(3)
        assertThat(rules.map { it.ruleKey }).containsAtLeast("file_path", "file_format", "publisher")
    }

    @Test
    fun insert_rule_with_same_key_replaces_previous(): Unit = runBlocking {
        val sourceId = db.collectionSourceDao().insert(newSource())
        db.collectionSourceDao().insertRule(
            CrawlRuleEntity(sourceId = sourceId, ruleKey = "publisher", ruleValue = "Original Pub", include = true),
        )
        db.collectionSourceDao().insertRule(
            CrawlRuleEntity(sourceId = sourceId, ruleKey = "publisher", ruleValue = "New Pub", include = true),
        )

        val rules = db.collectionSourceDao().rulesFor(sourceId).filter { it.ruleKey == "publisher" }
        assertThat(rules).hasSize(1)
        assertThat(rules[0].ruleValue).isEqualTo("New Pub")
    }

    @Test
    fun delete_rule_removes_it_from_source(): Unit = runBlocking {
        val sourceId = db.collectionSourceDao().insert(newSource())
        db.collectionSourceDao().insertRule(
            CrawlRuleEntity(sourceId = sourceId, ruleKey = "category_override", ruleValue = "journal", include = true),
        )
        assertThat(db.collectionSourceDao().rulesFor(sourceId)).hasSize(1)

        val removed = db.collectionSourceDao().deleteRule(sourceId, "category_override")
        assertThat(removed).isEqualTo(1)
        assertThat(db.collectionSourceDao().rulesFor(sourceId)).isEmpty()
    }

    // ── archive (state transition) ────────────────────────────────────────────

    @Test
    fun archive_disabled_source_transitions_state_and_audits(): Unit = runBlocking {
        val id = db.collectionSourceDao().insert(newSource(state = "disabled"))
        val existing = db.collectionSourceDao().byId(id)!!

        assertThat(CollectionSourceStateMachine.canTransition("disabled", "archived")).isTrue()
        db.collectionSourceDao().update(existing.copy(state = "archived", updatedAt = clockMs + 2))
        audit.record(
            action = "source.archived",
            targetType = "collection_source",
            targetId = id.toString(),
            userId = userId,
        )

        assertThat(db.collectionSourceDao().byId(id)!!.state).isEqualTo("archived")
        val events = db.auditDao().allEventsChronological()
        assertThat(events.any { it.action == "source.archived" }).isTrue()
    }

    // ── listByState / listAll ─────────────────────────────────────────────────

    @Test
    fun list_by_state_filters_correctly(): Unit = runBlocking {
        db.collectionSourceDao().insert(newSource(name = "src_draft", state = "draft"))
        db.collectionSourceDao().insert(newSource(name = "src_active_1", state = "active"))
        db.collectionSourceDao().insert(newSource(name = "src_active_2", state = "active"))
        db.collectionSourceDao().insert(newSource(name = "src_archived", state = "archived"))

        val active = db.collectionSourceDao().listByState("active")
        assertThat(active).hasSize(2)
        assertThat(active.map { it.name }).containsExactly("src_active_1", "src_active_2")
    }
}
