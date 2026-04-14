package com.eaglepoint.libops.tests

import com.eaglepoint.libops.domain.quality.QualityScore
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class QualityScoreTest {

    @Test
    fun zero_events_gives_max_score() {
        val score = QualityScore.compute(QualityScore.Inputs(0, 0, 0, 0))
        assertThat(score).isEqualTo(100)
    }

    @Test
    fun validation_failures_weighted_by_2() {
        val score = QualityScore.compute(QualityScore.Inputs(5, 0, 0, 0))
        assertThat(score).isEqualTo(90)
    }

    @Test
    fun policy_violations_weighted_by_5() {
        val score = QualityScore.compute(QualityScore.Inputs(0, 4, 0, 0))
        assertThat(score).isEqualTo(80)
    }

    @Test
    fun overdue_alerts_weighted_by_3() {
        val score = QualityScore.compute(QualityScore.Inputs(0, 0, 10, 0))
        assertThat(score).isEqualTo(70)
    }

    @Test
    fun unresolved_duplicates_weighted_by_1() {
        val score = QualityScore.compute(QualityScore.Inputs(0, 0, 0, 7))
        assertThat(score).isEqualTo(93)
    }

    @Test
    fun score_clamped_to_zero() {
        val score = QualityScore.compute(QualityScore.Inputs(100, 100, 100, 100))
        assertThat(score).isEqualTo(0)
    }

    @Test
    fun combined_score_is_additive() {
        // 100 - 2*3 - 5*2 - 3*4 - 1*5 = 67
        val score = QualityScore.compute(QualityScore.Inputs(3, 2, 4, 5))
        assertThat(score).isEqualTo(67)
    }
}
