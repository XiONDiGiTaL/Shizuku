package moe.shizuku.manager.xion.qs

import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.service.WatchdogService
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.xion.service.ServiceController

/**
 * Quick Settings integration.
 *
 * Every tile performs the operation it advertises: start/restart go through the same code
 * path as the dashboard (root, TCP or wireless-discovery start), stop really stops the
 * service, and the watchdog tile starts or stops the foreground watchdog service. States are
 * derived from a live binder ping, never from a stored flag.
 */
abstract class XionTileService : TileService() {

    protected val running: Boolean
        get() = ShizukuStateMachine.update() == ShizukuStateMachine.State.RUNNING

    protected fun setTile(label: String, subtitle: String, state: Int, iconRes: Int) {
        val tile = qsTile ?: return
        tile.label = label
        tile.subtitle = subtitle
        tile.state = state
        tile.icon = Icon.createWithResource(this, iconRes)
        runCatching { tile.updateTile() }
    }

    protected fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this, 0, intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

class StartTileService : XionTileService() {

    override fun onStartListening() {
        val isRunning = running
        setTile(
            "Start Shizuku",
            if (isRunning) "Already running" else "Service stopped",
            if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE,
            R.drawable.ic_x_power
        )
    }

    override fun onClick() {
        if (running) {
            openApp()
            return
        }
        unlockAndRun {
            val outcome = ServiceController.start(this, null)
            if (!outcome.launchedUi) openApp()
        }
    }
}

class StopTileService : XionTileService() {

    override fun onStartListening() {
        val isRunning = running
        setTile(
            "Stop Shizuku",
            if (isRunning) "Tap to stop" else "Service stopped",
            if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_UNAVAILABLE,
            R.drawable.ic_x_stop
        )
    }

    override fun onClick() {
        unlockAndRun {
            if (running) ServiceController.stop(this)
            setTile("Stop Shizuku", "Service stopped", Tile.STATE_UNAVAILABLE, R.drawable.ic_x_stop)
        }
    }
}

class RestartTileService : XionTileService() {

    override fun onStartListening() {
        val isRunning = running
        setTile(
            "Restart Shizuku",
            if (isRunning) "Running" else "Service stopped",
            if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE,
            R.drawable.ic_x_restart
        )
    }

    override fun onClick() {
        unlockAndRun {
            val outcome = ServiceController.restart(this, null)
            if (!outcome.launchedUi && !running) openApp()
        }
    }
}

class WatchdogTileService : XionTileService() {

    private fun enabled() = WatchdogService.isRunning() || ShizukuSettings.getWatchdog()

    override fun onStartListening() {
        val on = enabled()
        setTile(
            "Shizuku watchdog",
            if (on) "Watching the service" else "Off",
            if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE,
            R.drawable.ic_x_shield
        )
    }

    override fun onClick() {
        unlockAndRun {
            val next = !enabled()
            ShizukuSettings.setWatchdog(this, next)
            setTile(
                "Shizuku watchdog",
                if (next) "Watching the service" else "Off",
                if (next) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE,
                R.drawable.ic_x_shield
            )
        }
    }
}

/** Informational tile: it reports real state and opens the dashboard when tapped. */
class StatusTileService : XionTileService() {

    override fun onStartListening() {
        val state = ShizukuStateMachine.update()
        val isRunning = state == ShizukuStateMachine.State.RUNNING
        val uid = if (isRunning) runCatching { rikka.shizuku.Shizuku.getUid() }.getOrDefault(-1) else -1
        setTile(
            "Shizuku status",
            when {
                !isRunning -> "Stopped"
                uid == 0 -> "Running as root"
                uid > 0 -> "Running as adb (uid $uid)"
                else -> "Running"
            },
            if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE,
            R.drawable.ic_x_chart
        )
    }

    override fun onClick() = openApp()
}

/** Manifest helper so the five tiles stay declared together. */
object QsTiles {
    val ALL: List<Class<out XionTileService>> = listOf(
        StartTileService::class.java,
        StopTileService::class.java,
        RestartTileService::class.java,
        WatchdogTileService::class.java,
        StatusTileService::class.java
    )
}
