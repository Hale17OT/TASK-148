package com.eaglepoint.libops.domain.catalog

import java.text.Normalizer

/**
 * Title normalization (§13).
 *
 * - Unicode NFKD normalization
 * - Lowercased
 * - Punctuation stripped (non-alphanumeric to space)
 * - Whitespace collapsed
 * - Trimmed
 */
object TitleNormalizer {
    private val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]+")
    private val MULTI_SPACE = Regex("\\s+")

    fun normalize(raw: String?): String {
        if (raw == null) return ""
        val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFKD)
        val stripped = decomposed.replace(NON_ALPHANUMERIC, " ")
        return stripped.trim().lowercase().replace(MULTI_SPACE, " ")
    }
}
