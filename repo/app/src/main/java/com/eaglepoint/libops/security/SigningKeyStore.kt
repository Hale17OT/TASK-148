package com.eaglepoint.libops.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Persistent signing-key store for export/import bundles (§9.12, §14).
 *
 * Keys are stored in EncryptedSharedPreferences backed by the Android
 * Keystore master key, so they survive process death and app restarts.
 * This replaces the session-ephemeral keypair that previously made
 * cross-session/device bundle verification brittle.
 *
 * Supports:
 * - Stable device signing keypair (generated once, persisted encrypted)
 * - Key versioning for rotation (admin-initiated)
 * - Trusted public-key registry for verifying imports from other devices
 */
interface SigningKeyStore {
    /** Returns the current signing keypair, generating one if none exists. */
    fun getOrCreateSigningKeyPair(): KeyPair

    /** Current key version number. Incremented on rotation. */
    fun currentKeyVersion(): Int

    /** Rotates the signing keypair; returns the new key version. */
    fun rotateSigningKey(): Int

    /** Registers an external public key as trusted for import verification. */
    fun addTrustedPublicKey(keyId: String, publicKey: PublicKey)

    /** Removes a trusted public key by its ID. */
    fun removeTrustedPublicKey(keyId: String)

    /** Returns all trusted public keys (including this device's own). */
    fun trustedPublicKeys(): Map<String, PublicKey>

    /** Provides a key-provider lambda compatible with [BundleSigner]. */
    fun keyProvider(): () -> KeyPair
}

class EncryptedSigningKeyStore(context: Context) : SigningKeyStore {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        PREFS_NAME,
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        context.applicationContext,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    @Synchronized
    override fun getOrCreateSigningKeyPair(): KeyPair {
        val privB64 = prefs.getString(KEY_PRIVATE, null)
        val pubB64 = prefs.getString(KEY_PUBLIC, null)
        if (privB64 != null && pubB64 != null) {
            val kf = KeyFactory.getInstance(KEY_ALGORITHM)
            val priv = kf.generatePrivate(PKCS8EncodedKeySpec(decodeBase64(privB64)))
            val pub = kf.generatePublic(X509EncodedKeySpec(decodeBase64(pubB64)))
            return KeyPair(pub, priv)
        }
        return generateAndPersist()
    }

    override fun currentKeyVersion(): Int = prefs.getInt(KEY_VERSION, 1)

    @Synchronized
    override fun rotateSigningKey(): Int {
        val newVersion = currentKeyVersion() + 1
        generateAndPersist()
        prefs.edit().putInt(KEY_VERSION, newVersion).apply()
        return newVersion
    }

    @Synchronized
    override fun addTrustedPublicKey(keyId: String, publicKey: PublicKey) {
        val encoded = encodeBase64(publicKey.encoded)
        val trustKey = "$TRUSTED_PREFIX$keyId"
        prefs.edit().putString(trustKey, encoded).apply()
    }

    @Synchronized
    override fun removeTrustedPublicKey(keyId: String) {
        prefs.edit().remove("$TRUSTED_PREFIX$keyId").apply()
    }

    override fun trustedPublicKeys(): Map<String, PublicKey> {
        val kf = KeyFactory.getInstance(KEY_ALGORITHM)
        val result = mutableMapOf<String, PublicKey>()

        // Include device's own public key
        val ownPub = prefs.getString(KEY_PUBLIC, null)
        if (ownPub != null) {
            val pub = kf.generatePublic(X509EncodedKeySpec(decodeBase64(ownPub)))
            result["device_v${currentKeyVersion()}"] = pub
        }

        // Include externally trusted keys
        for ((key, value) in prefs.all) {
            if (key.startsWith(TRUSTED_PREFIX) && value is String) {
                val keyId = key.removePrefix(TRUSTED_PREFIX)
                val pub = kf.generatePublic(X509EncodedKeySpec(decodeBase64(value)))
                result[keyId] = pub
            }
        }
        return result
    }

    override fun keyProvider(): () -> KeyPair = { getOrCreateSigningKeyPair() }

    private fun generateAndPersist(): KeyPair {
        val kpg = KeyPairGenerator.getInstance(KEY_ALGORITHM)
        kpg.initialize(KEY_SIZE)
        val pair = kpg.generateKeyPair()
        prefs.edit()
            .putString(KEY_PRIVATE, encodeBase64(pair.private.encoded))
            .putString(KEY_PUBLIC, encodeBase64(pair.public.encoded))
            .apply()
        if (!prefs.contains(KEY_VERSION)) {
            prefs.edit().putInt(KEY_VERSION, 1).apply()
        }
        return pair
    }

    companion object {
        private const val PREFS_NAME = "libops_signing_keys"
        private const val KEY_PRIVATE = "signing_private_key"
        private const val KEY_PUBLIC = "signing_public_key"
        private const val KEY_VERSION = "signing_key_version"
        private const val TRUSTED_PREFIX = "trusted_pub_"
        private const val KEY_ALGORITHM = "RSA"
        private const val KEY_SIZE = 2048

        private fun encodeBase64(bytes: ByteArray): String =
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

        private fun decodeBase64(s: String): ByteArray =
            android.util.Base64.decode(s, android.util.Base64.NO_WRAP)
    }
}
