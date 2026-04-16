package com.eaglepoint.libops.tests

import com.eaglepoint.libops.domain.mask.Masking
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for [Masking.mask] — the utility used by [SecretRepository] to
 * produce safe display strings from sensitive values (§9.11).
 */
class MaskingTest {

    @Test
    fun null_value_returns_empty_string() {
        assertThat(Masking.mask(null)).isEmpty()
    }

    @Test
    fun short_value_under_four_chars_is_fully_masked() {
        assertThat(Masking.mask("abc")).isEqualTo("***")
    }

    @Test
    fun exactly_four_chars_shows_all_as_visible() {
        assertThat(Masking.mask("1234")).isEqualTo("1234")
    }

    @Test
    fun longer_value_shows_last_four_with_stars_prefix() {
        assertThat(Masking.mask("mysecretvalue")).isEqualTo("*********alue")
    }

    @Test
    fun empty_string_returns_empty_string() {
        assertThat(Masking.mask("")).isEmpty()
    }

    @Test
    fun single_char_is_fully_masked() {
        assertThat(Masking.mask("x")).isEqualTo("*")
    }

    @Test
    fun five_char_value_masks_first_char_only() {
        assertThat(Masking.mask("abcde")).isEqualTo("*bcde")
    }
}
