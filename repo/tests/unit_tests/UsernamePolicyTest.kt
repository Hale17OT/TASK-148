package com.eaglepoint.libops.tests

import com.eaglepoint.libops.domain.auth.UsernamePolicy
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for [UsernamePolicy.validate] covering length bounds, allowed
 * characters, and edge cases (§9.1).
 */
class UsernamePolicyTest {

    @Test
    fun valid_username_returns_no_errors() {
        assertThat(UsernamePolicy.validate("alice")).isEmpty()
    }

    @Test
    fun alphanumeric_with_dots_underscores_dashes_is_valid() {
        assertThat(UsernamePolicy.validate("cm_reviewer")).isEmpty()
        assertThat(UsernamePolicy.validate("user.name")).isEmpty()
        assertThat(UsernamePolicy.validate("user-1")).isEmpty()
    }

    @Test
    fun too_short_username_returns_length_error() {
        val errors = UsernamePolicy.validate("ab")
        assertThat(errors).isNotEmpty()
        assertThat(errors[0].code).isEqualTo("length")
    }

    @Test
    fun three_char_username_is_valid() {
        assertThat(UsernamePolicy.validate("abc")).isEmpty()
    }

    @Test
    fun sixty_four_char_username_is_valid() {
        val longName = "a".repeat(64)
        assertThat(UsernamePolicy.validate(longName)).isEmpty()
    }

    @Test
    fun sixty_five_char_username_returns_length_error() {
        val errors = UsernamePolicy.validate("a".repeat(65))
        assertThat(errors).isNotEmpty()
        assertThat(errors[0].code).isEqualTo("length")
    }

    @Test
    fun username_with_spaces_returns_format_error() {
        val errors = UsernamePolicy.validate("user name")
        assertThat(errors.any { it.code == "format" }).isTrue()
    }

    @Test
    fun username_with_special_chars_returns_format_error() {
        val errors = UsernamePolicy.validate("user@name!")
        assertThat(errors.any { it.code == "format" }).isTrue()
    }
}
