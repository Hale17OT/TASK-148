package com.eaglepoint.libops.tests

import com.eaglepoint.libops.security.SigningKeyStore
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec

/**
 * Contract tests for [SigningKeyStore] — verifies keypair lifecycle, version
 * tracking, rotation, and trusted-key management via a pure-JVM in-memory
 * implementation (§9.12, §16).
 *
 * The real [EncryptedSigningKeyStore] requires Android Keystore and is
 * exercised in instrumented tests. This suite validates the interface
 * contract independent of the persistence layer.
 */
class SigningKeyStoreContractTest {

    private lateinit var store: InMemorySigningKeyStore

    @Before
    fun setUp() {
        store = InMemorySigningKeyStore()
    }

    // ── keypair creation ──────────────────────────────────────────────────────

    @Test
    fun first_call_creates_and_returns_keypair() {
        val kp = store.getOrCreateSigningKeyPair()
        assertThat(kp.public).isNotNull()
        assertThat(kp.private).isNotNull()
        assertThat(kp.public.algorithm).isEqualTo("RSA")
    }

    @Test
    fun subsequent_calls_return_same_keypair() {
        val first = store.getOrCreateSigningKeyPair()
        val second = store.getOrCreateSigningKeyPair()
        assertThat(second.public.encoded).isEqualTo(first.public.encoded)
    }

    @Test
    fun initial_version_is_one() {
        store.getOrCreateSigningKeyPair()
        assertThat(store.currentKeyVersion()).isEqualTo(1)
    }

    // ── rotation ──────────────────────────────────────────────────────────────

    @Test
    fun rotate_increments_version() {
        store.getOrCreateSigningKeyPair()
        val v2 = store.rotateSigningKey()
        assertThat(v2).isEqualTo(2)
        assertThat(store.currentKeyVersion()).isEqualTo(2)
    }

    @Test
    fun rotate_generates_different_keypair() {
        val first = store.getOrCreateSigningKeyPair()
        store.rotateSigningKey()
        val second = store.getOrCreateSigningKeyPair()
        assertThat(second.public.encoded).isNotEqualTo(first.public.encoded)
    }

    // ── trusted key management ────────────────────────────────────────────────

    @Test
    fun add_trusted_key_appears_in_map() {
        store.getOrCreateSigningKeyPair()
        val externalKey = generateRsaPublicKey()
        store.addTrustedPublicKey("partner_1", externalKey)

        val trusted = store.trustedPublicKeys()
        assertThat(trusted).containsKey("partner_1")
        assertThat(trusted["partner_1"]!!.encoded).isEqualTo(externalKey.encoded)
    }

    @Test
    fun remove_trusted_key_removes_it() {
        store.getOrCreateSigningKeyPair()
        store.addTrustedPublicKey("temp", generateRsaPublicKey())
        store.removeTrustedPublicKey("temp")
        assertThat(store.trustedPublicKeys()).doesNotContainKey("temp")
    }

    @Test
    fun trusted_keys_includes_own_device_key() {
        store.getOrCreateSigningKeyPair()
        val trusted = store.trustedPublicKeys()
        assertThat(trusted.keys.any { it.startsWith("device_v") }).isTrue()
    }

    // ── keyProvider ───────────────────────────────────────────────────────────

    @Test
    fun key_provider_returns_current_keypair() {
        store.getOrCreateSigningKeyPair()
        val provider = store.keyProvider()
        val kp = provider()
        assertThat(kp.public.encoded).isEqualTo(store.getOrCreateSigningKeyPair().public.encoded)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun generateRsaPublicKey(): PublicKey {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        return kpg.generateKeyPair().public
    }
}

/**
 * Pure-JVM in-memory implementation of [SigningKeyStore] that mirrors the
 * contract of [EncryptedSigningKeyStore] without Android Keystore dependencies.
 */
class InMemorySigningKeyStore : SigningKeyStore {
    private var currentPair: KeyPair? = null
    private var version: Int = 0
    private val trusted = mutableMapOf<String, PublicKey>()

    override fun getOrCreateSigningKeyPair(): KeyPair {
        if (currentPair == null) {
            currentPair = generatePair()
            version = 1
        }
        return currentPair!!
    }

    override fun currentKeyVersion(): Int = version

    override fun rotateSigningKey(): Int {
        currentPair = generatePair()
        version++
        return version
    }

    override fun addTrustedPublicKey(keyId: String, publicKey: PublicKey) {
        trusted[keyId] = publicKey
    }

    override fun removeTrustedPublicKey(keyId: String) {
        trusted.remove(keyId)
    }

    override fun trustedPublicKeys(): Map<String, PublicKey> {
        val result = mutableMapOf<String, PublicKey>()
        currentPair?.let { result["device_v$version"] = it.public }
        result.putAll(trusted)
        return result
    }

    override fun keyProvider(): () -> KeyPair = { getOrCreateSigningKeyPair() }

    private fun generatePair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        return kpg.generateKeyPair()
    }
}
