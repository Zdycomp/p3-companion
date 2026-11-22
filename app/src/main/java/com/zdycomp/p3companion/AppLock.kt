package com.zdycomp.p3companion

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.os.SystemClock

/**
 * "Lock the app" (OPTIONS-PLAN P1/P2) with the framework BiometricPrompt: fingerprint, face or the
 * phone's PIN, pattern or password. The lock is checked in onStart only, after the app was in the
 * background for the chosen time; permission dialogs, Android's pairing popup and the app's own
 * file pickers never lock it again.
 */
object AppLock {

    /** Unlocked at least once in this process (survives theme changes, which recreate the activity). */
    @Volatile var unlocked = false
        private set
    @Volatile private var backgroundAt = 0L
    @Volatile var prompting = false
        private set

    /** Set while the app sends the person to a picker or share sheet it expects to come back from. */
    @Volatile var expectingReturn = false

    fun deviceSecure(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    fun shouldLock(context: Context, prefs: Prefs): Boolean {
        if (!prefs.appLock || !deviceSecure(context)) return false
        if (!unlocked) return true
        val at = backgroundAt
        if (at == 0L) return false
        return SystemClock.elapsedRealtime() - at >= prefs.lockAfterMin * 60_000L
    }

    fun onStop(changingConfigurations: Boolean) {
        if (prompting || changingConfigurations) return
        // A picker or share sheet the app opened gets extra time, so coming back from it doesn't lock;
        // if the person never comes back, the lock still returns after the grace period.
        backgroundAt = SystemClock.elapsedRealtime() + if (expectingReturn) PICKER_GRACE_MS else 0L
    }

    private const val PICKER_GRACE_MS = 10 * 60_000L

    fun onStarted() { if (unlocked) backgroundAt = 0L }

    /** Turning the lock on from Settings counts as unlocked for this visit. */
    fun markUnlocked() { unlocked = true; backgroundAt = 0L }

    /** Shows the system prompt. [onDone] gets true on success, false when cancelled, null on other errors. */
    fun prompt(activity: Activity, onDone: (Boolean?, String?) -> Unit) {
        if (prompting) return
        prompting = true
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle("Unlock P3 Companion")
            .setSubtitle("Use your fingerprint, face or screen lock")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()
        val cb = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                prompting = false
                unlocked = true
                backgroundAt = 0L
                onDone(true, null)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                prompting = false
                val cancelled = errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED
                onDone(if (cancelled) false else null, errString?.toString())
            }
        }
        try {
            prompt.authenticate(CancellationSignal(), activity.mainExecutor, cb)
        } catch (e: Exception) {
            prompting = false
            onDone(null, e.message)
        }
    }
}
