package com.eaglepoint.libops.tests

import com.eaglepoint.libops.domain.auth.PasswordHasher
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PasswordHasherTest {

    @Test
    fun hashes_are_deterministic_for_same_inputs() {
        val salt = ByteArray(16) { it.toByte() }
        val a = PasswordHasher.hash("Passw0rd!".toCharArray(), salt, iterations = 1000)
        val b = PasswordHasher.hash("Passw0rd!".toCharArray(), salt, iterations = 1000)
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun different_passwords_produce_different_hashes() {
        val salt = ByteArray(16) { it.toByte() }
        val a = PasswordHasher.hash("Passw0rd!".toCharArray(), salt, iterations = 1000)
        val b = PasswordHasher.hash("Passw0rd@".toCharArray(), salt, iterations = 1000)
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun different_salts_produce_different_hashes() {
        val a = PasswordHasher.hash("Same".toCharArray(), ByteArray(16) { 1 }, iterations = 1000)
        val b = PasswordHasher.hash("Same".toCharArray(), ByteArray(16) { 2 }, iterations = 1000)
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun verify_returns_true_for_correct_password() {
        val salt = PasswordHasher.generateSalt()
        val expected = PasswordHasher.hash("Secret1234!@".toCharArray(), salt, iterations = 1000)
        assertThat(
            PasswordHasher.verify("Secret1234!@".toCharArray(), salt, expected, iterations = 1000),
        ).isTrue()
    }

    @Test
    fun verify_returns_false_for_wrong_password() {
        val salt = PasswordHasher.generateSalt()
        val expected = PasswordHasher.hash("Secret1234!@".toCharArray(), salt, iterations = 1000)
        assertThat(
            PasswordHasher.verify("Secret1234!@x".toCharArray(), salt, expected, iterations = 1000),
        ).isFalse()
    }

    @Test
    fun base64_roundtrip() {
        val bytes = ByteArray(32) { it.toByte() }
        val encoded = PasswordHasher.encodeBase64(bytes)
        val decoded = PasswordHasher.decodeBase64(encoded)
        assertThat(decoded).isEqualTo(bytes)
    }
}
