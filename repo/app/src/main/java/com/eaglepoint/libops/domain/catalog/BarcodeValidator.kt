package com.eaglepoint.libops.domain.catalog

/**
 * Barcode validation (§9.9, §17).
 *
 * - 8-14 alphanumeric characters
 * - Uppercase canonical storage
 * - No whitespace
 * - Unique in active/reserved set (enforced by DB index)
 */
object BarcodeValidator {
    private val REGEX = Regex("^[A-Z0-9]{8,14}$")

    fun canonicalize(raw: String?): String? {
        if (raw == null) return null
        val cleaned = raw.trim().uppercase()
        return cleaned.ifEmpty { null }
    }

    fun isValid(code: String?): Boolean {
        val c = canonicalize(code) ?: return false
        return REGEX.matches(c)
    }
}
