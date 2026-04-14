package com.eaglepoint.libops.domain.dedup

import com.eaglepoint.libops.domain.catalog.IsbnValidator
import com.eaglepoint.libops.domain.catalog.TitleNormalizer

/**
 * Duplicate detection (§9.6).
 */
object DuplicateDetector {

    data class Candidate(
        val title: String?,
        val publisher: String?,
        val isbn10: String?,
        val isbn13: String?,
    )

    sealed interface Verdict {
        data object NotDuplicate : Verdict
        data class DuplicateCandidate(val score: Double, val rule: String) : Verdict
        data class PossibleDuplicate(val score: Double, val rule: String) : Verdict
    }

    fun evaluate(a: Candidate, b: Candidate): Verdict {
        val aTitle = TitleNormalizer.normalize(a.title)
        val bTitle = TitleNormalizer.normalize(b.title)
        val aIsbn13 = IsbnValidator.normalize(a.isbn13)
        val bIsbn13 = IsbnValidator.normalize(b.isbn13)
        val aIsbn10 = IsbnValidator.normalize(a.isbn10)
        val bIsbn10 = IsbnValidator.normalize(b.isbn10)

        val titleSim = SimilarityAlgorithm.jaroWinkler(aTitle, bTitle)
        val isbn13Match = aIsbn13 != null && bIsbn13 != null && aIsbn13 == bIsbn13
        val isbn10Match = aIsbn10 != null && bIsbn10 != null && aIsbn10 == bIsbn10

        if ((isbn13Match || isbn10Match) && titleSim >= SimilarityAlgorithm.ISBN_MATCH_TITLE_THRESHOLD) {
            return Verdict.DuplicateCandidate(titleSim, "isbn_match+title>=0.85")
        }

        val noIsbnOnEither = (aIsbn13 == null && aIsbn10 == null) || (bIsbn13 == null && bIsbn10 == null)
        val publisherMatch = !a.publisher.isNullOrBlank() && a.publisher.equals(b.publisher, ignoreCase = true)
        if (noIsbnOnEither && publisherMatch && titleSim >= SimilarityAlgorithm.ISBN_MISSING_TITLE_THRESHOLD) {
            return Verdict.PossibleDuplicate(titleSim, "no_isbn+publisher_match+title>=0.95")
        }

        return Verdict.NotDuplicate
    }
}
