package moe.shizuku.manager.xion.diag

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemProperties
import android.text.format.Formatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.SettingsHelper
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.xion.XionPrefs
import moe.shizuku.manager.xion.auth.AuthLoadResult
import moe.shizuku.manager.xion.auth.AuthorizationRepository
import moe.shizuku.manager.xion.shell.PrivilegedShell
import rikka.shizuku.Shizuku

object DiagnosticsCollector {

    data class Section(val title: String, val rows: List<Pair<String, String>>)

    suspend fun collect(context: Context): List<Section> = withContext(Dispatchers.IO) {
        val sections = ArrayList<Section>()

        sections.add(
            Section(
                "Application",
                listOf(
                    "Package" to BuildConfig.APPLICATION_ID,
                    "Version" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    "Build type" to BuildConfig.BUILD_TYPE,
                    "Identity" to if (BuildConfig.DEV_IDENTITY) "development (coexists with official Shizuku)" else "official",
                    "Installed upstream Shizuku" to if (isInstalled(context, BuildConfig.UPSTREAM_MANAGER_ID)) "yes" else "no"
                )
            )
        )

        sections.add(
            Section(
                "Device",
                listOf(
                    "Manufacturer" to Build.MANUFACTURER,
                    "Model" to Build.MODEL,
                    "Device" to Build.DEVICE,
                    "Android" to "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
                    "One UI" to oneUiVersion(),
                    "Build ID" to Build.DISPLAY,
                    "Fingerprint" to Build.FINGERPRINT,
                    "ABI" to Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
                    "Form factor" to when {
                        EnvironmentUtils.isTelevision() -> "TV"
                        EnvironmentUtils.isWatch() -> "Watch"
                        context.resources.configuration.smallestScreenWidthDp >= 600 -> "Tablet / large screen"
                        else -> "Phone"
                    }
                )
            )
        )

        val running = ShizukuStateMachine.isRunning()
        sections.add(
            Section(
                "Service",
                listOf(
                    "State" to ShizukuStateMachine.get().name,
                    "Running" to running.toString(),
                    "Privilege uid" to if (running) PrivilegedShell.serviceUid().toString() else "—",
                    "Privilege level" to when {
                        !running -> "none"
                        PrivilegedShell.isRoot() -> "root"
                        else -> "adb (shell)"
                    },
                    "API version" to if (running) runCatching { Shizuku.getVersion() }.getOrDefault(-1).toString() else "—",
                    "Server patch" to if (running) runCatching { Shizuku.getServerPatchVersion() }.getOrDefault(-1).toString() else "—",
                    "SELinux context" to if (running) runCatching { Shizuku.getSELinuxContext() }.getOrDefault("—") else "—",
                    "Last start" to Format.timeAgo(XionPrefs.lastStartedAt(context)),
                    "Last stop" to Format.timeAgo(XionPrefs.lastStoppedAt(context)),
                    "Last crash" to Format.timeAgo(XionPrefs.lastCrashedAt(context)),
                    "Start on boot" to ShizukuSettings.getStartOnBoot(context).toString(),
                    "Watchdog" to ShizukuSettings.getWatchdog().toString(),
                    "Watchdog restarts recorded" to XionPrefs.restartHistory(context).size.toString()
                )
            )
        )

        val adbEnabled = runCatching {
            android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.ADB_ENABLED, 0)
        }.getOrDefault(0)
        val wirelessEnabled = runCatching {
            android.provider.Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0)
        }.getOrDefault(0)
        sections.add(
            Section(
                "Wireless debugging",
                listOf(
                    "USB debugging" to if (adbEnabled == 1) "enabled" else "disabled",
                    "Wireless debugging" to if (wirelessEnabled == 1) "enabled" else "disabled",
                    "ADB TCP port" to EnvironmentUtils.getAdbTcpPort().toString(),
                    "TCP mode" to ShizukuSettings.getTcpMode().toString(),
                    "mDNS pairing supported" to EnvironmentUtils.isTlsSupported().toString(),
                    "Battery optimisations ignored" to SettingsHelper.isIgnoringBatteryOptimizations(context).toString()
                )
            )
        )

        val auth = AuthorizationRepository.load(context)
        sections.add(
            Section(
                "Authorizations",
                when (auth) {
                    is AuthLoadResult.Ok -> listOf(
                        "Reported by service" to auth.snapshot.serviceReportedCount.toString(),
                        "Granted" to auth.snapshot.grantedCount.toString(),
                        "Denied" to auth.snapshot.deniedCount.toString(),
                        "Unresolved" to auth.snapshot.unknownCount.toString(),
                        "Remembered but absent" to auth.snapshot.missingCount.toString()
                    )
                    is AuthLoadResult.Failure -> listOf("Could not read" to auth.message, "Detail" to (auth.detail ?: "—"))
                }
            )
        )

        val battery = battery(context)
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val stat = StatFs(Environment.getDataDirectory().path)
        sections.add(
            Section(
                "Resources",
                listOf(
                    "Battery" to battery,
                    "Memory" to "${Formatter.formatFileSize(context, mem.availMem)} free of ${Formatter.formatFileSize(context, mem.totalMem)}",
                    "Low memory" to mem.lowMemory.toString(),
                    "Data storage" to "${Formatter.formatFileSize(context, stat.availableBytes)} free of ${Formatter.formatFileSize(context, stat.totalBytes)}"
                )
            )
        )

        if (PrivilegedShell.isAvailable()) {
            val count = PrivilegedShell.run("pm list packages | wc -l")
            val thirdParty = PrivilegedShell.run("pm list packages -3 | wc -l")
            sections.add(
                Section(
                    "Package inventory",
                    listOf(
                        "Installed packages" to count.combined.trim(),
                        "Third-party packages" to thirdParty.combined.trim()
                    )
                )
            )
        }

        sections
    }

    fun render(sections: List<Section>): String = buildString {
        appendLine("Shizuku Reimagined — diagnostic report")
        appendLine("Generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.US).format(java.util.Date())}")
        appendLine()
        sections.forEach { section ->
            appendLine("[${section.title}]")
            section.rows.forEach { (key, value) ->
                appendLine("$key: $value")
            }
            appendLine()
        }
    }

    private fun oneUiVersion(): String {
        val oneUi = runCatching { SystemProperties.get("ro.build.version.oneui") }.getOrDefault("")
        val sem = runCatching { SystemProperties.get("ro.build.version.sem") }.getOrDefault("")
        if (oneUi.isBlank() && sem.isBlank()) return "not a Samsung build"
        return buildString {
            if (oneUi.isNotBlank()) {
                val value = oneUi.toLongOrNull()
                if (value != null && value >= 10000) append("${value / 10000}.${(value % 10000) / 100}")
                else append(oneUi)
            }
            if (sem.isNotBlank()) append(" (sem $sem)")
        }
    }

    private fun battery(context: Context): String = runCatching {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        "$pct%" + if (charging) " (charging)" else ""
    }.getOrDefault("unavailable")

    private fun isInstalled(context: Context, packageName: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess

    object Format {
        fun timeAgo(timestamp: Long): String {
            if (timestamp <= 0L) return "never"
            val diff = System.currentTimeMillis() - timestamp
            return when {
                diff < 60_000 -> "just now"
                diff < 3_600_000 -> "${diff / 60_000} min ago"
                diff < 86_400_000 -> "${diff / 3_600_000} h ago"
                else -> "${diff / 86_400_000} d ago"
            }
        }
    }
}
