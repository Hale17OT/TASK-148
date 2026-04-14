package com.eaglepoint.libops.ui

import com.eaglepoint.libops.domain.auth.Capabilities

/**
 * Maps nav destinations to required capabilities (§11). Items whose required
 * capability is not granted are hidden from the UI.
 *
 * Each entry also carries a display glyph + subtitle so the home screen
 * looks like more than a flat list of actions.
 */
object Navigation {

    data class Entry(
        val key: String,
        val label: String,
        val subtitle: String,
        val glyph: String,
        val required: String,
    )

    val ALL: List<Entry> = listOf(
        Entry("dashboard", "Analytics", "Funnel, KPIs, quality scores", "A", Capabilities.ANALYTICS_READ),
        Entry("collection_runs", "Collection Runs", "Sources, jobs, retries", "R", Capabilities.JOBS_READ),
        Entry("records", "Master Records", "Catalog, search, versions", "M", Capabilities.RECORDS_READ),
        Entry("duplicates", "Duplicate Review", "Guided merge queue", "D", Capabilities.DUPLICATES_READ),
        Entry("alerts", "Alerts", "SLA, acknowledge, resolve", "!", Capabilities.ALERTS_READ),
        Entry("audit_logs", "Audit Logs", "Hash-chain verified trail", "L", Capabilities.AUDIT_READ),
        Entry("imports", "Imports & Exports", "CSV / JSON / signed bundles", "I", Capabilities.IMPORTS_RUN),
        Entry("admin", "Admin", "Users, roles, settings", "U", Capabilities.USERS_MANAGE),
        Entry("secrets", "Secrets", "Encrypted at rest", "S", Capabilities.SECRETS_READ_MASKED),
    )

    fun visibleFor(capabilities: Set<String>): List<Entry> =
        ALL.filter { it.required in capabilities }
}
