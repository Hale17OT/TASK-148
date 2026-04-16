package com.eaglepoint.libops

import android.app.Application
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.auth.AuthRepository
import com.eaglepoint.libops.auth.BiometricAuthenticator
import com.eaglepoint.libops.auth.SeedData
import com.eaglepoint.libops.auth.SessionStore
import com.eaglepoint.libops.data.db.FieldDefinitionSeeder
import com.eaglepoint.libops.data.db.LibOpsDatabase
import com.eaglepoint.libops.orchestration.CollectionRunWorker
import com.eaglepoint.libops.observability.ObservabilityPipeline
import com.eaglepoint.libops.observability.OverdueSweeper
import com.eaglepoint.libops.orchestration.JobScheduler
import com.eaglepoint.libops.security.BootstrapCredentialStore
import com.eaglepoint.libops.security.EncryptedBootstrapCredentialStore
import com.eaglepoint.libops.security.EncryptedSigningKeyStore
import com.eaglepoint.libops.security.SecretCipher
import com.eaglepoint.libops.security.SecretRepository
import com.eaglepoint.libops.security.SigningKeyStore
import com.eaglepoint.libops.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Application-level service locator. Services are constructed lazily so
 * Robolectric / unit tests that don't exercise the UI don't pay the cost.
 */
class LibOpsApp : Application(), Configuration.Provider {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: LibOpsDatabase by lazy { LibOpsDatabase.get(this) }

    val settings: AppSettings by lazy { AppSettings.get(this) }

    val sessionStore: SessionStore by lazy { SessionStore() }

    val auditLogger: AuditLogger by lazy { AuditLogger(db.auditDao()) }

    val secretCipher: SecretCipher by lazy { SecretCipher() }

    val secretRepository: SecretRepository by lazy {
        SecretRepository(db.secretDao(), secretCipher)
    }

    val authRepository: AuthRepository by lazy {
        AuthRepository(
            userDao = db.userDao(),
            permissionDao = db.permissionDao(),
            sessionDao = db.sessionDao(),
            sessionStore = sessionStore,
            audit = auditLogger,
            observability = observabilityPipeline,
        )
    }

    val biometricAuthenticator: BiometricAuthenticator by lazy { BiometricAuthenticator() }

    val observabilityPipeline: ObservabilityPipeline by lazy {
        ObservabilityPipeline(
            auditDao = db.auditDao(),
            alertDao = db.alertDao(),
            jobDao = db.jobDao(),
            auditLogger = auditLogger,
            userDao = db.userDao(),
            qualityScoreDao = db.qualityScoreDao(),
            duplicateDao = db.duplicateDao(),
            importDao = db.importDao(),
        )
    }

    val overdueSweeper: OverdueSweeper by lazy {
        OverdueSweeper(db.alertDao(), auditLogger, observability = observabilityPipeline)
    }

    val jobScheduler: JobScheduler by lazy {
        JobScheduler(db.jobDao(), db.collectionSourceDao(), auditLogger, observabilityPipeline)
    }

    val seedData: SeedData by lazy {
        SeedData(
            userDao = db.userDao(),
            permissionDao = db.permissionDao(),
            audit = auditLogger,
            // In debug builds the bootstrap password is fixed so reviewers always
            // have a known admin credential without catching the one-time dialog.
            // Release builds keep the per-install SecureRandom generation.
            bootstrapPasswordOverride = if (BuildConfig.DEBUG) REVIEW_ADMIN_PASSWORD else null,
        )
    }

    val signingKeyStore: SigningKeyStore by lazy {
        EncryptedSigningKeyStore(this)
    }

    val bootstrapStore: BootstrapCredentialStore by lazy {
        EncryptedBootstrapCredentialStore(this)
    }

    /**
     * Observable bootstrap password for first-run display. Emits the
     * password from Keystore-backed encrypted storage once seeding
     * completes. Survives process death — cleared only after explicit
     * acknowledgement via [acknowledgeBootstrapCredential].
     */
    private val _bootstrapPassword = MutableStateFlow<String?>(null)
    val bootstrapPasswordFlow: StateFlow<String?> = _bootstrapPassword.asStateFlow()

    /**
     * Called by LoginActivity after the operator acknowledges the credential.
     * Removes from both encrypted storage and the in-memory flow so the
     * dialog cannot reappear on activity recreation.
     */
    fun acknowledgeBootstrapCredential() {
        bootstrapStore.consume()
        _bootstrapPassword.value = null
    }

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            seedData.ensureSeeded()
            FieldDefinitionSeeder.ensureSeeded(db.fieldDefinitionDao())
            // On first run, persist the bootstrap password in encrypted storage
            // so it survives process death until the operator acknowledges it.
            seedData.consumeBootstrapPassword()?.let { password ->
                bootstrapStore.store(password)
            }
            // Emit whatever is in encrypted storage (first run or recovery)
            _bootstrapPassword.value = bootstrapStore.peek()
            // Restore a prior authenticated session from DB if still valid.
            // This enforces idle-timeout on the restore path (§9.2) so
            // expired sessions cannot be silently reused after process death.
            authRepository.restoreSession()
            scheduleOrchestratorTick()
        }
    }

    private fun scheduleOrchestratorTick() {
        val request = PeriodicWorkRequestBuilder<CollectionRunWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork(
                CollectionRunWorker.UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .setExecutor(java.util.concurrent.Executors.newFixedThreadPool(settings.current().parallelism))
            .build()

    companion object {
        /**
         * Fixed bootstrap password used only in debug builds so reviewers always
         * have a known admin credential. Release builds use a per-install
         * SecureRandom password displayed once at first launch.
         *
         * Satisfies PasswordPolicy: uppercase, lowercase, digit, special char, ≥12 chars.
         */
        const val REVIEW_ADMIN_PASSWORD = "Admin@Review2024!"
    }
}
