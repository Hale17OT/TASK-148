package com.eaglepoint.libops.domain.catalog

/**
 * Taxonomy integrity (§9.11).
 *
 * - Node names unique within same parent
 * - No cycles
 * - No deletion of nodes with children (re-parent or archive instead)
 */
object TaxonomyValidator {

    data class Node(val id: Long, val parentId: Long?)

    /** True if setting [nodeId]'s parent to [newParentId] would create a cycle. */
    fun wouldCreateCycle(nodes: List<Node>, nodeId: Long, newParentId: Long?): Boolean {
        if (newParentId == null) return false
        if (newParentId == nodeId) return true
        val byId = nodes.associateBy { it.id }
        val visited = hashSetOf<Long>()
        var cursor: Long? = newParentId
        while (cursor != null) {
            if (cursor == nodeId) return true
            if (!visited.add(cursor)) return true // dangling cycle
            cursor = byId[cursor]?.parentId
        }
        return false
    }
}
