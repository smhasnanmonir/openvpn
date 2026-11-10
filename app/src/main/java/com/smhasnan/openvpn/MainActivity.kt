package com.smhasnan.openvpn

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smhasnan.openvpn.ui.AppRoot
import com.smhasnan.openvpn.ui.LockScreen
import com.smhasnan.openvpn.ui.theme.OpenVpnTheme
import java.util.concurrent.Executor

/**
 * Single-activity Compose host. The fingerprint app-lock is a UI gate only; an
 * active VPN service is intentionally not stopped when the UI is locked.
 */
class MainActivity : FragmentActivity() {
    private enum class BiometricAction { UNLOCK, ENABLE_LOCK, DISABLE_LOCK }

    private val executor: Executor by lazy { ContextCompat.getMainExecutor(this) }
    private lateinit var biometricPrompt: BiometricPrompt
    private var pendingAction = BiometricAction.UNLOCK

    private var locked by mutableStateOf(false)
    private var authenticating by mutableStateOf(false)
    private var authError by mutableStateOf<String?>(null)
    private var lastStoppedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = appContainer
        locked = container.settings.settingsNow().biometricLock && !AppLockState.unlockedThisProcess
        setupBiometricPrompt()

        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle()
            OpenVpnTheme(themeMode = settings.themeMode) {
                if (locked) {
                    LaunchedEffect(locked) {
                        if (locked) requestUnlock()
                    }
                    LockScreen(
                        authenticating = authenticating,
                        error = authError,
                        unavailable = !canUseBiometrics(),
                        onUnlock = ::requestUnlock,
                        onOpenSecuritySettings = {
                            runCatching {
                                startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                            }
                        },
                    )
                } else {
                    AppRoot(onBiometricChangeRequest = ::requestBiometricChange)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val container = appContainer
        if (!container.settings.settingsNow().biometricLock) return
        if (locked) return
        val elapsed = if (lastStoppedAt == 0L) Long.MAX_VALUE else SystemClock.elapsedRealtime() - lastStoppedAt
        if (elapsed > RELOCK_AFTER_MS) {
            locked = true
            AppLockState.unlockedThisProcess = false
            authError = null
        }
    }

    override fun onStop() {
        lastStoppedAt = SystemClock.elapsedRealtime()
        super.onStop()
    }

    private fun setupBiometricPrompt() {
        biometricPrompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    authenticating = false
                    authError = null
                    handleBiometricSuccess()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    authError = "Fingerprint not recognized. Try again."
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    authenticating = false
                    when (errorCode) {
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_CANCELED -> {
                            authError = if (pendingAction == BiometricAction.UNLOCK) {
                                "Unlock cancelled."
                            } else {
                                null
                            }
                        }
                        BiometricPrompt.ERROR_LOCKOUT -> {
                            authError = "Too many attempts. Use your device unlock, then try again."
                        }
                        BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> {
                            authError = "Biometric authentication is temporarily locked. Unlock the device and try again."
                        }
                        BiometricPrompt.ERROR_NO_BIOMETRICS,
                        BiometricPrompt.ERROR_HW_NOT_PRESENT,
                        BiometricPrompt.ERROR_HW_UNAVAILABLE -> {
                            authError = "Biometric authentication is unavailable on this device."
                        }
                        else -> {
                            authError = errString.toString().take(160)
                        }
                    }
                }
            },
        )
    }

    private fun requestUnlock() {
        if (!canUseBiometrics()) {
            authError = "Set up a screen lock and enroll a fingerprint to unlock."
            return
        }
        if (authenticating) return
        pendingAction = BiometricAction.UNLOCK
        authenticate(title = "Unlock OpenVPN Client", subtitle = "Confirm it's you to manage profiles")
    }

    private fun requestBiometricChange(enable: Boolean) {
        val settings = appContainer.settings
        if (settings.settingsNow().biometricLock == enable) return
        if (enable && !canUseBiometrics()) {
            Toast.makeText(
                this,
                "Set up a strong biometric (fingerprint) in system settings first.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        if (authenticating) return
        pendingAction = if (enable) BiometricAction.ENABLE_LOCK else BiometricAction.DISABLE_LOCK
        authenticate(
            title = if (enable) "Enable app lock" else "Disable app lock",
            subtitle = "Authenticate to change the security setting",
        )
    }

    private fun authenticate(title: String, subtitle: String) {
        authenticating = true
        authError = null
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText("Cancel")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setConfirmationRequired(true)
            .build()
        try {
            biometricPrompt.authenticate(promptInfo)
        } catch (e: Exception) {
            authenticating = false
            authError = "Biometric prompt could not be shown: ${e.javaClass.simpleName}"
        }
    }

    private fun handleBiometricSuccess() {
        val settings = appContainer.settings
        when (pendingAction) {
            BiometricAction.UNLOCK -> {
                locked = false
                AppLockState.unlockedThisProcess = true
            }
            BiometricAction.ENABLE_LOCK -> {
                settings.update { it.copy(biometricLock = true) }
                Toast.makeText(this, "App lock enabled", Toast.LENGTH_SHORT).show()
            }
            BiometricAction.DISABLE_LOCK -> {
                settings.update { it.copy(biometricLock = false) }
                locked = false
                AppLockState.unlockedThisProcess = true
                Toast.makeText(this, "App lock disabled", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun canUseBiometrics(): Boolean {
        val result = BiometricManager.from(this)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        return result == BiometricManager.BIOMETRIC_SUCCESS
    }

    private companion object {
        const val RELOCK_AFTER_MS = 20_000L
    }
}

/** Survives activity recreation but not process death. */
private object AppLockState {
    @Volatile
    var unlockedThisProcess: Boolean = false
}
