package com.eaglepoint.libops.tests

import com.eaglepoint.libops.domain.catalog.BarcodeValidator
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BarcodeValidatorTest {

    @Test
    fun canonicalize_trims_and_uppercases() {
        assertThat(BarcodeValidator.canonicalize("  abc12345 ")).isEqualTo("ABC12345")
    }

    @Test
    fun length_range_enforced() {
        assertThat(BarcodeValidator.isValid("1234567")).isFalse()          // 7
        assertThat(BarcodeValidator.isValid("12345678")).isTrue()          // 8
        assertThat(BarcodeValidator.isValid("12345678901234")).isTrue()    // 14
        assertThat(BarcodeValidator.isValid("123456789012345")).isFalse()  // 15
    }

    @Test
    fun only_alphanumeric_allowed() {
        assertThat(BarcodeValidator.isValid("ABC 12345")).isFalse()
        assertThat(BarcodeValidator.isValid("ABC-12345")).isFalse()
        assertThat(BarcodeValidator.isValid("ABC12345")).isTrue()
    }

    @Test
    fun uppercase_enforced_after_canonicalization() {
        assertThat(BarcodeValidator.isValid("abc12345")).isTrue()
        assertThat(BarcodeValidator.canonicalize("abc12345")).isEqualTo("ABC12345")
    }

    @Test
    fun rejects_empty_and_null() {
        assertThat(BarcodeValidator.isValid(null)).isFalse()
        assertThat(BarcodeValidator.isValid("")).isFalse()
        assertThat(BarcodeValidator.isValid("   ")).isFalse()
    }
}
