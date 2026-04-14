package com.eaglepoint.libops.tests.fakes

import com.eaglepoint.libops.data.db.dao.CollectionSourceDao
import com.eaglepoint.libops.data.db.dao.JobDao
import com.eaglepoint.libops.data.db.entity.CollectionSourceEntity
import com.eaglepoint.libops.data.db.entity.CrawlRuleEntity
import com.eaglepoint.libops.data.db.entity.JobAttemptEntity
import com.eaglepoint.libops.data.db.entity.JobEntity
import java.util.concurrent.atomic.AtomicLong

class FakeCollectionSourceDao : CollectionSourceDao {
    private val sources = linkedMapOf<Long, CollectionSourceEntity>()
    private val rules = mutableListOf<CrawlRuleEntity>()
    private val ids = AtomicLong(0)
    private val ruleIds = AtomicLong(0)

    override suspend fun insert(source: CollectionSourceEntity): Long {
        val id = ids.incrementAndGet()
        sources[id] = source.copy(id = id)
        return id
    }

    override suspend fun update(source: CollectionSourceEntity): Int {
        if (!sources.containsKey(source.id)) return 0
        sources[source.id] = source
        return 1
    }

    override suspend fun byId(id: Long): CollectionSourceEntity? = sources[id]
    override suspend fun listByState(state: String): List<CollectionSourceEntity> =
        sources.values.filter { it.state == state }.sortedBy { it.name }
    override suspend fun listAll(): List<CollectionSourceEntity> =
        sources.values.sortedBy { it.name }

    override suspend fun insertRule(rule: CrawlRuleEntity): Long {
        // Respect unique index on (sourceId, ruleKey) — REPLACE semantics
        rules.removeAll { it.sourceId == rule.sourceId && it.ruleKey == rule.ruleKey }
        val id = ruleIds.incrementAndGet()
        rules.add(rule.copy(id = id))
        return id
    }

    override suspend fun deleteRule(sourceId: Long, ruleKey: String): Int {
        val before = rules.size
        rules.removeAll { it.sourceId == sourceId && it.ruleKey == ruleKey }
        return before - rules.size
    }

    override suspend fun rulesFor(sourceId: Long): List<CrawlRuleEntity> =
        rules.filter { it.sourceId == sourceId }
}

class FakeJobDao : JobDao {
    private val jobs = linkedMapOf<Long, JobEntity>()
    private val attempts = mutableListOf<JobAttemptEntity>()
    private val ids = AtomicLong(0)
    private val attemptIds = AtomicLong(0)

    override suspend fun insert(job: JobEntity): Long {
        val id = ids.incrementAndGet()
        jobs[id] = job.copy(id = id)
        return id
    }

    override suspend fun update(job: JobEntity): Int {
        if (!jobs.containsKey(job.id)) return 0
        jobs[job.id] = job
        return 1
    }

    override suspend fun insertAttempt(attempt: JobAttemptEntity): Long {
        val id = attemptIds.incrementAndGet()
        attempts.add(attempt.copy(id = id))
        return id
    }

    override suspend fun byId(id: Long): JobEntity? = jobs[id]

    override suspend fun nextRunnable(limit: Int, nowMs: Long): List<JobEntity> =
        jobs.values.filter { it.status in setOf("scheduled", "queued", "retry_waiting") && it.scheduledAt <= nowMs }
            .sortedWith(
                compareByDescending<JobEntity> { it.priority }
                    .thenBy { it.scheduledAt }
                    .thenBy { it.retryCount }
                    .thenBy { it.id },
            )
            .take(limit)

    override suspend fun pausedJobs(): List<JobEntity> =
        jobs.values.filter { it.status == "paused_low_battery" }

    override suspend fun updateStatus(
        id: Long,
        status: String,
        retryCount: Int,
        lastError: String?,
        startedAt: Long?,
        finishedAt: Long?,
        now: Long,
        scheduledAt: Long,
    ): Int {
        val j = jobs[id] ?: return 0
        jobs[id] = j.copy(
            status = status,
            retryCount = retryCount,
            lastError = lastError,
            startedAt = startedAt ?: j.startedAt,
            finishedAt = finishedAt ?: j.finishedAt,
            updatedAt = now,
            scheduledAt = scheduledAt,
        )
        return 1
    }

    override suspend fun countByStatus(status: String): Int =
        jobs.values.count { it.status == status }

    override suspend fun attemptsFor(jobId: Long): List<JobAttemptEntity> =
        attempts.filter { it.jobId == jobId }.sortedByDescending { it.attemptNumber }

    override suspend fun recentTerminalAttempts(limit: Int): List<JobAttemptEntity> =
        attempts.filter { it.outcome in setOf("succeeded", "failed") }
            .sortedByDescending { it.startedAt }
            .take(limit)
}
