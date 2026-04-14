package com.eaglepoint.libops.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.eaglepoint.libops.LibOpsApp
import com.eaglepoint.libops.auth.BiometricAuthenticator
import com.eaglepoint.libops.databinding.ActivityLoginBinding
import com.eaglepoint.libops.domain.auth.BiometricPolicy
import com.eaglepoint.libops.domain.auth.PasswordPolicy
import com.eaglepoint.libops.domain.auth.UsernamePolicy
import com.eaglepoint.libops.observability.QueryTimer
import kotlinx.coroutines.launch

class LoginActivity : FragmentActivity() {

    private lateinit var binding: ActivityLoginBinding

    private val viewModel: LoginViewModel by viewModels {
        LoginViewModelFactory(application as LibOpsApp)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.signInButton.setOnClickListener { submit() }
        binding.biometricButton.setOnClickListener { tryBiometric() }

        showBiometricIfEligible()
        observeUsernameChanges()
        observeBootstrapCredential()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    private fun render(state: LoginViewModel.UiState) {
        when (state) {
            LoginViewModel.UiState.Idle -> {
                binding.signInButton.isEnabled = true
                binding.statusText.text = ""
            }
            LoginViewModel.UiState.Submitting -> {
                binding.signInButton.isEnabled = false
                binding.statusText.text = "Signing in…"
            }
            is LoginViewModel.UiState.Locked -> {
                binding.signInButton.isEnabled = true
                binding.statusText.text = "Account locked. Try again in ${state.minutesRemaining} min."
            }
            is LoginViewModel.UiState.Error -> {
                binding.signInButton.isEnabled = true
                binding.statusText.text = state.message
            }
            is LoginViewModel.UiState.Authenticated -> {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }
    }

    private fun submit() {
        val username = binding.usernameInput.text?.toString().orEmpty().trim()
        val password = binding.passwordInput.text?.toString().orEmpty()

        val errs = (UsernamePolicy.validate(username) + PasswordPolicy.validate(password))
        if (errs.isNotEmpty()) {
            binding.statusText.text = errs.joinToString("\n") { it.message }
            return
        }
        viewModel.login(username, password.toCharArray())
        binding.passwordInput.setText("")
    }

    private val biometricDebounceHandler = Handler(Looper.getMainLooper())
    private var biometricDebounceRunnable: Runnable? = null

    private fun observeUsernameChanges() {
        binding.usernameInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                biometricDebounceRunnable?.let { biometricDebounceHandler.removeCallbacks(it) }
                biometricDebounceRunnable = Runnable { showBiometricIfEligible() }
                biometricDebounceHandler.postDelayed(biometricDebounceRunnable!!, 300)
            }
        })
    }

    private fun showBiometricIfEligible() {
        val app = application as LibOpsApp
        val authenticator = app.biometricAuthenticator
        lifecycleScope.launch {
            val username = binding.usernameInput.text?.toString().orEmpty().trim()
            val shouldShow = username.isNotEmpty() &&
                authenticator.canAuthenticate(this@LoginActivity) == BiometricAuthenticator.Availability.Ready &&
                isBiometricEligible(username)
            binding.biometricButton.visibility = if (shouldShow) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    private suspend fun isBiometricEligible(username: String): Boolean {
        val app = application as LibOpsApp
        val queryTimer = QueryTimer(app.observabilityPipeline)
        val user = queryTimer.timed("query", "userDao.findByUsername") { app.db.userDao().findByUsername(username.lowercase()) } ?: return false
        val state = BiometricPolicy.State(
            userEnabled = user.biometricEnabled,
            hasEverLoggedIn = user.lastPasswordLoginEpochMillis != null,
            lastPasswordLoginEpochMillis = user.lastPasswordLoginEpochMillis,
            lastPasswordChangeEpochMillis = user.lastPasswordChangeEpochMillis,
            privilegedRoleChangedEpochMillis = null,
            adminForcedLogoutEpochMillis = null,
        )
        return BiometricPolicy.isEligible(state, System.currentTimeMillis())
    }

    /**
     * Observes the bootstrap password flow from [LibOpsApp]. Because seeding
     * is async, the password may arrive before or after this screen opens.
     * The flow-based approach handles both cases: if the password is already
     * available it fires immediately, if seeding is still running it fires
     * once the value is set.
     */
    private var bootstrapDialogShown = false

    private fun observeBootstrapCredential() {
        val app = application as LibOpsApp
        lifecycleScope.launch {
            app.bootstrapPasswordFlow.collect { password ->
                if (password != null && !bootstrapDialogShown) {
                    // Re-read from encrypted store to guard against stale flow
                    val verified = app.bootstrapStore.peek()
                    if (verified != null) {
                        bootstrapDialogShown = true
                        showBootstrapDialog(verified, app)
                    }
                }
            }
        }
    }

    private fun showBootstrapDialog(password: String, app: LibOpsApp) {
        binding.usernameInput.setText("admin")
        AlertDialog.Builder(this)
            .setTitle("First-run setup")
            .setMessage(
                "A default administrator account has been created.\n\n" +
                "Username: admin\n" +
                "Password: $password\n\n" +
                "You must change this password immediately after signing in. " +
                "This credential will not be shown again."
            )
            .setCancelable(false)
            .setPositiveButton("I have noted this") { _, _ ->
                // Remove from both encrypted storage and in-memory flow
                // so the dialog cannot reappear on activity recreation.
                app.acknowledgeBootstrapCredential()
            }
            .show()
    }

    private fun tryBiometric() {
        val username = binding.usernameInput.text?.toString().orEmpty().trim()
        if (username.isEmpty()) {
            binding.statusText.text = "Enter your username before using biometric unlock."
            return
        }
        val app = application as LibOpsApp
        app.biometricAuthenticator.prompt(
            this,
            title = "Unlock LibOps",
            subtitle = "Use your biometric to continue",
        ) { outcome ->
            if (outcome is BiometricAuthenticator.Outcome.Success) {
                viewModel.loginViaBiometric(username)
            }
        }
    }
}
