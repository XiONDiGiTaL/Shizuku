package moe.shizuku.manager.xion

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import moe.shizuku.manager.xion.security.AppLock

/**
 * Every screen in the app inherits the optional device-credential gate, so leaving the app on the
 * Tools screen and coming back a minute later asks for the unlock just like the dashboard does.
 * The prompt is the keyguard's own; this app only sees whether it succeeded.
 *
 * The master-lock idea comes from Shevery by HmnDev-Tech (Apache-2.0); this is a single app-wide
 * switch rather than their per-action protection.
 */
open class XionActivity : AppCompatActivity() {

    private var unlockInFlight = false

    private val unlockLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            unlockInFlight = false
            if (result.resultCode == RESULT_OK) {
                AppLock.markUnlocked(this)
                onUnlocked()
            } else {
                // No credential, no manager: close every screen instead of showing a half-usable app.
                finishAffinity()
            }
        }

    /** Called after a successful unlock, for screens that need to reload state. */
    protected open fun onUnlocked() = Unit

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Coming back to an existing task should re-check, not only a cold start.
        if (AppLock.needsUnlock(this)) enforceLock()
    }

    override fun onStart() {
        super.onStart()
        enforceLock()
    }

    private fun enforceLock() {
        if (unlockInFlight || isFinishing || !AppLock.needsUnlock(this)) return
        val intent = AppLock.promptIntent(this)
        if (intent == null) {
            // The screen lock was removed while the guard was on; there is nothing left to ask for.
            AppLock.setEnabled(this, false)
            return
        }
        unlockInFlight = true
        unlockLauncher.launch(intent)
    }
}
