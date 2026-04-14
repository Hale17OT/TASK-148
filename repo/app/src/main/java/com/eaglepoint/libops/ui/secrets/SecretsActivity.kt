package com.eaglepoint.libops.ui.secrets

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.CountDownTimer
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.eaglepoint.libops.LibOpsApp
import com.eaglepoint.libops.R
import com.eaglepoint.libops.audit.AuditLogger
import com.eaglepoint.libops.databinding.ActivityFeatureBinding
import com.eaglepoint.libops.domain.auth.Authorizer
import com.eaglepoint.libops.domain.auth.Capabilities
import com.eaglepoint.libops.observability.QueryTimer
import com.eaglepoint.libops.ui.AuthorizationGate
import com.eaglepoint.libops.ui.FeatureScreenHelper
import com.eaglepoint.libops.ui.TwoLineRow
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

/**
 * Secrets list. Reveal path enforces:
 *
 * - capability `secrets.read_full`
 * - step-up re-authentication (password) per §9.2
 * - plaintext shown only inside an auto-clearing dialog — NEVER in a toast
 *   or logcat (audit finding #3)
 * - timed auto-dismiss (12s) + clipboard auto-clear (30s)
 */
class SecretsActivity : FragmentActivity() {

    private lateinit var binding: ActivityFeatureBinding
    private lateinit var helper: FeatureScreenHelper
    private var canReadFull = false
    private var canManage = false
    private var userId: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = AuthorizationGate.requireAccess(this, Capabilities.SECRETS_READ_MASKED) ?: return
        binding = ActivityFeatureBinding.inflate(layoutInflater)
        setContentView(binding.root)
        helper = FeatureScreenHelper(this, binding)

        val authz = Authorizer(session.capabilities)
        canReadFull = authz.has(Capabilities.SECRETS_READ_FULL)
        canManage = authz.has(Capabilities.SECRETS_MANAGE)
        userId = session.userId

        helper.setup(
            eyebrow = "Security",
            title = "Secrets",
            subtitle = "Encrypted at rest (AES-GCM). Reveal requires password step-up.",
            fabLabel = if (canManage) "Add demo secret" else null,
            onRowClick = { row -> promptReveal(row.primary) },
            onFabClick = { addDemoSecret() },
        )

        lifecycleScope.launch { refresh() }
    }

    override fun onResume() {
        super.onResume()
        if (::helper.isInitialized) lifecycleScope.launch { refresh() }
    }

    private suspend fun refresh() {
        val app = application as LibOpsApp
        val queryTimer = QueryTimer(app.observabilityPipeline)
        val list = queryTimer.timed("query", "secretRepository.listMasked") { app.secretRepository.listMasked() }
        val rows = list.map {
            TwoLineRow(
                id = "s-${it.id}",
                primary = it.alias,
                secondary = "${it.category} • ${it.masked}",
                chipLabel = it.category,
            )
        }
        helper.submit(
            rows,
            emptyTitle = "No secrets stored",
            emptyBody = "Use \u201CAdd demo secret\u201D to encrypt one for this device.",
        )
    }

    private fun addDemoSecret() {
        if (AuthorizationGate.revalidateSession(this, Capabilities.SECRETS_MANAGE) == null) return
        val app = application as LibOpsApp
        lifecycleScope.launch {
            app.secretRepository.upsert(
                alias = "demo.token.${System.currentTimeMillis() % 10_000}",
                plaintext = "sk_live_${System.currentTimeMillis()}_example_4242",
                category = "api_token",
                creatorUserId = userId,
            )
            app.auditLogger.record(
                "secret.created",
                "secret",
                userId = userId,
                reason = "ui_add_demo",
            )
            refresh()
            Snackbar.make(binding.root, "Secret encrypted and stored", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun promptReveal(alias: String) {
        if (!canReadFull) {
            Snackbar.make(binding.root, "secrets.read_full required", Snackbar.LENGTH_SHORT).show()
            return
        }
        val dialogView = layoutInflater.inflate(R.layout.dialog_stepup, null)
        val input = dialogView.findViewById<TextInputEditText>(R.id.stepupInput)
        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Verify") { d, _ ->
                val pw = input.text?.toString().orEmpty().toCharArray()
                d.dismiss()
                verifyAndReveal(alias, pw)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun verifyAndReveal(alias: String, password: CharArray) {
        if (AuthorizationGate.revalidateSession(this, Capabilities.SECRETS_MANAGE) == null) return
        val app = application as LibOpsApp
        lifecycleScope.launch {
            val ok = app.authRepository.verifyPasswordForStepUp(password)
            if (!ok) {
                Snackbar.make(binding.root, "Re-authentication failed", Snackbar.LENGTH_SHORT).show()
                return@launch
            }
            val plaintext = app.secretRepository.revealPlaintext(alias)
            if (plaintext == null) {
                Snackbar.make(binding.root, "Secret no longer exists", Snackbar.LENGTH_SHORT).show()
                return@launch
            }
            app.auditLogger.record(
                action = "secret.read_full",
                targetType = "secret",
                targetId = alias,
                userId = userId,
                reason = "ui_reveal_stepup_passed",
                severity = AuditLogger.Severity.CRITICAL,
            )
            showRevealDialog(alias, plaintext)
        }
    }

    private fun showRevealDialog(alias: String, plaintext: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_secret_reveal, null)
        val value = dialogView.findViewById<TextView>(R.id.secretValue)
        val aliasView = dialogView.findViewById<TextView>(R.id.secretAlias)
        val countdown = dialogView.findViewById<TextView>(R.id.countdownText)
        aliasView.text = "Alias: $alias"
        value.text = plaintext

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Close", null)
            .create()

        val copyButton = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.copyButton)
        copyButton.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("LibOps secret", plaintext))
            Snackbar.make(binding.root, "Copied. Clipboard will auto-clear in 30s.", Snackbar.LENGTH_SHORT).show()
            // Auto-clear clipboard
            android.os.Handler(mainLooper).postDelayed({
                val latest = cm.primaryClip?.getItemAt(0)?.text?.toString()
                if (latest == plaintext) cm.setPrimaryClip(ClipData.newPlainText("", ""))
            }, 30_000L)
        }

        // Auto-dismiss after 12 seconds so plaintext doesn't linger on screen
        val timer = object : CountDownTimer(12_000L, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                countdown.text = "Auto-close in ${(millisUntilFinished / 1_000).toInt()}s"
            }
            override fun onFinish() {
                value.text = "" // scrub before dismissing
                dialog.dismiss()
            }
        }
        dialog.setOnDismissListener { timer.cancel() }
        dialog.show()
        timer.start()
    }
}
