package com.eaglepoint.libops.domain.catalog

import com.eaglepoint.libops.domain.FieldError

/**
 * Holdings adjustment policy (§9.10, §17).
 */
object HoldingsAdjustment {
    val REASON_CODES = setOf(
        "acquisition", "correction", "return", "withdrawal", "inventory_recount",
    )

    data class Input(val currentCount: Int, val delta: Int, val reason: String)

    fun validate(input: Input): List<FieldError> {
        val errors = mutableListOf<FieldError>()
        if (input.reason !in REASON_CODES) {
            errors += FieldError("reason", "invalid", "Unknown reason code")
        }
        val newCount = input.currentCount + input.delta
        if (newCount < 0) {
            errors += FieldError("delta", "negative_result", "Holding count may not be negative")
        }
        return errors
    }

    fun apply(input: Input): Int = input.currentCount + input.delta
}
