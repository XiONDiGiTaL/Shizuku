package moe.shizuku.manager.xion.security

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent

/**
 * Optional app lock: the manager asks for the device's own screen lock before it will show the
 * service controls.
 *
 * The device credential is deliberate rather than a private PIN, Android already stores and rate
 * limits it, biometrics are handled by the keyguard, and the app never sees the secret. The idea of
 * a master lock guarding Shizuku's privileged actions comes from Shevery by HmnDev-Tech
 * (https://github.com/HmnDev-Tech/shevery, Apache-2.0). This is a smaller version: one switch for the
 * whole manager, not per-action protection.
 */
object AppLock {

    private const val PREFS = "xion_app_lock"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_UNLOCK = "last_unlock"

    /** How long the app may stay unlocked after leaving it. */
    private const val GRACE_MS = 60_000L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) markUnlocked(context)
    }

    fun markUnlocked(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_UNLOCK, System.currentTimeMillis()).apply()
    }

    fun needsUnlock(context: Context): Boolean {
        if (!isEnabled(context)) return false
        val last = prefs(context).getLong(KEY_LAST_UNLOCK, 0L)
        return System.currentTimeMillis() - last > GRACE_MS
    }

    fun isDeviceSecure(context: Context): Boolean {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return false
        return keyguard.isDeviceSecure
    }

    /** The keyguard's own credential prompt, or null when the device has no screen lock. */
    fun promptIntent(context: Context): Intent? {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return null
        if (!keyguard.isDeviceSecure) return null
        return keyguard.createConfirmDeviceCredentialIntent(
            "Shizuku Reimagined",
            "Confirm it's you to manage the service"
        )
    }
}
