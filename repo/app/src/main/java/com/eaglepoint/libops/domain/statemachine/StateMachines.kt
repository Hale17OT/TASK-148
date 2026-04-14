package com.eaglepoint.libops.domain.statemachine

/**
 * State machine declarations (§10). Each machine captures the allowed
 * transitions and enforces illegal transition blocking.
 */
interface StateMachine {
    val allowed: Map<String, Set<String>>
    val initial: String

    fun canTransition(from: String, to: String): Boolean =
        allowed[from]?.contains(to) ?: false
}

/** §10.1 */
object UserAccountStateMachine : StateMachine {
    override val initial = "pending_activation"
    override val allowed = mapOf(
        "pending_activation" to setOf("active", "disabled"),
        "active" to setOf("locked", "disabled", "password_reset_required"),
        "locked" to setOf("active", "disabled"),
        "password_reset_required" to setOf("active", "disabled"),
        "disabled" to setOf("active"),
    )
}

/** §10.2 */
object SessionStateMachine : StateMachine {
    override val initial = "created"
    override val allowed = mapOf(
        "created" to setOf("authenticated", "expired"),
        "authenticated" to setOf("biometric_eligible", "expired", "revoked"),
        "biometric_eligible" to setOf("authenticated", "expired", "revoked"),
        "expired" to emptySet(),
        "revoked" to emptySet(),
    )
}

/** §10.3 */
object CollectionSourceStateMachine : StateMachine {
    override val initial = "draft"
    override val allowed = mapOf(
        "draft" to setOf("active", "archived"),
        "active" to setOf("disabled", "archived"),
        "disabled" to setOf("active", "archived"),
        "archived" to emptySet(),
    )
}

/** §10.4 */
object JobStateMachine : StateMachine {
    override val initial = "scheduled"
    override val allowed = mapOf(
        "scheduled" to setOf("queued", "cancelled"),
        "queued" to setOf("running", "cancelled", "paused_low_battery"),
        "running" to setOf(
            "succeeded", "failed", "retry_waiting", "paused_low_battery", "cancelled_partial",
        ),
        "retry_waiting" to setOf("queued", "failed"),
        "paused_low_battery" to setOf("queued", "cancelled"),
        "failed" to setOf("retry_waiting", "terminal_failed"),
        "terminal_failed" to emptySet(),
        "succeeded" to emptySet(),
        "cancelled" to emptySet(),
        "cancelled_partial" to emptySet(),
    )
}

/** §10.5 */
object ImportBatchStateMachine : StateMachine {
    override val initial = "received"
    override val allowed = mapOf(
        "received" to setOf("validating", "rejected_invalid_bundle"),
        "validating" to setOf("staged", "rejected_validation_failure"),
        "staged" to setOf("awaiting_merge_review", "accepted_all", "rejected_all"),
        "awaiting_merge_review" to setOf("accepted_partial", "accepted_all", "rejected_all"),
        "accepted_partial" to setOf("completed"),
        "accepted_all" to setOf("completed"),
        "rejected_invalid_bundle" to emptySet(),
        "rejected_validation_failure" to emptySet(),
        "rejected_all" to setOf("completed"),
        "completed" to emptySet(),
    )
}

/** §10.6 */
object DuplicateStateMachine : StateMachine {
    override val initial = "detected"
    override val allowed = mapOf(
        "detected" to setOf("under_review", "dismissed"),
        "under_review" to setOf("merged", "dismissed", "escalated"),
        "escalated" to setOf("merged", "dismissed"),
        "merged" to setOf("reversed"),
        "dismissed" to setOf("reopened"),
        "reversed" to setOf("reopened"),
        "reopened" to setOf("under_review", "dismissed"),
    )
}

/** §10.7 */
object AlertStateMachine : StateMachine {
    override val initial = "open"
    override val allowed = mapOf(
        "open" to setOf("acknowledged", "auto_suppressed"),
        "acknowledged" to setOf("resolved", "overdue"),
        "overdue" to setOf("resolved"),
        "resolved" to setOf("reopened"),
        "reopened" to setOf("acknowledged"),
        "auto_suppressed" to setOf("reopened"),
    )
}

/** §10.8 */
object BarcodeStateMachine : StateMachine {
    override val initial = "available"
    override val allowed = mapOf(
        "available" to setOf("assigned", "retired"),
        "assigned" to setOf("retired", "suspended"),
        "suspended" to setOf("assigned", "retired"),
        "retired" to setOf("reserved_hold"),
        "reserved_hold" to setOf("available_after_expiry"),
        "available_after_expiry" to emptySet(),
    )
}
