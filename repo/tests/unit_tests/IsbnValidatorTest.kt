package com.eaglepoint.libops.tests

import com.eaglepoint.libops.domain.catalog.IsbnValidator
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IsbnValidatorTest {

    @Test
    fun normalize_removes_dashes_and_spaces_and_uppercases() {
        assertThat(IsbnValidator.normalize("978-3-16-148410-0")).isEqualTo("9783161484100")
        assertThat(IsbnValidator.normalize(" 0-306-40615-2 ")).isEqualTo("0306406152")
        assertThat(IsbnValidator.normalize("978316148410x")).isEqualTo("978316148410X")
    }

    @Test
    fun valid_isbn10_examples() {
        assertThat(IsbnValidator.isValidIsbn10("0306406152")).isTrue()
        assertThat(IsbnValidator.isValidIsbn10("0321146530")).isTrue()
        assertThat(IsbnValidator.isValidIsbn10("043942089X")).isTrue()
    }

    @Test
    fun invalid_isbn10_examples() {
        assertThat(IsbnValidator.isValidIsbn10("0306406153")).isFalse()
        assertThat(IsbnValidator.isValidIsbn10("12345")).isFalse()
        assertThat(IsbnValidator.isValidIsbn10("ABCDEFGHIJ")).isFalse()
    }

    @Test
    fun valid_isbn13_examples() {
        assertThat(IsbnValidator.isValidIsbn13("9783161484100")).isTrue()
        assertThat(IsbnValidator.isValidIsbn13("9780306406157")).isTrue()
        assertThat(IsbnValidator.isValidIsbn13("9780321146533")).isTrue()
    }

    @Test
    fun invalid_isbn13_examples() {
        assertThat(IsbnValidator.isValidIsbn13("9783161484101")).isFalse()
        assertThat(IsbnValidator.isValidIsbn13("123")).isFalse()
    }

    @Test
    fun convert_isbn10_to_isbn13() {
        assertThat(IsbnValidator.isbn10To13("0306406152")).isEqualTo("9780306406157")
        assertThat(IsbnValidator.isbn10To13("0321146530")).isEqualTo("9780321146533")
    }

    @Test
    fun consistent_returns_true_when_matching_forms() {
        assertThat(IsbnValidator.consistent("0306406152", "9780306406157")).isTrue()
    }

    @Test
    fun consistent_returns_false_when_mismatched() {
        assertThat(IsbnValidator.consistent("0306406152", "9783161484100")).isFalse()
    }

    @Test
    fun consistent_returns_true_when_only_one_form() {
        assertThat(IsbnValidator.consistent("0306406152", null)).isTrue()
        assertThat(IsbnValidator.consistent(null, "9783161484100")).isTrue()
        assertThat(IsbnValidator.consistent(null, null)).isTrue()
    }

    @Test
    fun isValid_top_level_dispatches_by_length() {
        assertThat(IsbnValidator.isValid("9783161484100")).isTrue()
        assertThat(IsbnValidator.isValid("0306406152")).isTrue()
        assertThat(IsbnValidator.isValid("bogus")).isFalse()
        assertThat(IsbnValidator.isValid(null)).isFalse()
    }
}
