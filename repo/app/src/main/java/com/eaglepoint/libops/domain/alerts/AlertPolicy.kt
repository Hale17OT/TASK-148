package com.eaglepoint.libops.domain.alerts

import com.eaglepoint.libops.domain.FieldError

/**
 * Alert SLA policy (§9.14):
 *
 * - Every alert must be acknowledged then resolved with a note within 7 days
 * - Resolution note min length 10 chars
 * - Closed-loop: open -> acknowledged -> resolved
 * - Past-due alerts become overdue and affect quality score
 */
object AlertPolicy {
    const val SLA_DAYS = 7L
    const val SLA_MILLIS = SLA_DAYS * 24 * 60 * 60 * 1000L
    const val RESOLUTION_NOTE_MIN = 10

    fun isOverdue(createdAt: Long, nowMillis: Long): Boolean =
        nowMillis - createdAt > SLA_MILLIS

    fun validateResolution(currentStatus: String, note: String): List<FieldError> {
        val errors = mutableListOf<FieldError>()
        if (currentStatus != "acknowledged" && currentStatus != "overdue") {
            errors += FieldError("status", "illegal_transition", "Alert must be acknowledged before resolution")
        }
        if (note.trim().length < RESOLUTION_NOTE_MIN) {
            errors += FieldError("note", "too_short", "Resolution note must be at least $RESOLUTION_NOTE_MIN characters")
        }
        return errors
    }
}

/**
 * Anomaly detection thresholds (§15).
 */
object AnomalyThresholds {
    const val JOB_FAILURE_RATE = 0.20
    const val JOB_FAILURE_WINDOW_ATTEMPTS = 50
    const val SLOW_QUERY_MS = 200L
    const val SLOW_QUERY_FRACTION = 0.05
    const val RATE_LIMIT_VIOLATIONS_PER_DAY = 5
    const val OVERDUE_ALERTS_GLOBAL = 10

    fun jobFailuresTriggerAlert(failures: Int, total: Int): Boolean {
        if (total < JOB_FAILURE_WINDOW_ATTEMPTS) return false
        return failures.toDouble() / total > JOB_FAILURE_RATE
    }

    fun slowQueriesTriggerAlert(slow: Int, total: Int): Boolean {
        if (total == 0) return false
        return slow.toDouble() / total > SLOW_QUERY_FRACTION
    }
}
