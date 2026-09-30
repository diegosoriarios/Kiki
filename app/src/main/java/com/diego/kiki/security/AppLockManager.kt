package com.diego.kiki.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the app-lock state machine: locked on cold start, re-lock on resume
 * after the grace timeout, unlock on successful biometric/credential auth.
 */
class AppLockManager(private val prefs: SecurePrefs) {

    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    @Volatile
    private var lastBackgroundedAt = 0L

    /** Whether the lock should engage right now given the current tab set. */
    fun shouldLock(hasIncognitoTabs: Boolean): Boolean = when {
        !prefs.appLockEnabled -> false
        prefs.lockIncognitoOnly -> hasIncognitoTabs
        else -> true
    }

    fun evaluateColdStart(hasIncognitoTabs: Boolean) {
        lastBackgroundedAt = 0L
        _isLocked.value = shouldLock(hasIncognitoTabs)
    }

    fun onForeground(hasIncognitoTabs: Boolean) {
        if (lastBackgroundedAt == 0L) return // fresh process; cold start decided
        val elapsedSeconds = (System.currentTimeMillis() - lastBackgroundedAt) / 1000
        if (elapsedSeconds < prefs.graceTimeoutSeconds) return // within grace
        if (shouldLock(hasIncognitoTabs)) {
            _isLocked.value = true
        }
    }

    fun onBackground() {
        lastBackgroundedAt = System.currentTimeMillis()
    }

    fun unlock() {
        _isLocked.value = false
    }

    /** Launches the system biometric/credential prompt. Callback runs on main. */
    fun showPrompt(activity: FragmentActivity, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlock()
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onError(errString.toString())
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Kiki")
            .setSubtitle("Authenticate to access the browser")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        prompt.authenticate(info)
    }

    fun isBiometricAvailable(context: Context): Boolean {
        return BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }
}
