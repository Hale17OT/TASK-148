package com.eaglepoint.libops.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Contract for one-time bootstrap credential storage.
 * Production uses Keystore-backed encryption; tests use in-memory fake.
 */
interface BootstrapCredentialStore {
    fun store(password: String)
    fun peek(): String?
    fun consume(): String?
}

/**
 * Keystore-backed encrypted storage for the one-time bootstrap admin
 * credential. The password is persisted encrypted at rest so it survives
 * process death between seeding and the operator's first login. It is
 * removed only after explicit acknowledgement via [consume].
 */
class EncryptedBootstrapCredentialStore(context: Context) : BootstrapCredentialStore {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        PREFS_NAME,
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        context.applicationContext,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun store(password: String) {
        prefs.edit().putString(KEY, password).apply()
    }

    override fun peek(): String? = prefs.getString(KEY, null)

    override fun consume(): String? {
        val pw = prefs.getString(KEY, null)
        if (pw != null) {
            prefs.edit().remove(KEY).apply()
        }
        return pw
    }

    companion object {
        private const val PREFS_NAME = "libops_bootstrap_credential"
        private const val KEY = "bootstrap_pw"
    }
}
