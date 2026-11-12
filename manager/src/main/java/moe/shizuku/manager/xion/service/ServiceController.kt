package moe.shizuku.manager.xion.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.home.AdbDialogFragment
import moe.shizuku.manager.receiver.ShizukuReceiverStarter
import moe.shizuku.manager.starter.StarterActivity
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.xion.XionPrefs
import rikka.shizuku.Shizuku

/**
 * Thin controller over the start/stop mechanisms the fork already implements, so the new
 * dashboard performs the same real actions as the classic screen instead of faking them.
 */
object ServiceController {

    enum class Action { START, STOP, RESTART, RECONNECT }

    data class Outcome(val message: String, val launchedUi: Boolean = false)

    fun start(context: Context, activity: FragmentActivity?): Outcome {
        val state = ShizukuStateMachine.get()
        if (state == ShizukuStateMachine.State.RUNNING) {
            return Outcome("The service is already running.")
        }
        if (state == ShizukuStateMachine.State.STARTING) {
            return Outcome("The service is already starting.")
        }

        // Root: the manager can ask libsu to run the starter command directly.
        if (ShizukuSettings.getLastLaunchMode() == ShizukuSettings.LaunchMethod.ROOT && EnvironmentUtils.isRooted()) {
            XionPrefs.markStarted(context)
            ShizukuReceiverStarter.start(context, forceStart = true)
            return Outcome("Starting the service with root…")
        }

        val tcpPort = EnvironmentUtils.getAdbTcpPort()
        val tcpMode = ShizukuSettings.getTcpMode()

        // ADB already listening on TCP and the user keeps TCP mode: start over the loopback port.
        if (tcpPort > 0 && tcpMode) {
            context.startActivity(Intent(context, StarterActivity::class.java).apply {
                putExtra(StarterActivity.EXTRA_PORT, tcpPort)
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            return Outcome("Opening the ADB start window…", launchedUi = true)
        }

        // Android 11+: wireless debugging discovery through mDNS.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && activity != null) {
            if (context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                runCatching {
                    Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, 1)
                    Settings.Global.putLong(context.contentResolver, "adb_allowed_connection_time", 0L)
                }
            }
            AdbDialogFragment().show(activity.supportFragmentManager, "adb_start")
            return Outcome("Waiting for wireless debugging…", launchedUi = true)
        }

        if (activity != null) {
            AdbDialogFragment().show(activity.supportFragmentManager, "adb_start")
            return Outcome("Waiting for wireless debugging…", launchedUi = true)
        }

        return Outcome(context.getString(R.string.xion_tools_requires_service))
    }

    fun stop(context: Context): Outcome {
        if (!ShizukuStateMachine.isRunning()) {
            return Outcome("The service is not running.")
        }
        ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPING)
        XionPrefs.markStopped(context)
        val result = runCatching { Shizuku.exit() }
        return if (result.isSuccess) Outcome("Stopping the service…")
        else Outcome("Could not stop the service: ${result.exceptionOrNull()?.message}")
    }

    fun restart(context: Context, activity: FragmentActivity?): Outcome {
        if (ShizukuStateMachine.isRunning()) {
            runCatching { Shizuku.exit() }
            ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPING)
            Toast.makeText(context, "Restarting…", Toast.LENGTH_SHORT).show()
            // Give the server a moment to release the binder before starting again.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                start(context, activity)
            }, 1200)
            return Outcome("Restarting the service…")
        }
        return start(context, activity)
    }

    fun reconnect(context: Context): Outcome {
        val state = ShizukuStateMachine.update()
        return if (state == ShizukuStateMachine.State.RUNNING) {
            Outcome("Binder reconnected.")
        } else {
            Outcome("No binder answered; the service is not running.")
        }
    }
}
