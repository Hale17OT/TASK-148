package com.eaglepoint.libops.domain.auth

/**
 * Capability groups and role defaults (§11).
 */
object Capabilities {
    const val USERS_READ = "users.read"
    const val USERS_MANAGE = "users.manage"
    const val ROLES_MANAGE = "roles.manage"
    const val PERMISSIONS_MANAGE = "permissions.manage"
    const val SECRETS_READ_MASKED = "secrets.read_masked"
    const val SECRETS_READ_FULL = "secrets.read_full"
    const val SECRETS_MANAGE = "secrets.manage"
    const val SOURCES_READ = "sources.read"
    const val SOURCES_MANAGE = "sources.manage"
    const val JOBS_READ = "jobs.read"
    const val JOBS_RUN = "jobs.run"
    const val JOBS_RETRY = "jobs.retry"
    const val JOBS_CANCEL = "jobs.cancel"
    const val DUPLICATES_READ = "duplicates.read"
    const val DUPLICATES_RESOLVE = "duplicates.resolve"
    const val RECORDS_READ = "records.read"
    const val RECORDS_MANAGE = "records.manage"
    const val TAXONOMY_MANAGE = "taxonomy.manage"
    const val HOLDINGS_MANAGE = "holdings.manage"
    const val BARCODES_MANAGE = "barcodes.manage"
    const val IMPORTS_RUN = "imports.run"
    const val EXPORTS_RUN = "exports.run"
    const val AUDIT_READ = "audit.read"
    const val ALERTS_READ = "alerts.read"
    const val ALERTS_ACKNOWLEDGE = "alerts.acknowledge"
    const val ALERTS_RESOLVE = "alerts.resolve"
    const val ANALYTICS_READ = "analytics.read"
    const val QUALITY_SCORES_READ_ALL = "quality_scores.read_all"

    val ALL: List<String> = listOf(
        USERS_READ, USERS_MANAGE, ROLES_MANAGE, PERMISSIONS_MANAGE,
        SECRETS_READ_MASKED, SECRETS_READ_FULL, SECRETS_MANAGE,
        SOURCES_READ, SOURCES_MANAGE,
        JOBS_READ, JOBS_RUN, JOBS_RETRY, JOBS_CANCEL,
        DUPLICATES_READ, DUPLICATES_RESOLVE,
        RECORDS_READ, RECORDS_MANAGE, TAXONOMY_MANAGE, HOLDINGS_MANAGE, BARCODES_MANAGE,
        IMPORTS_RUN, EXPORTS_RUN,
        AUDIT_READ, ALERTS_READ, ALERTS_ACKNOWLEDGE, ALERTS_RESOLVE,
        ANALYTICS_READ, QUALITY_SCORES_READ_ALL,
    )
}

object Roles {
    const val ADMIN = "administrator"
    const val COLLECTION_MANAGER = "collection_manager"
    const val CATALOGER = "cataloger"
    const val AUDITOR = "auditor"

    val DEFAULT_MAPPING: Map<String, Set<String>> = mapOf(
        ADMIN to Capabilities.ALL.toSet(),
        COLLECTION_MANAGER to setOf(
            Capabilities.SOURCES_READ, Capabilities.SOURCES_MANAGE,
            Capabilities.JOBS_READ, Capabilities.JOBS_RUN, Capabilities.JOBS_RETRY, Capabilities.JOBS_CANCEL,
            Capabilities.DUPLICATES_READ, Capabilities.DUPLICATES_RESOLVE,
            Capabilities.RECORDS_READ,
            Capabilities.IMPORTS_RUN, Capabilities.EXPORTS_RUN,
            Capabilities.SECRETS_READ_MASKED,
            Capabilities.ALERTS_READ, Capabilities.ALERTS_ACKNOWLEDGE, Capabilities.ALERTS_RESOLVE,
            Capabilities.ANALYTICS_READ,
        ),
        CATALOGER to setOf(
            Capabilities.RECORDS_READ, Capabilities.RECORDS_MANAGE,
            Capabilities.TAXONOMY_MANAGE, Capabilities.HOLDINGS_MANAGE, Capabilities.BARCODES_MANAGE,
            Capabilities.DUPLICATES_READ, Capabilities.DUPLICATES_RESOLVE,
            Capabilities.IMPORTS_RUN, Capabilities.EXPORTS_RUN,
            Capabilities.ALERTS_READ, Capabilities.ALERTS_ACKNOWLEDGE, Capabilities.ALERTS_RESOLVE,
            Capabilities.ANALYTICS_READ,
        ),
        AUDITOR to setOf(
            Capabilities.USERS_READ,
            Capabilities.SECRETS_READ_MASKED,
            Capabilities.SOURCES_READ,
            Capabilities.JOBS_READ,
            Capabilities.DUPLICATES_READ,
            Capabilities.RECORDS_READ,
            Capabilities.AUDIT_READ,
            Capabilities.ALERTS_READ, Capabilities.ALERTS_ACKNOWLEDGE, Capabilities.ALERTS_RESOLVE,
            Capabilities.ANALYTICS_READ, Capabilities.QUALITY_SCORES_READ_ALL,
        ),
    )
}

/**
 * Pure authorization evaluator. Takes the caller's permissions and checks
 * whether the requested capability is granted.
 */
class Authorizer(private val grants: Set<String>) {
    fun has(capability: String): Boolean = grants.contains(capability)
    fun requireAny(vararg capabilities: String): Boolean = capabilities.any { has(it) }
    fun require(capability: String) {
        if (!has(capability)) throw SecurityException("Missing capability: $capability")
    }
}
