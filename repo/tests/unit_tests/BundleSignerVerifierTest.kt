package com.eaglepoint.libops.tests

import android.util.Base64
import com.eaglepoint.libops.exports.BundleSigner
import com.eaglepoint.libops.exports.BundleVerifier
import com.google.common.truth.Truth.assertThat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

/**
 * Tests for [BundleSigner] (sign/verify contract) and [BundleVerifier]
 * (bundle directory verification). Uses Robolectric so that
 * [android.util.Base64] is available for constructing valid bundle fixtures.
 *
 * Covers §9.12 / §14: signed export bundles, digest + signature validation,
 * and multi-key trusted-key set resolution.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BundleSignerVerifierTest {

    @get:Rule
    val tmpDir = TemporaryFolder()

    // ── BundleSigner ──────────────────────────────────────────────────────────

    @Test
    fun sign_and_verify_roundtrip_succeeds() {
        val keyProvider = BundleSigner.ephemeralKeyProvider()
        val signer = BundleSigner(keyProvider)
        val payload = "hello world".toByteArray()

        val signed = signer.sign(payload)
        val result = signer.verify(payload, signed, keyProvider().public)

        assertThat(result).isEqualTo(BundleSigner.VerifyResult.Ok)
    }

    @Test
    fun verify_detects_tampered_payload_as_digest_mismatch() {
        val keyProvider = BundleSigner.ephemeralKeyProvider()
        val signer = BundleSigner(keyProvider)
        val signed = signer.sign("original".toByteArray())

        val result = signer.verify("tampered!".toByteArray(), signed, keyProvider().public)

        assertThat(result).isEqualTo(BundleSigner.VerifyResult.DigestMismatch)
    }

    @Test
    fun verify_detects_wrong_public_key_as_signature_invalid() {
        val keyA = BundleSigner.ephemeralKeyProvider()
        val keyB = BundleSigner.ephemeralKeyProvider() // independent key pair
        val signer = BundleSigner(keyA)
        val signed = signer.sign("hello".toByteArray())

        val result = signer.verify("hello".toByteArray(), signed, keyB().public)

        assertThat(result).isEqualTo(BundleSigner.VerifyResult.SignatureInvalid)
    }

    @Test
    fun sign_produces_deterministic_key_id_for_same_keypair() {
        val keyProvider = BundleSigner.ephemeralKeyProvider()
        val signer = BundleSigner(keyProvider)

        val s1 = signer.sign("payload_a".toByteArray())
        val s2 = signer.sign("payload_b".toByteArray())

        assertThat(s1.keyId).isEqualTo(s2.keyId)
        assertThat(s1.keyId).hasLength(16) // 8-byte SHA-256 prefix → 16 hex chars
    }

    // ── BundleVerifier ────────────────────────────────────────────────────────

    @Test
    fun verifier_returns_invalid_manifest_when_manifest_file_missing() {
        val dir = tmpDir.newFolder("empty_bundle")
        val result = BundleVerifier.verify(dir, BundleSigner.ephemeralKeyProvider()().public)
        assertThat(result).isInstanceOf(BundleVerifier.Result.InvalidManifest::class.java)
    }

    @Test
    fun verifier_returns_invalid_manifest_for_malformed_json() {
        val dir = tmpDir.newFolder("bad_json")
        File(dir, "manifest.json").writeText("not valid json {{{")
        val result = BundleVerifier.verify(dir, BundleSigner.ephemeralKeyProvider()().public)
        assertThat(result).isInstanceOf(BundleVerifier.Result.InvalidManifest::class.java)
    }

    @Test
    fun verifier_returns_content_missing_when_content_file_absent() {
        val dir = tmpDir.newFolder("missing_content")
        File(dir, "manifest.json").writeText(
            buildManifest("content.json", "abc123", "dGVzdA==").toString()
        )
        val result = BundleVerifier.verify(dir, BundleSigner.ephemeralKeyProvider()().public)
        assertThat(result).isInstanceOf(BundleVerifier.Result.ContentMissing::class.java)
        assertThat((result as BundleVerifier.Result.ContentMissing).path).isEqualTo("content.json")
    }

    @Test
    fun verifier_returns_digest_mismatch_on_tampered_content() {
        val dir = tmpDir.newFolder("tampered")
        val originalBytes = "original content".toByteArray()
        val sha = sha256Hex(originalBytes)

        // Write different bytes — digest will not match
        File(dir, "content.json").writeText("tampered content here")
        File(dir, "manifest.json").writeText(buildManifest("content.json", sha, "dGVzdA==").toString())

        val result = BundleVerifier.verify(dir, BundleSigner.ephemeralKeyProvider()().public)
        assertThat(result).isEqualTo(BundleVerifier.Result.DigestMismatch)
    }

    @Test
    fun verifier_returns_ok_for_fully_valid_signed_bundle() {
        val dir = tmpDir.newFolder("valid_bundle")
        val keyProvider = BundleSigner.ephemeralKeyProvider()
        val signer = BundleSigner(keyProvider)

        val contentBytes = """{"records":[]}""".toByteArray()
        File(dir, "content.json").writeBytes(contentBytes)
        val sha = sha256Hex(contentBytes)

        val signed = signer.sign(contentBytes)
        val sigB64 = Base64.encodeToString(signed.signature, Base64.NO_WRAP)

        File(dir, "manifest.json").writeText(
            buildManifest("content.json", sha, sigB64, signed.algorithm).toString()
        )

        val result = BundleVerifier.verify(dir, keyProvider().public)
        assertThat(result).isInstanceOf(BundleVerifier.Result.Ok::class.java)
        val ok = result as BundleVerifier.Result.Ok
        assertThat(ok.sha256).isEqualTo(sha)
        assertThat(ok.manifestVersion).isEqualTo("1.0")
    }

    @Test
    fun verifyWithTrustedKeys_returns_invalid_manifest_for_empty_key_list() {
        val dir = tmpDir.newFolder("no_keys")
        val result = BundleVerifier.verifyWithTrustedKeys(dir, emptyList())
        assertThat(result).isInstanceOf(BundleVerifier.Result.InvalidManifest::class.java)
    }

    @Test
    fun verifyWithTrustedKeys_succeeds_when_second_of_two_keys_matches() {
        val dir = tmpDir.newFolder("multi_key")
        val keyA = BundleSigner.ephemeralKeyProvider() // signs the bundle
        val keyB = BundleSigner.ephemeralKeyProvider() // wrong key — tried first
        val signer = BundleSigner(keyA)

        val contentBytes = """{"records":[]}""".toByteArray()
        File(dir, "content.json").writeBytes(contentBytes)
        val sha = sha256Hex(contentBytes)

        val signed = signer.sign(contentBytes)
        val sigB64 = Base64.encodeToString(signed.signature, Base64.NO_WRAP)
        File(dir, "manifest.json").writeText(
            buildManifest("content.json", sha, sigB64, signed.algorithm).toString()
        )

        // keyB tried first (returns SignatureInvalid), then keyA succeeds
        val result = BundleVerifier.verifyWithTrustedKeys(dir, listOf(keyB().public, keyA().public))
        assertThat(result).isInstanceOf(BundleVerifier.Result.Ok::class.java)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildManifest(
        path: String,
        sha: String,
        sigValue: String,
        algorithm: String = BundleSigner.ALGORITHM,
    ) = JSONObject()
        .put("manifest_version", "1.0")
        .put(
            "content_files",
            JSONArray().put(JSONObject().put("path", path).put("sha256", sha)),
        )
        .put(
            "signature",
            JSONObject().put("algorithm", algorithm).put("value", sigValue),
        )

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
