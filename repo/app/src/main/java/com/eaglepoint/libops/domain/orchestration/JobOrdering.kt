package com.eaglepoint.libops.domain.orchestration

/**
 * Job scheduling ordering (§9.4):
 *
 * 1. Higher priority
 * 2. Earlier scheduled time
 * 3. Lower retry count
 * 4. Lower job id (deterministic tie-break)
 *
 * Pure comparator so it can be unit-tested.
 */
object JobOrdering {
    data class Candidate(
        val id: Long,
        val priority: Int,
        val scheduledAt: Long,
        val retryCount: Int,
    )

    val COMPARATOR: Comparator<Candidate> = Comparator { a, b ->
        val p = b.priority.compareTo(a.priority) // higher priority first
        if (p != 0) return@Comparator p
        val s = a.scheduledAt.compareTo(b.scheduledAt) // earlier first
        if (s != 0) return@Comparator s
        val r = a.retryCount.compareTo(b.retryCount) // lower retry first
        if (r != 0) return@Comparator r
        a.id.compareTo(b.id) // lower id first
    }

    fun pickNext(candidates: List<Candidate>): Candidate? =
        candidates.sortedWith(COMPARATOR).firstOrNull()

    fun sorted(candidates: List<Candidate>): List<Candidate> =
        candidates.sortedWith(COMPARATOR)
}

/**
 * Battery-aware execution (§9.5).
 *
 * Jobs pause when battery below threshold AND not charging.
 * Default threshold is 15%. Admin configurable 10–25.
 */
object BatteryAwareness {
    const val DEFAULT_THRESHOLD_PCT = 15
    const val MIN_THRESHOLD = 10
    const val MAX_THRESHOLD = 25

    fun shouldPause(batteryPct: Int, charging: Boolean, threshold: Int = DEFAULT_THRESHOLD_PCT): Boolean {
        val t = threshold.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
        return !charging && batteryPct < t
    }

    fun shouldResume(batteryPct: Int, charging: Boolean, threshold: Int = DEFAULT_THRESHOLD_PCT): Boolean {
        val t = threshold.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
        return charging || batteryPct >= t
    }
}

/**
 * Rate limiting (§9.13).
 *
 * 30 imports per user per rolling 60-minute window. Admin may reduce to 1..30.
 */
object ImportRateLimiter {
    const val DEFAULT_LIMIT = 30
    const val MIN_LIMIT = 1
    const val MAX_LIMIT = 30
    const val WINDOW_MILLIS = 60L * 60L * 1000L

    fun allowed(recentCount: Int, limit: Int = DEFAULT_LIMIT): Boolean {
        val bounded = limit.coerceIn(MIN_LIMIT, MAX_LIMIT)
        return recentCount < bounded
    }

    fun windowStartMillis(nowMillis: Long): Long = nowMillis - WINDOW_MILLIS
}

/**
 * Retry policy for jobs (§9.3, §15).
 */
object RetryPolicy {
    const val MAX_RETRIES = 3

    val BACKOFF_MILLIS = mapOf(
        "1_min" to 60_000L,
        "5_min" to 5 * 60_000L,
        "30_min" to 30 * 60_000L,
    )

    fun nextDelay(backoff: String): Long =
        BACKOFF_MILLIS[backoff] ?: error("Unknown backoff $backoff")

    /**
     * Decide whether an error warrants a retry. Parser-level corruption and
     * signature failures are terminal; transient errors may retry.
     */
    fun isRetryable(kind: RetryKind): Boolean = when (kind) {
        RetryKind.TRANSIENT_IO -> true
        RetryKind.PARSER_CORRUPTION -> false
        RetryKind.INVALID_SIGNATURE -> false
        RetryKind.ROW_CONSTRAINT -> false
        RetryKind.LOW_BATTERY_PAUSE -> false
    }

    enum class RetryKind { TRANSIENT_IO, PARSER_CORRUPTION, INVALID_SIGNATURE, ROW_CONSTRAINT, LOW_BATTERY_PAUSE }
}
