package moe.shizuku.manager.xion

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.Toast

import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.databinding.ActivityXionDashboardBinding
import moe.shizuku.manager.databinding.XionNavRowBinding
import moe.shizuku.manager.service.WatchdogService
import moe.shizuku.manager.settings.SettingsActivity
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.xion.auth.AuthLoadResult
import moe.shizuku.manager.xion.auth.AuthorizationRepository
import moe.shizuku.manager.xion.security.AppLock
import moe.shizuku.manager.xion.service.ServiceController
import moe.shizuku.manager.xion.shell.PrivilegedShell
import moe.shizuku.manager.xion.automation.AutomationActivity
import moe.shizuku.manager.xion.backup.BackupActivity
import moe.shizuku.manager.xion.diag.DiagnosticsActivity
import moe.shizuku.manager.xion.tools.ToolsActivity
import rikka.shizuku.Shizuku

class DashboardActivity : XionActivity() {

    private lateinit var binding: ActivityXionDashboardBinding

    private val stateListener: (ShizukuStateMachine.State) -> Unit = {
        runOnUiThread { refresh() }
    }



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityXionDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyInsets()
        bindNavigation()
        bindActions()
        applyNarrowLayout()

        // The badge used to read "DEV IDENTITY", which told nobody anything. The version and build
        // are what people actually quote when something goes wrong.
        binding.identityBadge.text = "v${BuildConfig.VERSION_NAME} · ${BuildConfig.VERSION_CODE}"

        // The footer is the project's own address, not a version stamp: version and build live in
        // Diagnostics, where they belong when someone is actually reporting a problem.
        binding.footer.text = XionLinks.SITE_LABEL
        binding.footer.paintFlags = binding.footer.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
        binding.footer.contentDescription = "Open ${XionLinks.SITE}"
        binding.footer.setOnClickListener {
            moe.shizuku.manager.utils.CustomTabsHelper.launchUrlOrCopy(this, XionLinks.SITE)
        }

        // The brand mark is the way back to the project's author.
        binding.brand.setOnClickListener {
            moe.shizuku.manager.utils.CustomTabsHelper.launchUrlOrCopy(this, XionLinks.PROFILE)
        }
    }

    override fun onStart() {
        super.onStart()
        ShizukuStateMachine.addListener(stateListener)
    }

    override fun onUnlocked() {
        refresh()
    }

    override fun onStop() {
        ShizukuStateMachine.removeListener(stateListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        refreshLockRow()
    }

    private fun refreshLockRow() {
        binding.navLock.subtitle.setText(
            if (AppLock.isEnabled(this)) R.string.xion_lock_on else R.string.xion_nav_lock_sub
        )
    }

    private fun showLockDialog() {
        val on = AppLock.isEnabled(this)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.xion_lock_title)
            .setMessage(R.string.xion_lock_body)
            .setPositiveButton(if (on) "Turn off" else "Turn on") { _, _ ->
                if (on) {
                    AppLock.setEnabled(this, false)
                    Toast.makeText(this, R.string.xion_lock_off, Toast.LENGTH_SHORT).show()
                } else if (!AppLock.isDeviceSecure(this)) {
                    Toast.makeText(this, R.string.xion_lock_no_device_lock, Toast.LENGTH_LONG).show()
                    runCatching { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
                } else {
                    AppLock.setEnabled(this, true)
                    Toast.makeText(this, R.string.xion_lock_on, Toast.LENGTH_SHORT).show()
                }
                refreshLockRow()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * A large display-size setting (or a small phone) leaves roughly 320dp of width. The brand
     * title is two weighted text views and the action rows are three equal buttons, so both break
     * mid-word there: "Reimagined" ends up one syllable per line and "Start" one letter per line.
     * Stacking the title and dropping the button icons gives the text the width it needs.
     */
    private fun applyNarrowLayout() {
        if (resources.configuration.screenWidthDp >= 360) return

        binding.titleRow.orientation = android.widget.LinearLayout.VERTICAL
        val accentParams = binding.appTitleAccent.layoutParams as android.view.ViewGroup.MarginLayoutParams
        accentParams.marginStart = 0
        binding.appTitleAccent.layoutParams = accentParams

        binding.identityBadge.apply {
            textSize = 9f
            maxLines = 1
            (layoutParams as android.view.ViewGroup.MarginLayoutParams).marginStart = 0
        }

        listOf(binding.btnStart, binding.btnStop, binding.btnRestart, binding.btnWireless, binding.btnDiagnose)
            .forEach { button ->
                button.icon = null
                button.iconPadding = 0
                button.textSize = 12f
                button.maxLines = 1
            }
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.content) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                v.paddingLeft,
                bars.top + resources.getDimensionPixelSize(R.dimen.xion_space_2),
                v.paddingRight,
                bars.bottom + resources.getDimensionPixelSize(R.dimen.xion_space_6)
            )
            insets
        }
    }

    // --- Binding ---------------------------------------------------------

    private fun bindNavigation() {
        nav(binding.navAuthorizations, R.drawable.ic_x_shield, R.string.xion_nav_authorizations, R.string.xion_nav_authorizations_sub) {
            startActivity(Intent(this, AuthorizationsActivity::class.java))
        }
        nav(binding.navAutomation, R.drawable.ic_x_bolt, R.string.xion_nav_automation, R.string.xion_nav_automation_sub) {
            startActivity(Intent(this, AutomationActivity::class.java))
        }
        nav(binding.navTools, R.drawable.ic_x_apps, R.string.xion_nav_tools, R.string.xion_nav_tools_sub) {
            startActivity(Intent(this, ToolsActivity::class.java))
        }
        nav(binding.navDiagnostics, R.drawable.ic_x_bug, R.string.xion_nav_diagnostics, R.string.xion_nav_diagnostics_sub) {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
        nav(binding.navBackup, R.drawable.ic_x_export, R.string.xion_nav_backup, R.string.xion_nav_backup_sub) {
            startActivity(Intent(this, BackupActivity::class.java))
        }
        nav(binding.navSettings, R.drawable.ic_x_gear, R.string.xion_nav_settings, R.string.xion_nav_settings_sub) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        nav(binding.navLock, R.drawable.ic_x_shield, R.string.xion_nav_lock, R.string.xion_nav_lock_sub) {
            showLockDialog()
        }
        binding.cardAuthorized.setOnClickListener { startActivity(Intent(this, AuthorizationsActivity::class.java)) }
        binding.cardWatchdog.setOnClickListener { showWatchdogDialog() }
        binding.cardWireless.setOnClickListener { startActivity(Intent(this, WirelessActivity::class.java)) }
        binding.cardUptime.setOnClickListener { showServiceDialog() }
        binding.cardUptime.isClickable = true
        binding.cardUptime.isFocusable = true
        binding.cardWireless.isClickable = true
        binding.cardWireless.isFocusable = true
        binding.cardWatchdog.isClickable = true
        binding.cardWatchdog.isFocusable = true
        binding.cardAuthorized.isClickable = true
        binding.cardAuthorized.isFocusable = true
    }

    private fun nav(
        row: XionNavRowBinding,
        icon: Int,
        title: Int,
        subtitle: Int,
        onClick: () -> Unit
    ) {
        row.icon.setImageResource(icon)
        row.icon.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.xion_brand_hi)
        )
        row.title.setText(title)
        row.subtitle.setText(subtitle)
        row.root.setOnClickListener { onClick() }
    }

    private fun bindActions() {
        binding.btnStart.setOnClickListener { action { ServiceController.start(this, this) } }
        binding.btnStop.setOnClickListener { action { ServiceController.stop(this) } }
        binding.btnRestart.setOnClickListener { action { ServiceController.restart(this, this) } }
        binding.btnWireless.setOnClickListener {
            startActivity(Intent(this, WirelessActivity::class.java))
        }
        binding.btnDiagnose.setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
    }

    private fun action(block: () -> ServiceController.Outcome) {
        val outcome = block()
        Toast.makeText(this, outcome.message, Toast.LENGTH_SHORT).show()
        binding.root.postDelayed({ refresh() }, 900)
    }

    // --- State -----------------------------------------------------------

    private fun refresh() {
        val state = ShizukuStateMachine.update()
        val running = state == ShizukuStateMachine.State.RUNNING

        val (titleRes, dotColor) = when (state) {
            ShizukuStateMachine.State.RUNNING -> R.string.xion_state_running to R.color.xion_positive
            ShizukuStateMachine.State.STARTING -> R.string.xion_state_starting to R.color.xion_caution
            ShizukuStateMachine.State.STOPPING -> R.string.xion_state_stopping to R.color.xion_caution
            ShizukuStateMachine.State.CRASHED -> R.string.xion_state_crashed to R.color.xion_negative
            ShizukuStateMachine.State.STOPPED -> R.string.xion_state_stopped to R.color.xion_text_muted
        }
        binding.statusTitle.setText(titleRes)
        binding.statusDot.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, dotColor))

        val uid = if (running) runCatching { Shizuku.getUid() }.getOrDefault(-1) else -1
        val api = if (running) runCatching { Shizuku.getVersion() }.getOrDefault(-1) else -1

        binding.statusChip.text = if (running) {
            when (uid) {
                0 -> "ROOT · API $api"
                else -> "ADB · API $api"
            }
        } else {
            "OFFLINE"
        }

        binding.statusDetail.text = when {
            running -> buildString {
                append(if (uid == 0) "Running as root (uid 0)" else "Running as shell (uid $uid)")
                val openPort = EnvironmentUtils.getAdbTcpPort()
                if (openPort > 0) append(" · port $openPort")
            }
            state == ShizukuStateMachine.State.STARTING -> "Waiting for the service to answer…"
            else -> "Start the service to grant apps ADB-level access."
        }

        binding.btnStart.isEnabled = !running && state != ShizukuStateMachine.State.STARTING
        binding.btnStop.isEnabled = running
        binding.btnRestart.isEnabled = running
        binding.btnWireless.isEnabled = true

        // Watchdog
        val watchdogOn = WatchdogService.isRunning() || ShizukuSettings.getWatchdog()
        binding.metricWatchdog.text = if (watchdogOn) "On" else "Off"
        binding.metricWatchdogSub.text = if (watchdogOn) {
            "max ${XionPrefs.watchdogMaxRestarts(this)} restarts"
        } else "not monitoring"

        // Wireless debugging (read from system settings, real device state)
        val adbEnabled = runCatching {
            Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 0)
        }.getOrDefault(0)
        val wirelessEnabled = runCatching {
            Settings.Global.getInt(contentResolver, "adb_wifi_enabled", 0)
        }.getOrDefault(0)
        val port = EnvironmentUtils.getAdbTcpPort()

        binding.metricWireless.text = when {
            wirelessEnabled == 1 -> "Wireless"
            adbEnabled == 1 -> "USB"
            else -> "Off"
        }
        binding.metricWirelessSub.text = when {
            wirelessEnabled == 1 && port > 0 -> "port $port · tap to manage"
            wirelessEnabled == 1 -> "tap to pair or start"
            adbEnabled == 1 -> "wireless off · tap to set up"
            else -> "debugging off · tap to set up"
        }

        // Uptime
        val startedAt = XionPrefs.lastStartedAt(this)
        if (running && startedAt > 0) {
            binding.metricUptime.text = formatDuration(System.currentTimeMillis() - startedAt)
            binding.metricUptimeSub.text = "since ${formatTime(startedAt)}"
        } else {
            binding.metricUptime.text = "n/a"
            binding.metricUptimeSub.text = if (startedAt > 0) "last ${formatTime(startedAt)}" else "never started here"
        }

        // Authorizations: ask the service, and never render a failure as "0".
        if (running) {
            lifecycleScope.launch {
                when (val result = AuthorizationRepository.load(this@DashboardActivity, includeHistory = true)) {
                    is AuthLoadResult.Ok -> {
                        val snapshot = result.snapshot
                        binding.metricAuthorized.text = snapshot.grantedCount.toString()
                        binding.metricAuthorizedSub.text = when {
                            snapshot.reportingAnomaly ->
                                "${snapshot.candidateCount} request access · service reported none"
                            snapshot.unknownCount > 0 ->
                                "of ${snapshot.candidateCount} · ${snapshot.unknownCount} unknown"
                            else -> "of ${snapshot.candidateCount} requesting apps"
                        }
                    }
                    is AuthLoadResult.Failure -> {
                        binding.metricAuthorized.text = "!"
                        binding.metricAuthorizedSub.text = "state unavailable"
                    }
                }
            }
        } else {
            binding.metricAuthorized.text = "n/a"
            binding.metricAuthorizedSub.text = "service offline"
        }
    }

    // --- Dialogs ---------------------------------------------------------

    private fun showWatchdogDialog() {
        val history = XionPrefs.restartHistory(this)
        val on = WatchdogService.isRunning() || ShizukuSettings.getWatchdog()
        val body = buildString {
            appendLine(if (on) "Watchdog is running." else "Watchdog is off.")
            appendLine("Health check: every ${XionPrefs.watchdogHealthIntervalSeconds(this@DashboardActivity)}s")
            appendLine("Backoff: ${XionPrefs.watchdogBackoffSeconds(this@DashboardActivity)}s base, exponential")
            appendLine("Restart limit: ${XionPrefs.watchdogMaxRestarts(this@DashboardActivity)} per 15 minutes")
            appendLine()
            appendLine(getString(R.string.xion_watchdog_history))
            if (history.isEmpty()) {
                append(getString(R.string.xion_watchdog_empty_history))
            } else {
                history.takeLast(6).reversed().forEach {
                    appendLine(
                        "${formatTime(it.timestamp)} · ${it.reason} · attempt ${it.attempt} · ${if (it.success) "ok" else "failed"}"
                    )
                }
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.xion_watchdog_title)
            .setMessage(body)
            .setPositiveButton(if (on) "Turn off" else "Turn on") { _, _ ->
                ShizukuSettings.setWatchdog(this, !on)
                binding.root.postDelayed({ refresh() }, 600)
            }
            .setNeutralButton("Tune…") { _, _ -> showWatchdogTuning() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showWatchdogTuning() {
        val limits = arrayOf("2 restarts", "3 restarts", "5 restarts", "10 restarts")
        val values = intArrayOf(2, 3, 5, 10)
        MaterialAlertDialogBuilder(this)
            .setTitle("Restart limit")
            .setItems(limits) { _, which ->
                XionPrefs.setWatchdogMaxRestarts(this, values[which])
                Toast.makeText(this, "Limit set to ${values[which]}", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showServiceDialog() {
        if (!ShizukuStateMachine.isRunning()) {
            Toast.makeText(this, "The service is not running.", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val result = PrivilegedShell.run("ps -A -o USER,PID,PPID,NAME | grep -i shizuku_server")
            val api = runCatching { Shizuku.getVersion() }.getOrDefault(-1)
            val patch = runCatching { Shizuku.getServerPatchVersion() }.getOrDefault(-1)
            val se = runCatching { Shizuku.getSELinuxContext() }.getOrDefault("n/a")
            val uid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
            val msg = buildString {
                appendLine("uid: $uid")
                appendLine("api: $api (patch $patch)")
                appendLine("secontext: $se")
                appendLine()
                appendLine("process:")
                append(result.combined.lines().filter { it.isNotBlank() }.take(4).joinToString("\n"))
            }
            MaterialAlertDialogBuilder(this@DashboardActivity)
                .setTitle("Service process")
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun formatDuration(ms: Long): String {
        val minutes = ms / 60000
        return when {
            minutes < 1 -> "<1m"
            minutes < 60 -> "${minutes}m"
            minutes < 1440 -> "${minutes / 60}h ${minutes % 60}m"
            else -> "${minutes / 1440}d ${(minutes % 1440) / 60}h"
        }
    }

    private fun formatTime(timestamp: Long) =
        SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(timestamp))
}
