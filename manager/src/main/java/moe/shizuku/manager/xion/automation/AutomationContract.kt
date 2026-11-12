package moe.shizuku.manager.xion.automation

import android.content.Context
import android.content.Intent
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.utils.ShizukuStateMachine

/**
 * The exact contract automation apps (Tasker, MacroDroid, Automate) can rely on.
 * Control intents are received by real receivers declared in the manifest; events are
 * emitted as explicit broadcasts while this app is running (the watchdog keeps it running
 * when it is enabled).
 */
object AutomationContract {

    val PACKAGE = BuildConfig.APPLICATION_ID

    /** Sent to this package to start the service (same path as the boot receiver). */
    val ACTION_START = "$PACKAGE.START"

    /** Sent to this package to stop the service. */
    val ACTION_STOP = "$PACKAGE.STOP"

    /** Sent to this package to restart the service (stop, then start). */
    val ACTION_RESTART = "$PACKAGE.RESTART"

    /** Enable or disable the watchdog. Extra: [EXTRA_ENABLED]. */
    val ACTION_WATCHDOG = "$PACKAGE.WATCHDOG"

    /** Broadcast when the service becomes available. */
    val EVENT_STARTED = "$PACKAGE.EVENT.SERVICE_STARTED"

    /** Broadcast when the service was stopped on purpose. */
    val EVENT_STOPPED = "$PACKAGE.EVENT.SERVICE_STOPPED"

    /** Broadcast when the binder died unexpectedly. */
    val EVENT_CRASHED = "$PACKAGE.EVENT.SERVICE_CRASHED"

    /** Broadcast after the watchdog performed a restart attempt. */
    val EVENT_RESTARTED = "$PACKAGE.EVENT.SERVICE_RESTARTED"

    /** Broadcast when wireless debugging becomes available or unavailable. */
    val EVENT_WIRELESS = "$PACKAGE.EVENT.WIRELESS_DEBUGGING"

    const val EXTRA_ENABLED = "enabled"
    const val EXTRA_STATE = "state"
    const val EXTRA_TIMESTAMP = "timestamp"
    const val EXTRA_ATTEMPT = "attempt"
    const val EXTRA_UNAVAILABLE = "available"

    fun describe(): List<AutomationAction> = listOf(
        AutomationAction(ACTION_START, "Start the service", "Uses the last successful launch method. Requires root or an enabled wireless-debugging connection.", emptyList()),
        AutomationAction(ACTION_STOP, "Stop the service", "Stops the running service and its binder.", emptyList()),
        AutomationAction(ACTION_RESTART, "Restart the service", "Stops and immediately starts the service again.", emptyList()),
        AutomationAction(ACTION_WATCHDOG, "Toggle the watchdog", "Starts or stops the foreground watchdog.", listOf("$EXTRA_ENABLED: true|false"))
    )

    fun describeEvents(): List<AutomationAction> = listOf(
        AutomationAction(EVENT_STARTED, "Service started", "Emitted when the binder becomes available.", listOf("$EXTRA_TIMESTAMP: epoch millis")),
        AutomationAction(EVENT_STOPPED, "Service stopped", "Emitted when the service is stopped on purpose.", listOf("$EXTRA_TIMESTAMP: epoch millis")),
        AutomationAction(EVENT_CRASHED, "Service crashed", "Emitted when the binder dies unexpectedly.", listOf("$EXTRA_TIMESTAMP: epoch millis")),
        AutomationAction(EVENT_RESTARTED, "Watchdog restart", "Emitted after a watchdog restart attempt.", listOf("$EXTRA_ATTEMPT: int", "$EXTRA_ENABLED: success")),
        AutomationAction(EVENT_WIRELESS, "Wireless debugging changed", "Emitted when the TCP port or wireless ADB state changes while the app is running.", listOf("$EXTRA_UNAVAILABLE: true|false"))
    )

    data class AutomationAction(
        val action: String,
        val title: String,
        val description: String,
        val extras: List<String>
    )

    fun sendEvent(context: Context, action: String, extras: Map<String, Any?> = emptyMap()) {
        val intent = Intent(action).setPackage(context.packageName)
        intent.putExtra(EXTRA_TIMESTAMP, System.currentTimeMillis())
        extras.forEach { (key, value) ->
            when (value) {
                is Boolean -> intent.putExtra(key, value)
                is Int -> intent.putExtra(key, value)
                is Long -> intent.putExtra(key, value)
                is String -> intent.putExtra(key, value)
            }
        }
        runCatching { context.sendBroadcast(intent) }
    }

    /** Emits the event that matches a state transition, if any. */
    fun emitForState(context: Context, state: ShizukuStateMachine.State) {
        when (state) {
            ShizukuStateMachine.State.RUNNING -> sendEvent(context, EVENT_STARTED)
            ShizukuStateMachine.State.STOPPED -> sendEvent(context, EVENT_STOPPED)
            ShizukuStateMachine.State.CRASHED -> sendEvent(context, EVENT_CRASHED)
            else -> Unit
        }
    }

    fun adbExample(action: String): String =
        "adb shell am broadcast -a $action -p $PACKAGE"
}
