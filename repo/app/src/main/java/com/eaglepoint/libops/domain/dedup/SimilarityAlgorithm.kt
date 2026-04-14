package com.eaglepoint.libops.domain.dedup

import kotlin.math.max
import kotlin.math.min

/**
 * Jaro-Winkler similarity with normalized prefix scaling. See PRD §13 and
 * ASSUMPTIONS.md.
 *
 * Chosen as the fixed, deterministic similarity function because it:
 * - runs in O(n*m) with tiny constant factors
 * - is stable across library updates (pure math, no external model)
 * - has well-understood thresholds matching the 0.85 / 0.95 defaults
 *
 * Inputs are expected to already be normalized via [TitleNormalizer].
 */
object SimilarityAlgorithm {
    const val ISBN_MATCH_TITLE_THRESHOLD = 0.85
    const val ISBN_MISSING_TITLE_THRESHOLD = 0.95

    /** Jaro similarity in [0.0, 1.0]. */
    fun jaro(s1: String, s2: String): Double {
        if (s1.isEmpty() && s2.isEmpty()) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        if (s1 == s2) return 1.0

        val matchDistance = max(s1.length, s2.length) / 2 - 1
        val s1Matches = BooleanArray(s1.length)
        val s2Matches = BooleanArray(s2.length)

        var matches = 0
        for (i in s1.indices) {
            val start = max(0, i - matchDistance)
            val end = min(i + matchDistance + 1, s2.length)
            for (j in start until end) {
                if (s2Matches[j]) continue
                if (s1[i] != s2[j]) continue
                s1Matches[i] = true
                s2Matches[j] = true
                matches++
                break
            }
        }
        if (matches == 0) return 0.0

        var transpositions = 0
        var k = 0
        for (i in s1.indices) {
            if (!s1Matches[i]) continue
            while (!s2Matches[k]) k++
            if (s1[i] != s2[k]) transpositions++
            k++
        }
        val m = matches.toDouble()
        return (m / s1.length + m / s2.length + (m - transpositions / 2.0) / m) / 3.0
    }

    /**
     * Jaro-Winkler with prefix scaling factor 0.1 (default per Winkler 1990)
     * and max common prefix 4.
     */
    fun jaroWinkler(s1: String, s2: String, scalingFactor: Double = 0.1): Double {
        val jaro = jaro(s1, s2)
        val prefixLen = commonPrefix(s1, s2, maxLen = 4)
        return jaro + prefixLen * scalingFactor * (1.0 - jaro)
    }

    private fun commonPrefix(s1: String, s2: String, maxLen: Int): Int {
        val n = min(min(s1.length, s2.length), maxLen)
        var i = 0
        while (i < n && s1[i] == s2[i]) i++
        return i
    }
}
