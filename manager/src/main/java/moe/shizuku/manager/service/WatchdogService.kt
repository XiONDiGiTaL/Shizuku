package moe.shizuku.manager.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.receiver.ShizukuReceiverStarter
import moe.shizuku.manager.utils.SettingsHelper
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.xion.XionPrefs
import moe.shizuku.manager.xion.automation.AutomationContract

/**
 * Watchdog 2.0.
 *
 * Compared with the original watchdog this version:
 *  - performs a real health check (binder ping) on a configurable interval instead of only
 *    reacting to a dead binder;
 *  - restarts with exponential backoff and a hard attempt limit inside a rolling window,
 *    so a service that cannot start can never produce an endless restart loop;
 *  - records every attempt in a history the UI can display;
 *  - reports crashes, recoveries and its own giving up as separate notifications;
 *  - emits automation events.
 */
class WatchdogService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitorJob: Job? = null

    private val stateListener: (ShizukuStateMachine.State) -> Unit = { state ->
        AutomationContract.emitForState(applicationContext, state)
        when (state) {
            ShizukuStateMachine.State.CRASHED -> onCrashDetected()
            ShizukuStateMachine.State.RUNNING -> onServiceRunning()
            else -> Unit
        }
    }

    /** Timestamps of restart attempts inside the current rolling window. */
    private val attempts = ArrayDeque<Long>()
    private var gaveUp = false
    private var pendingRestart = false
    private var lastHealthyPing = 0L

    override fun onCreate() {
        super.onCreate()
        isRunning.set(true)
        ShizukuStateMachine.addListener(stateListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat()

        if (monitorJob == null) {
            monitorJob = scope.launch { monitorLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        ShizukuStateMachine.removeListener(stateListener)
        monitorJob?.cancel()
        scope.cancel()
        isRunning.set(false)
        ShizukuSettings.setWatchdog(applicationContext, false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- Health monitoring ----------------------------------------------

    private suspend fun monitorLoop() {
        var batteryHintShown = false
        while (scope.isActive) {
            val interval = XionPrefs.watchdogHealthIntervalSeconds(applicationContext) * 1000L
            delay(interval)

            // Battery optimisation is the most common reason a watchdog gets killed itself.
            if (!batteryHintShown && !SettingsHelper.isIgnoringBatteryOptimizations(applicationContext)) {
                batteryHintShown = true
                notifyBatteryOptimisation()
            }

            val state = ShizukuStateMachine.get()
            if (state == ShizukuStateMachine.State.RUNNING) {
                val alive = ShizukuStateMachine.update() == ShizukuStateMachine.State.RUNNING
                if (alive) {
                    lastHealthyPing = SystemClock.elapsedRealtime()
                    // A long healthy period clears the attempt budget.
                    if (attempts.size >= maxAttempts()) attempts.clear()
                    gaveUp = false
                } else {
                    onCrashDetected()
                }
            } else if (state == ShizukuStateMachine.State.STOPPED && !pendingRestart && attempts.isNotEmpty()) {
                // The service was stopped after a restart attempt and nothing brought it back.
                onCrashDetected()
            }
        }
    }

    private fun maxAttempts() = XionPrefs.watchdogMaxRestarts(applicationContext)

    private val windowMs = 15 * 60 * 1000L

    private fun onCrashDetected() {
        if (pendingRestart || gaveUp) return
        pendingRestart = true

        val now = System.currentTimeMillis()
        attempts.addLast(now)
        while (attempts.isNotEmpty() && now - attempts.first() > windowMs) attempts.removeFirst()

        if (attempts.size > maxAttempts()) {
            gaveUp = true
            pendingRestart = false
            XionPrefs.markCrashed(applicationContext)
            XionPrefs.addRestartEvent(
                applicationContext,
                XionPrefs.RestartEvent(now, "gave up after ${attempts.size - 1} attempts", attempts.size - 1, false)
            )
            notifyGaveUp()
            return
        }

        XionPrefs.markCrashed(applicationContext)
        showCrashNotification()

        val attempt = attempts.size
        val backoff = backoffFor(attempt)

        scope.launch {
            delay(backoff)
            val success = attemptRestart()
            XionPrefs.addRestartEvent(
                applicationContext,
                XionPrefs.RestartEvent(
                    System.currentTimeMillis(),
                    "binder died",
                    attempt,
                    success
                )
            )
            if (success) {
                if (attempts.size >= maxAttempts()) attempts.clear()
                notifyRecovered(attempt)
            } else if (attempts.size >= maxAttempts()) {
                gaveUp = true
                notifyGaveUp()
            }
            pendingRestart = false
        }
    }

    private fun backoffFor(attempt: Int): Long {
        val base = XionPrefs.watchdogBackoffSeconds(applicationContext) * 1000L
        // 1x, 2x, 4x … capped at 5 minutes.
        val multiplier = 1L shl (attempt - 1).coerceIn(0, 4)
        return (base * multiplier).coerceAtMost(5 * 60 * 1000L)
    }

    private suspend fun attemptRestart(): Boolean {
        return try {
            ShizukuReceiverStarter.start(applicationContext, forceStart = true)
            val running = withTimeoutOrNull(90_000) {
                ShizukuStateMachine.asFlow().first { it == ShizukuStateMachine.State.RUNNING }
            }
            running != null
        } catch (e: Throwable) {
            Log.w(TAG, "watchdog restart failed", e)
            false
        }
    }

    private fun onServiceRunning() {
        if (pendingRestart) return
        lastHealthyPing = SystemClock.elapsedRealtime()
        AutomationContract.sendEvent(applicationContext, AutomationContract.EVENT_STARTED)
    }

    // --- Notifications ---------------------------------------------------

    private fun channel(id: String, name: String, importance: Int): NotificationChannel =
        NotificationChannel(id, name, importance).also {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(it)
        }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID_WATCHDOG, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID_WATCHDOG, notification)
        }
    }

    private fun buildNotification(): Notification {
        channel(CHANNEL_ID_WATCHDOG, "Watchdog", NotificationManager.IMPORTANCE_LOW)

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val launchPendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, WatchdogService::class.java).apply { action = ACTION_STOP_SERVICE }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = when {
            gaveUp -> "Stopped restarting after repeated failures"
            pendingRestart -> "Service interrupted — recovery in progress"
            else -> "Watching the service"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID_WATCHDOG)
            .setContentTitle(getString(R.string.xion_watchdog_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentIntent(launchPendingIntent)
            .addAction(R.drawable.ic_close_24, getString(R.string.watchdog_turn_off), stopPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun notify(text: String) {
        channel(CRASH_CHANNEL_ID, "Service events", NotificationManager.IMPORTANCE_DEFAULT)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pending = PendingIntent.getActivity(
            this, 2, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CRASH_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(getString(R.string.xion_watchdog_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID_CRASH, notification) }
    }

    private fun showCrashNotification() {
        notify("The Shizuku service stopped unexpectedly. The watchdog is trying to bring it back.")
    }

    private fun notifyRecovered(attempt: Int) {
        notify("The service is running again (recovery attempt $attempt).")
        AutomationContract.sendEvent(
            applicationContext,
            AutomationContract.EVENT_RESTARTED,
            mapOf(AutomationContract.EXTRA_ATTEMPT to attempt, AutomationContract.EXTRA_ENABLED to true)
        )
    }

    private fun notifyGaveUp() {
        notify(
            "The service could not be restarted after ${maxAttempts()} attempts. " +
                "Automatic recovery is paused — open Shizuku Reimagined to start it manually."
        )
        AutomationContract.sendEvent(
            applicationContext,
            AutomationContract.EVENT_RESTARTED,
            mapOf(AutomationContract.EXTRA_ATTEMPT to maxAttempts(), AutomationContract.EXTRA_ENABLED to false)
        )
    }

    private fun notifyBatteryOptimisation() {
        notify(
            "Battery optimisation is enabled for Shizuku Reimagined. " +
                "Android may stop the watchdog in the background; exclude this app from optimisation for reliable recovery."
        )
    }

    companion object {
        private const val TAG = "ShizukuWatchdog"
        private const val CHANNEL_ID_WATCHDOG = "shizuku_watchdog"
        private const val NOTIFICATION_ID_WATCHDOG = 1001
        private const val NOTIFICATION_ID_CRASH = 1002
        const val CRASH_CHANNEL_ID = "crash_reports"
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"

        private val isRunning = AtomicBoolean(false)

        @JvmStatic
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, WatchdogService::class.java))
            } catch (e: Exception) {
                Log.e("ShizukuApplication", "Failed to start WatchdogService: ${e.message}")
            }
        }

        @JvmStatic
        fun stop(context: Context) {
            context.stopService(Intent(context, WatchdogService::class.java))
        }

        @JvmStatic
        fun isRunning(): Boolean = isRunning.get()
    }
}
