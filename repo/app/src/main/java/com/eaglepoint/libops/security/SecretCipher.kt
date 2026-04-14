package com.eaglepoint.libops.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM encryption backed by the Android Keystore (§14).
 *
 * Keys never leave the secure hardware where available. We use
 * AES/GCM/NoPadding with a 128-bit authentication tag and 12-byte IV.
 */
class SecretCipher(private val keyAlias: String = DEFAULT_ALIAS) {

    fun encrypt(plaintext: String): Ciphertext {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val iv = cipher.iv
        val body = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Ciphertext(body.encodeBase64(), iv.encodeBase64())
    }

    fun decrypt(ciphertextBase64: String, ivBase64: String): String {
        val key = getOrCreateKey()
        val iv = ivBase64.decodeBase64()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        }
        val body = cipher.doFinal(ciphertextBase64.decodeBase64())
        return String(body, Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = ks.getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    data class Ciphertext(val cipherTextBase64: String, val ivBase64: String)

    companion object {
        const val DEFAULT_ALIAS = "libops.secrets.masterkey.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_SIZE_BITS = 256
        const val TAG_LENGTH_BITS = 128
    }
}

private fun ByteArray.encodeBase64(): String =
    android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)

private fun String.decodeBase64(): ByteArray =
    android.util.Base64.decode(this, android.util.Base64.NO_WRAP)
