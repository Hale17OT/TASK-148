package com.eaglepoint.libops.tests

import androidx.test.core.app.ApplicationProvider
import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.data.db.LibOpsDatabase
import com.eaglepoint.libops.data.db.entity.DuplicateCandidateEntity
import com.eaglepoint.libops.data.db.entity.MasterRecordEntity
import com.eaglepoint.libops.data.db.entity.MergeDecisionEntity
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
 * [com.eaglepoint.libops.ui.duplicates.MergeReviewActivity] (§10.5).
 *
 * Exercises duplicate review workflow: load candidate → transition to
 * under_review → record decision (merged/dismissed/escalated) with provenance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MergeReviewOperationsIntegrationTest {

    private lateinit var db: LibOpsDatabase
    private lateinit var audit: AuditLogger
    private val clockMs = 1_700_000_000_000L
    private val operatorUserId = 9L

    @Before
    fun setUp() {
        db = LibOpsDatabase.inMemory(
            ApplicationProvider.getApplicationContext<android.content.Context>()
        )
        audit = AuditLogger(db.auditDao(), clock = { clockMs })
    }

    @After
    fun tearDown() { db.close() }

    private suspend fun seedRecord(title: String): Long = db.recordDao().insert(
        MasterRecordEntity(
            title = title, titleNormalized = title.lowercase(),
            publisher = null, pubDate = null, format = "paperback",
            category = "book", isbn10 = null, isbn13 = null,
            language = null, notes = null, status = "active",
            sourceProvenanceJson = null, createdByUserId = 1L,
            createdAt = clockMs, updatedAt = clockMs,
        ),
    )

    private suspend fun seedDuplicate(primaryId: Long, status: String = "detected"): Long =
        db.duplicateDao().insert(
            DuplicateCandidateEntity(
                primaryRecordId = primaryId, candidateRecordId = null,
                primaryStagingRef = null, candidateStagingRef = "batch=1,row=0",
                score = 0.92, algorithm = "jaro_winkler",
                status = status, detectedAt = clockMs,
                reviewedByUserId = null, reviewedAt = null,
            ),
        )

    // ── load duplicate + primary record (MergeReviewActivity onCreate) ────────

    @Test
    fun load_duplicate_by_id_returns_persisted_row(): Unit = runBlocking {
        val recordId = seedRecord("Primary Book")
        val dupId = seedDuplicate(recordId)

        val loaded = db.duplicateDao().byId(dupId)
        assertThat(loaded).isNotNull()
        assertThat(loaded!!.primaryRecordId).isEqualTo(recordId)
        assertThat(loaded.score).isEqualTo(0.92)
    }

    @Test
    fun load_primary_record_for_side_by_side_display(): Unit = runBlocking {
        val recordId = seedRecord("Side by Side Book")
        seedDuplicate(recordId)

        val primary = db.recordDao().byId(recordId)
        assertThat(primary).isNotNull()
        assertThat(primary!!.title).isEqualTo("Side by Side Book")
    }

    // ── transition detected → under_review (first open) ───────────────────────

    @Test
    fun transition_detected_to_under_review_persists(): Unit = runBlocking {
        val recordId = seedRecord("Open Book")
        val dupId = seedDuplicate(recordId, status = "detected")

        val dup = db.duplicateDao().byId(dupId)!!
        db.duplicateDao().update(dup.copy(status = "under_review"))

        val after = db.duplicateDao().byId(dupId)!!
        assertThat(after.status).isEqualTo("under_review")
    }

    // ── merge decision ────────────────────────────────────────────────────────

    @Test
    fun merge_decision_records_entity_and_audit(): Unit = runBlocking {
        val recordId = seedRecord("Merge Target")
        val dupId = seedDuplicate(recordId, status = "under_review")

        val dup = db.duplicateDao().byId(dupId)!!
        db.duplicateDao().update(
            dup.copy(
                status = "merged",
                reviewedByUserId = operatorUserId,
                reviewedAt = clockMs + 100,
            ),
        )
        db.duplicateDao().insertDecision(
            MergeDecisionEntity(
                duplicateId = dupId,
                operatorUserId = operatorUserId,
                decision = "merged",
                rationale = "Same ISBN, same publisher — clear duplicate.",
                keptRecordId = recordId,
                mergedFromRecordId = null,
                provenanceJson = """{"fromStatus":"under_review","algorithm":"jaro_winkler","score":0.92}""",
                decidedAt = clockMs + 100,
            ),
        )
        audit.record(
            action = "duplicate.merged",
            targetType = "duplicate_candidate",
            targetId = dupId.toString(),
            userId = operatorUserId,
            reason = "Same ISBN, same publisher — clear duplicate.",
        )

        val finalDup = db.duplicateDao().byId(dupId)!!
        assertThat(finalDup.status).isEqualTo("merged")
        assertThat(finalDup.reviewedByUserId).isEqualTo(operatorUserId)

        val events = db.auditDao().allEventsChronological()
        assertThat(events.any { it.action == "duplicate.merged" }).isTrue()
    }

    // ── dismiss decision ──────────────────────────────────────────────────────

    @Test
    fun dismiss_decision_transitions_status_and_records_entity(): Unit = runBlocking {
        val recordId = seedRecord("Dismiss Target")
        val dupId = seedDuplicate(recordId, status = "under_review")

        val dup = db.duplicateDao().byId(dupId)!!
        db.duplicateDao().update(
            dup.copy(status = "dismissed", reviewedByUserId = operatorUserId, reviewedAt = clockMs + 50),
        )
        db.duplicateDao().insertDecision(
            MergeDecisionEntity(
                duplicateId = dupId, operatorUserId = operatorUserId,
                decision = "dismissed", rationale = "Different editions, not a duplicate.",
                keptRecordId = recordId, mergedFromRecordId = null,
                provenanceJson = "{}", decidedAt = clockMs + 50,
            ),
        )

        assertThat(db.duplicateDao().byId(dupId)!!.status).isEqualTo("dismissed")
    }

    // ── escalate decision ─────────────────────────────────────────────────────

    @Test
    fun escalate_decision_transitions_status(): Unit = runBlocking {
        val recordId = seedRecord("Escalate Target")
        val dupId = seedDuplicate(recordId, status = "under_review")

        val dup = db.duplicateDao().byId(dupId)!!
        db.duplicateDao().update(
            dup.copy(status = "escalated", reviewedByUserId = operatorUserId, reviewedAt = clockMs + 75),
        )
        db.duplicateDao().insertDecision(
            MergeDecisionEntity(
                duplicateId = dupId, operatorUserId = operatorUserId,
                decision = "escalated", rationale = "Uncertain — escalating for senior review.",
                keptRecordId = null, mergedFromRecordId = null,
                provenanceJson = "{}", decidedAt = clockMs + 75,
            ),
        )

        assertThat(db.duplicateDao().byId(dupId)!!.status).isEqualTo("escalated")
    }

    // ── listByStatus (DuplicatesActivity source query) ────────────────────────

    @Test
    fun list_by_status_returns_candidates_filtered_by_status(): Unit = runBlocking {
        val recordId = seedRecord("Filter Record")
        seedDuplicate(recordId, status = "detected")
        seedDuplicate(recordId, status = "detected")
        seedDuplicate(recordId, status = "merged")

        val detected = db.duplicateDao().listByStatus("detected", limit = 50, offset = 0)
        assertThat(detected).hasSize(2)
        val merged = db.duplicateDao().listByStatus("merged", limit = 50, offset = 0)
        assertThat(merged).hasSize(1)
    }
}
