package com.eaglepoint.libops.tests

import com.eaglepoint.libops.data.db.dao.SecretDao
import com.eaglepoint.libops.data.db.entity.SecretEntity
import com.eaglepoint.libops.security.SecretCipher
import com.eaglepoint.libops.security.SecretRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * Tests for [SecretRepository] covering upsert (create + update), masked
 * listing, and plaintext reveal (§14, §9.17).
 *
 * Uses [FakeSecretCipher] (identity encryption) to isolate repository logic
 * from the Android Keystore, and [FakeSecretDao] as an in-memory store.
 */
class SecretRepositoryTest {

    private lateinit var dao: FakeSecretDao
    private lateinit var repo: SecretRepository
    private var clockMs = 1_700_000_000_000L

    @Before
    fun setUp() {
        dao = FakeSecretDao()
        repo = SecretRepository(dao, FakeSecretCipher(), clock = { clockMs })
    }

    @Test
    fun upsert_creates_new_secret_when_alias_absent() = runBlocking {
        val result = repo.upsert("proxy.token", "s3cr3t!", "proxy", creatorUserId = 1L)

        assertThat(result.id).isGreaterThan(0L)
        assertThat(result.alias).isEqualTo("proxy.token")
        assertThat(result.category).isEqualTo("proxy")
        assertThat(result.createdAt).isEqualTo(clockMs)
        assertThat(result.updatedAt).isEqualTo(clockMs)
        // Plaintext must never appear verbatim in maskedPreview
        assertThat(result.maskedPreview).doesNotContain("s3cr3t!")
    }

    @Test
    fun upsert_updates_existing_row_preserving_creation_time() = runBlocking {
        val first = repo.upsert("shared.key", "initial_value", "api_token", creatorUserId = 1L)
        val createdAt = first.createdAt

        clockMs += 10_000L
        val second = repo.upsert("shared.key", "updated_value", "api_token", creatorUserId = 1L)

        assertThat(second.id).isEqualTo(first.id)           // same row updated
        assertThat(second.createdAt).isEqualTo(createdAt)   // creation time unchanged
        assertThat(second.updatedAt).isGreaterThan(createdAt) // update time advanced
    }

    @Test
    fun listMasked_returns_masked_previews_without_exposing_plaintext() = runBlocking {
        repo.upsert("key.a", "abcdefgh12345678", "proxy", creatorUserId = 1L)
        repo.upsert("key.b", "XYZ9999", "signing_key", creatorUserId = 1L)

        val masked = repo.listMasked()

        assertThat(masked).hasSize(2)
        for (m in masked) {
            assertThat(m.masked).doesNotMatch(".*[a-z][a-z][a-z][a-z].*") // no long plaintext runs
            assertThat(m.masked).contains("*")
        }
    }

    @Test
    fun listMasked_shows_last_four_chars_with_stars_prefix() = runBlocking {
        repo.upsert("mask.test", "abcde12345", "other", creatorUserId = 1L)

        val masked = repo.listMasked()

        assertThat(masked).hasSize(1)
        // "abcde12345" → "******2345"
        assertThat(masked[0].masked).isEqualTo("******2345")
    }

    @Test
    fun revealPlaintext_decrypts_and_returns_original_value() = runBlocking {
        repo.upsert("reveal.me", "plaintext123", "signing_key", creatorUserId = 1L)

        val revealed = repo.revealPlaintext("reveal.me")

        assertThat(revealed).isEqualTo("plaintext123")
    }

    @Test
    fun revealPlaintext_returns_null_for_unknown_alias() = runBlocking {
        val result = repo.revealPlaintext("nonexistent.alias")
        assertThat(result).isNull()
    }

    @Test
    fun upsert_throws_for_blank_alias() {
        var threw = false
        try {
            runBlocking { repo.upsert("", "value", "proxy", creatorUserId = 1L) }
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertThat(threw).isTrue()
    }

    @Test
    fun upsert_throws_for_empty_plaintext() {
        var threw = false
        try {
            runBlocking { repo.upsert("ok.alias", "", "proxy", creatorUserId = 1L) }
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertThat(threw).isTrue()
    }
}

/**
 * Identity cipher: returns plaintext as ciphertext, avoiding Android Keystore.
 * Only for use in tests — never in production.
 */
class FakeSecretCipher : SecretCipher("test-only-alias") {
    override fun encrypt(plaintext: String): Ciphertext = Ciphertext(plaintext, "fake-iv")
    override fun decrypt(ciphertextBase64: String, ivBase64: String): String = ciphertextBase64
}

class FakeSecretDao : SecretDao {
    private val secrets = linkedMapOf<Long, SecretEntity>()
    private val ids = AtomicLong(0)

    override suspend fun insert(secret: SecretEntity): Long {
        val id = ids.incrementAndGet()
        secrets[id] = secret.copy(id = id)
        return id
    }

    override suspend fun update(secret: SecretEntity): Int {
        if (!secrets.containsKey(secret.id)) return 0
        secrets[secret.id] = secret
        return 1
    }

    override suspend fun listAll(): List<SecretEntity> = secrets.values.sortedBy { it.alias }

    override suspend fun byAlias(alias: String): SecretEntity? =
        secrets.values.firstOrNull { it.alias == alias }

    override suspend fun delete(id: Long): Int =
        if (secrets.remove(id) != null) 1 else 0
}
