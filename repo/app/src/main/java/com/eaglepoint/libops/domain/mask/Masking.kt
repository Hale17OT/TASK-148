package com.eaglepoint.libops.domain.mask

/**
 * Sensitive data masking (§9.17).
 *
 * - Show only last 4 characters
 * - Preceding replaced with '*'
 * - Values < 4 chars fully masked
 */
object Masking {
    private const val MASK = '*'

    fun mask(value: String?): String {
        if (value == null) return ""
        if (value.length < 4) return MASK.toString().repeat(value.length)
        val visible = value.takeLast(4)
        val hiddenLen = value.length - 4
        return MASK.toString().repeat(hiddenLen) + visible
    }
}
