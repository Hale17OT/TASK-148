package com.eaglepoint.libops.auth

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Wraps [BiometricPrompt] behind a small sealed callback contract so the
 * caller never touches the Android-specific API directly.
 *
 * Biometric is gated by [BiometricPolicy] for eligibility — this class only
 * runs the prompt when the policy allows.
 */
class BiometricAuthenticator {

    fun canAuthenticate(activity: FragmentActivity): Availability {
        val bm = BiometricManager.from(activity)
        val flags = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        return when (bm.canAuthenticate(flags)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Availability.Ready
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Availability.NoneEnrolled
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> Availability.NoHardware
            else -> Availability.Unavailable
        }
    }

    fun prompt(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onResult: (Outcome) -> Unit,
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onResult(Outcome.Success)
                }
                override fun onAuthenticationFailed() {
                    onResult(Outcome.Failed)
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onResult(Outcome.Error(errorCode, errString.toString()))
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.BIOMETRIC_WEAK,
            )
            .setNegativeButtonText("Cancel")
            .build()
        prompt.authenticate(info)
    }

    enum class Availability { Ready, NoneEnrolled, NoHardware, Unavailable }

    sealed interface Outcome {
        data object Success : Outcome
        data object Failed : Outcome
        data class Error(val code: Int, val message: String) : Outcome
    }
}
