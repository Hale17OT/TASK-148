package com.eaglepoint.libops.tests

import com.eaglepoint.libops.domain.catalog.HoldingsAdjustment
import com.eaglepoint.libops.domain.catalog.RecordValidator
import com.eaglepoint.libops.domain.catalog.TaxonomyValidator
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecordValidatorTest {

    private val now = 1_700_000_000_000L

    @Test
    fun title_required() {
        val errors = RecordValidator.validate(
            RecordValidator.Input(
                title = null,
                publisher = null,
                pubDateEpochMillis = null,
                format = null,
                category = RecordValidator.Category.BOOK,
                isbn10 = null,
                isbn13 = null,
                nowEpochMillis = now,
            )
        )
        assertThat(errors.map { it.field }).contains("title")
    }

    @Test
    fun book_requires_format() {
        val errors = RecordValidator.validate(
            RecordValidator.Input(
                title = "My Book",
                publisher = "Acme",
                pubDateEpochMillis = null,
                format = null,
                category = RecordValidator.Category.BOOK,
                isbn10 = null,
                isbn13 = null,
                nowEpochMillis = now,
            )
        )
        assertThat(errors.map { it.code }).contains("required")
        assertThat(errors.map { it.field }).contains("format")
    }

    @Test
    fun journal_requires_publisher() {
        val errors = RecordValidator.validate(
            RecordValidator.Input(
                title = "My Journal",
                publisher = null,
                pubDateEpochMillis = null,
                format = null,
                category = RecordValidator.Category.JOURNAL,
                isbn10 = null,
                isbn13 = null,
                nowEpochMillis = now,
            )
        )
        assertThat(errors.map { it.field }).contains("publisher")
    }

    @Test
    fun other_category_only_requires_title() {
        val errors = RecordValidator.validate(
            RecordValidator.Input(
                title = "Map of Narnia",
                publisher = null,
                pubDateEpochMillis = null,
                format = null,
                category = RecordValidator.Category.OTHER,
                isbn10 = null,
                isbn13 = null,
                nowEpochMillis = now,
            )
        )
        assertThat(errors).isEmpty()
    }

    @Test
    fun future_pub_date_rejected() {
        val errors = RecordValidator.validate(
            RecordValidator.Input(
                title = "Future Book",
                publisher = "Acme",
                pubDateEpochMillis = now + 10_000,
                format = "hardcover",
                category = RecordValidator.Category.BOOK,
                isbn10 = null,
                isbn13 = null,
                nowEpochMillis = now,
            )
        )
        assertThat(errors.map { it.code }).contains("future_not_allowed")
    }

    @Test
    fun mismatched_isbn_forms_rejected() {
        val errors = RecordValidator.validate(
            RecordValidator.Input(
                title = "Ok",
                publisher = "Acme",
                pubDateEpochMillis = null,
                format = "hardcover",
                category = RecordValidator.Category.BOOK,
                isbn10 = "0306406152",
                isbn13 = "9783161484100",
                nowEpochMillis = now,
            )
        )
        assertThat(errors.any { it.code == "inconsistent" }).isTrue()
    }

    @Test
    fun holdings_adjustment_requires_known_reason() {
        val errors = HoldingsAdjustment.validate(HoldingsAdjustment.Input(5, 2, "mystery"))
        assertThat(errors.map { it.code }).contains("invalid")
    }

    @Test
    fun holdings_adjustment_cannot_go_negative() {
        val errors = HoldingsAdjustment.validate(HoldingsAdjustment.Input(1, -5, "withdrawal"))
        assertThat(errors.map { it.code }).contains("negative_result")
    }

    @Test
    fun holdings_adjustment_applies_delta() {
        val errors = HoldingsAdjustment.validate(HoldingsAdjustment.Input(5, 2, "acquisition"))
        assertThat(errors).isEmpty()
        assertThat(HoldingsAdjustment.apply(HoldingsAdjustment.Input(5, 2, "acquisition"))).isEqualTo(7)
    }

    @Test
    fun taxonomy_self_cycle_detected() {
        val nodes = listOf(TaxonomyValidator.Node(1, null), TaxonomyValidator.Node(2, 1))
        assertThat(TaxonomyValidator.wouldCreateCycle(nodes, nodeId = 1, newParentId = 1)).isTrue()
    }

    @Test
    fun taxonomy_indirect_cycle_detected() {
        val nodes = listOf(
            TaxonomyValidator.Node(1, null),
            TaxonomyValidator.Node(2, 1),
            TaxonomyValidator.Node(3, 2),
        )
        // Setting 1's parent to 3 would create 1 -> 3 -> 2 -> 1 cycle
        assertThat(TaxonomyValidator.wouldCreateCycle(nodes, nodeId = 1, newParentId = 3)).isTrue()
    }

    @Test
    fun taxonomy_valid_reparenting_allowed() {
        val nodes = listOf(
            TaxonomyValidator.Node(1, null),
            TaxonomyValidator.Node(2, null),
            TaxonomyValidator.Node(3, 1),
        )
        assertThat(TaxonomyValidator.wouldCreateCycle(nodes, nodeId = 3, newParentId = 2)).isFalse()
    }
}
