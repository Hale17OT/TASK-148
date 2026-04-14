package com.eaglepoint.libops.domain.auth

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Password hashing with PBKDF2WithHmacSHA256 (§14).
 *
 * See ASSUMPTIONS.md: we use PBKDF2 with 210,000 iterations since Argon2id is
 * not in Android's default JCE providers and adding BouncyCastle would expand
 * the offline footprint. This meets OWASP 2023 guidance.
 */
object PasswordHasher {
    const val ALGORITHM = "PBKDF2WithHmacSHA256"
    const val ITERATIONS = 210_000
    const val KEY_LENGTH_BITS = 256
    const val SALT_LENGTH_BYTES = 16
    const val MEMORY_KB = 0 // not applicable for PBKDF2

    private val secureRandom = SecureRandom()

    fun generateSalt(): ByteArray = ByteArray(SALT_LENGTH_BYTES).also { secureRandom.nextBytes(it) }

    fun hash(password: CharArray, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance(ALGORITHM)
        return factory.generateSecret(spec).encoded
    }

    fun verify(password: CharArray, salt: ByteArray, expected: ByteArray, iterations: Int): Boolean {
        val computed = hash(password, salt, iterations)
        return constantTimeEquals(computed, expected)
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].toInt() xor b[i].toInt())
        return result == 0
    }

    fun encodeBase64(bytes: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(bytes)

    fun decodeBase64(encoded: String): ByteArray =
        java.util.Base64.getDecoder().decode(encoded)
}
