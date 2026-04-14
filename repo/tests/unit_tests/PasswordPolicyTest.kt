package com.eaglepoint.libops.tests

import com.eaglepoint.libops.domain.auth.PasswordPolicy
import com.eaglepoint.libops.domain.auth.UsernamePolicy
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PasswordPolicyTest {

    @Test
    fun rejects_short_password() {
        val errors = PasswordPolicy.validate("aB1!short")
        assertThat(errors.map { it.code }).contains("too_short")
    }

    @Test
    fun rejects_password_missing_symbol() {
        val errors = PasswordPolicy.validate("Abcdefgh1234")
        assertThat(errors.map { it.code }).contains("missing_symbol")
    }

    @Test
    fun rejects_password_missing_upper() {
        val errors = PasswordPolicy.validate("abcdefgh1234!")
        assertThat(errors.map { it.code }).contains("missing_upper")
    }

    @Test
    fun rejects_password_missing_lower() {
        val errors = PasswordPolicy.validate("ABCDEFGH1234!")
        assertThat(errors.map { it.code }).contains("missing_lower")
    }

    @Test
    fun rejects_password_missing_digit() {
        val errors = PasswordPolicy.validate("Abcdefghijkl!")
        assertThat(errors.map { it.code }).contains("missing_digit")
    }

    @Test
    fun accepts_valid_password() {
        assertThat(PasswordPolicy.isValid("StrongPass1@word")).isTrue()
    }

    @Test
    fun username_length_validated() {
        assertThat(UsernamePolicy.validate("ab").map { it.code }).contains("length")
        assertThat(UsernamePolicy.validate("a".repeat(65)).map { it.code }).contains("length")
    }

    @Test
    fun username_format_validated() {
        assertThat(UsernamePolicy.validate("bad user").map { it.code }).contains("format")
        assertThat(UsernamePolicy.validate("good.user_01")).isEmpty()
    }
}
