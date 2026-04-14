package com.eaglepoint.libops.security

import com.eaglepoint.libops.data.db.dao.SecretDao
import com.eaglepoint.libops.data.db.entity.SecretEntity
import com.eaglepoint.libops.domain.mask.Masking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository for encrypted secrets. Writes go through [SecretCipher] so
 * plaintext never hits the database; reads default to masked form unless
 * the caller is privileged and has passed re-authentication (§14, §9.17).
 */
class SecretRepository(
    private val dao: SecretDao,
    private val cipher: SecretCipher,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun upsert(
        alias: String,
        plaintext: String,
        category: String,
        creatorUserId: Long,
    ): SecretEntity = withContext(Dispatchers.IO) {
        require(alias.isNotBlank()) { "alias required" }
        require(plaintext.isNotEmpty()) { "plaintext required" }

        val ct = cipher.encrypt(plaintext)
        val now = clock()
        val existing = dao.byAlias(alias)
        val masked = Masking.mask(plaintext)

        val record = SecretEntity(
            id = existing?.id ?: 0,
            alias = alias,
            cipherText = ct.cipherTextBase64,
            iv = ct.ivBase64,
            maskedPreview = masked,
            category = category,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            createdByUserId = creatorUserId,
        )
        if (existing == null) {
            val id = dao.insert(record)
            record.copy(id = id)
        } else {
            dao.update(record)
            record
        }
    }

    suspend fun listMasked(): List<MaskedSecret> = withContext(Dispatchers.IO) {
        dao.listAll().map {
            MaskedSecret(id = it.id, alias = it.alias, masked = it.maskedPreview, category = it.category)
        }
    }

    /** Requires [secrets.read_full] + re-authentication upstream. */
    suspend fun revealPlaintext(alias: String): String? = withContext(Dispatchers.IO) {
        val row = dao.byAlias(alias) ?: return@withContext null
        cipher.decrypt(row.cipherText, row.iv)
    }

    data class MaskedSecret(val id: Long, val alias: String, val masked: String, val category: String)
}
