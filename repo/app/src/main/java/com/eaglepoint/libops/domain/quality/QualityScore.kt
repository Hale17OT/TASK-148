package com.eaglepoint.libops.domain.quality

import kotlin.math.max

/**
 * Quality score formula per PRD §9.15.
 *
 * ```
 * quality_score = max(0,
 *   100
 *   - (2 * validation_failures_last_30d)
 *   - (5 * policy_violations_last_30d)
 *   - (3 * overdue_alerts_last_30d)
 *   - (1 * unresolved_duplicate_decisions_older_than_7d)
 * )
 * ```
 *
 * Range: 0..100, integer display.
 */
object QualityScore {
    const val MAX_SCORE = 100

    data class Inputs(
        val validationFailures30d: Int,
        val policyViolations30d: Int,
        val overdueAlerts30d: Int,
        val unresolvedDuplicates7d: Int,
    )

    fun compute(i: Inputs): Int {
        val raw = MAX_SCORE -
            (2 * i.validationFailures30d) -
            (5 * i.policyViolations30d) -
            (3 * i.overdueAlerts30d) -
            (1 * i.unresolvedDuplicates7d)
        return max(0, raw)
    }
}
