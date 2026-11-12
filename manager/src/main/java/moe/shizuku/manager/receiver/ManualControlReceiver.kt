package moe.shizuku.manager.receiver

import android.content.Context
import android.content.Intent
import android.provider.Settings
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.service.WatchdogService
import moe.shizuku.manager.utils.ShizukuStateMachine
import rikka.shizuku.Shizuku
import android.os.Handler
import android.os.Looper

/**
 * Automation entry points that are not simple start/stop:
 *  - ACTION_RESTART:  stop then start through the same path the UI uses.
 *  - ACTION_WATCHDOG: enable/disable the watchdog with an "enabled" extra.
 * They reuse the existing authenticated receiver base so only callers holding the token or
 * the app's own permission can drive them.
 */
class ManualControlReceiver : AuthenticatedReceiver() {

    override fun onAuthenticated(context: Context, intent: Intent) {
        val packageName = BuildConfig.APPLICATION_ID
        when (intent.action) {
            "$packageName.RESTART" -> {
                if (ShizukuStateMachine.isRunning()) {
                    ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPING)
                    runCatching { Shizuku.exit() }
                    Handler(Looper.getMainLooper()).postDelayed({
                        ShizukuReceiverStarter.start(context, forceStart = true)
                    }, 1500)
                } else {
                    ShizukuReceiverStarter.start(context, forceStart = true)
                }
            }

            "$packageName.WATCHDOG" -> {
                val enable = intent.getBooleanExtra("enabled", !ShizukuSettings.getWatchdog())
                ShizukuSettings.setWatchdog(context, enable)
            }
        }
    }
}
