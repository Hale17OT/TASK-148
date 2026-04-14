package com.eaglepoint.libops.exports

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature

/**
 * Verifies a signed export bundle (§9.12). Returns a structured result so
 * the caller can surface the specific failure reason in the UI.
 */
object BundleVerifier {

    sealed interface Result {
        data class Ok(val sha256: String, val manifestVersion: String) : Result
        data class InvalidManifest(val reason: String) : Result
        data object DigestMismatch : Result
        data object SignatureInvalid : Result
        data class ContentMissing(val path: String) : Result
    }

    /**
     * Verifies the bundle against any of the provided trusted public keys.
     * Returns [Result.Ok] if verification succeeds with at least one key.
     * Returns the last failure result if no key succeeds.
     */
    fun verifyWithTrustedKeys(bundleDir: File, trustedKeys: Collection<PublicKey>): Result {
        if (trustedKeys.isEmpty()) return Result.InvalidManifest("no trusted public keys configured")
        var lastResult: Result = Result.SignatureInvalid
        for (key in trustedKeys) {
            val result = verify(bundleDir, key)
            if (result is Result.Ok) return result
            lastResult = result
            // Stop early for non-signature failures (manifest/digest issues won't change with a different key)
            if (result !is Result.SignatureInvalid) return result
        }
        return lastResult
    }

    fun verify(bundleDir: File, publicKey: PublicKey): Result {
        val manifestFile = File(bundleDir, "manifest.json")
        if (!manifestFile.isFile) return Result.InvalidManifest("manifest.json missing")
        val manifest = runCatching { JSONObject(manifestFile.readText(Charsets.UTF_8)) }.getOrNull()
            ?: return Result.InvalidManifest("manifest.json not valid JSON")
        val version = (manifest.optString("manifest_version", "") ?: "").ifBlank {
            return Result.InvalidManifest("missing manifest_version")
        }
        val files = manifest.optJSONArray("content_files")
            ?: return Result.InvalidManifest("missing content_files")
        if (files.length() == 0) return Result.InvalidManifest("no content files listed")
        val firstEntry = files.getJSONObject(0)
        val path = (firstEntry.optString("path", "") ?: "").ifBlank {
            return Result.InvalidManifest("entry missing path")
        }
        val expectedSha = (firstEntry.optString("sha256", "") ?: "").ifBlank {
            return Result.InvalidManifest("entry missing sha256")
        }
        val content = File(bundleDir, path)
        if (!content.isFile) return Result.ContentMissing(path)
        val bytes = content.readBytes()
        val actualSha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (actualSha != expectedSha) return Result.DigestMismatch

        val signature = manifest.optJSONObject("signature")
            ?: return Result.InvalidManifest("missing signature block")
        val algorithm = (signature.optString("algorithm", "") ?: "").ifBlank {
            return Result.InvalidManifest("missing signature.algorithm")
        }
        val sigValue = (signature.optString("value", "") ?: "").ifBlank {
            return Result.InvalidManifest("missing signature.value")
        }
        val decoded = runCatching {
            android.util.Base64.decode(sigValue, android.util.Base64.NO_WRAP)
        }.getOrNull() ?: return Result.InvalidManifest("signature.value not base64")

        val verifier = Signature.getInstance(algorithm).apply {
            initVerify(publicKey); update(bytes)
        }
        return if (verifier.verify(decoded)) Result.Ok(actualSha, version) else Result.SignatureInvalid
    }
}
