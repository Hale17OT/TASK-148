package com.eaglepoint.libops.tests

import com.eaglepoint.libops.domain.catalog.TitleNormalizer
import com.eaglepoint.libops.domain.dedup.DuplicateDetector
import com.eaglepoint.libops.domain.dedup.SimilarityAlgorithm
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SimilarityAlgorithmTest {

    @Test
    fun identical_strings_score_1() {
        assertThat(SimilarityAlgorithm.jaroWinkler("abc", "abc")).isEqualTo(1.0)
    }

    @Test
    fun empty_both_scores_1() {
        assertThat(SimilarityAlgorithm.jaroWinkler("", "")).isEqualTo(1.0)
    }

    @Test
    fun completely_different_scores_0() {
        assertThat(SimilarityAlgorithm.jaroWinkler("abc", "xyz")).isEqualTo(0.0)
    }

    @Test
    fun similar_titles_exceed_isbn_threshold() {
        val a = TitleNormalizer.normalize("The Lord of the Rings")
        val b = TitleNormalizer.normalize("The Lord of the Rings!")
        val score = SimilarityAlgorithm.jaroWinkler(a, b)
        assertThat(score).isAtLeast(SimilarityAlgorithm.ISBN_MATCH_TITLE_THRESHOLD)
    }

    @Test
    fun dissimilar_titles_below_threshold() {
        val a = TitleNormalizer.normalize("The Lord of the Rings")
        val b = TitleNormalizer.normalize("Introduction to Algorithms")
        val score = SimilarityAlgorithm.jaroWinkler(a, b)
        assertThat(score).isLessThan(SimilarityAlgorithm.ISBN_MATCH_TITLE_THRESHOLD)
    }

    @Test
    fun title_normalizer_collapses_whitespace_and_lowercases() {
        assertThat(TitleNormalizer.normalize("  Hello,   World!  ")).isEqualTo("hello world")
    }

    @Test
    fun title_normalizer_strips_punctuation() {
        assertThat(TitleNormalizer.normalize("C++!: Primer")).isEqualTo("c primer")
    }

    @Test
    fun duplicate_detected_when_isbn13_matches_and_title_close() {
        val verdict = DuplicateDetector.evaluate(
            DuplicateDetector.Candidate("The Lord of the Rings", "Allen & Unwin", null, "9780261103252"),
            DuplicateDetector.Candidate("The Lord of the Rings.", "Allen Unwin", null, "9780261103252"),
        )
        assertThat(verdict).isInstanceOf(DuplicateDetector.Verdict.DuplicateCandidate::class.java)
    }

    @Test
    fun possible_duplicate_when_no_isbn_but_publisher_match_and_very_similar_title() {
        val verdict = DuplicateDetector.evaluate(
            DuplicateDetector.Candidate("Deep Learning", "MIT Press", null, null),
            DuplicateDetector.Candidate("Deep Learning.", "MIT Press", null, null),
        )
        assertThat(verdict).isInstanceOf(DuplicateDetector.Verdict.PossibleDuplicate::class.java)
    }

    @Test
    fun not_duplicate_when_isbn_differs() {
        val verdict = DuplicateDetector.evaluate(
            DuplicateDetector.Candidate("Deep Learning", "MIT Press", null, "9780262035613"),
            DuplicateDetector.Candidate("Deep Learning", "MIT Press", null, "9780262033848"),
        )
        assertThat(verdict).isEqualTo(DuplicateDetector.Verdict.NotDuplicate)
    }

    @Test
    fun not_duplicate_when_no_isbn_and_publisher_mismatch() {
        val verdict = DuplicateDetector.evaluate(
            DuplicateDetector.Candidate("Deep Learning", "MIT Press", null, null),
            DuplicateDetector.Candidate("Deep Learning", "OtherPub", null, null),
        )
        assertThat(verdict).isEqualTo(DuplicateDetector.Verdict.NotDuplicate)
    }
}
