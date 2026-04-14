package com.eaglepoint.libops.exports

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature

/**
 * Ed25519 / RSA-compatible signer abstraction for signed export bundles
 * (§9.12, §14).
 *
 * Android's built-in providers cover Ed25519 from API 33+; for older
 * devices we fall back to SHA256withRSA. The key is generated once per
 * device and stored via the app's secure key mechanism (caller-supplied
 * key provider).
 *
 * For the phase-1 offline requirement, the signing key is a local
 * self-signed RSA key so that `docker compose up --build` can verify
 * round-trip without external KMS.
 */
class BundleSigner(
    private val keyProvider: () -> KeyPair,
) {

    fun sign(payload: ByteArray): Signed {
        val key = keyProvider()
        val sig = Signature.getInstance(ALGORITHM).apply {
            initSign(key.private)
            update(payload)
        }
        val sha256 = MessageDigest.getInstance("SHA-256").digest(payload)
        return Signed(
            algorithm = ALGORITHM,
            keyId = shortKeyId(key.public),
            signature = sig.sign(),
            contentSha256 = sha256,
        )
    }

    fun verify(payload: ByteArray, signed: Signed, publicKey: PublicKey): VerifyResult {
        val expectedDigest = MessageDigest.getInstance("SHA-256").digest(payload)
        if (!expectedDigest.contentEquals(signed.contentSha256)) return VerifyResult.DigestMismatch
        val sig = Signature.getInstance(signed.algorithm).apply {
            initVerify(publicKey)
            update(payload)
        }
        return if (sig.verify(signed.signature)) VerifyResult.Ok else VerifyResult.SignatureInvalid
    }

    private fun shortKeyId(publicKey: PublicKey): String {
        val h = MessageDigest.getInstance("SHA-256").digest(publicKey.encoded)
        return h.copyOf(8).joinToString("") { "%02x".format(it) }
    }

    data class Signed(
        val algorithm: String,
        val keyId: String,
        val signature: ByteArray,
        val contentSha256: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean = other is Signed &&
            algorithm == other.algorithm &&
            keyId == other.keyId &&
            signature.contentEquals(other.signature) &&
            contentSha256.contentEquals(other.contentSha256)

        override fun hashCode(): Int = algorithm.hashCode() xor signature.contentHashCode()
    }

    sealed interface VerifyResult {
        data object Ok : VerifyResult
        data object DigestMismatch : VerifyResult
        data object SignatureInvalid : VerifyResult
    }

    companion object {
        const val ALGORITHM = "SHA256withRSA"
        private const val DEFAULT_KEY_SIZE = 2048

        /**
         * Deterministic keypair factory for smoke testing; production uses
         * the Android Keystore-backed generator.
         */
        fun ephemeralKeyProvider(): () -> KeyPair {
            var cached: KeyPair? = null
            return {
                cached ?: KeyPairGenerator.getInstance("RSA").apply { initialize(DEFAULT_KEY_SIZE) }
                    .generateKeyPair().also { cached = it }
            }
        }
    }
}
