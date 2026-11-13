package moe.shizuku.manager.platform

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.xion.shell.PrivilegedShell

/**
 * Probes what this device can actually do, and records the evidence.
 *
 * The rules here matter more than the list:
 * - a capability is AVAILABLE only when a read-only probe succeeded on this device;
 * - a failed probe means UNAVAILABLE, with the reason kept;
 * - if something cannot be checked without changing state, the state is UNKNOWN, never ASSUMED;
 * - nothing in this file runs `su`, and a root-privileged service is not treated as a capability.
 */
object CapabilityRegistry {

    enum class Manufacturer { SAMSUNG, PIXEL, XIAOMI, ONEPLUS, MOTOROLA, OTHER }

    fun manufacturer(): Manufacturer = when (Build.MANUFACTURER.lowercase()) {
        "samsung" -> Manufacturer.SAMSUNG
        "google" -> Manufacturer.PIXEL
        "xiaomi", "redmi", "poco" -> Manufacturer.XIAOMI
        "oneplus" -> Manufacturer.ONEPLUS
        "motorola", "moto" -> Manufacturer.MOTOROLA
        else -> Manufacturer.OTHER
    }

    fun manufacturerLabel(): String = when (manufacturer()) {
        Manufacturer.SAMSUNG -> "Samsung One UI"
        Manufacturer.PIXEL -> "Google Pixel"
        Manufacturer.XIAOMI -> "Xiaomi HyperOS"
        Manufacturer.ONEPLUS -> "OnePlus OxygenOS"
        Manufacturer.MOTOROLA -> "Motorola"
        Manufacturer.OTHER -> Build.MANUFACTURER
    }

    /** True when the running service is the shell user, which is the only privilege model we support. */
    fun isShellPrivileged(): Boolean = ShizukuStateMachine.isRunning() && PrivilegedShell.serviceUid() == 2000

    suspend fun snapshot(context: Context): List<Capability> = withContext(Dispatchers.IO) {
        val list = ArrayList<Capability>()
        val running = ShizukuStateMachine.isRunning()
        val uid = if (running) PrivilegedShell.serviceUid() else -1

        list.add(
            Capability(
                "service.shell",
                "Shizuku service (shell privilege)",
                Tier.SHIZUKU_ADB,
                when {
                    uid == 2000 -> CapabilityState.AVAILABLE
                    uid == 0 -> CapabilityState.UNAVAILABLE
                    else -> CapabilityState.UNAVAILABLE
                },
                when {
                    uid == 2000 -> "running as uid 2000 (shell)"
                    uid == 0 -> "running as uid 0; this platform does not use root privilege"
                    running -> "service running but uid was not readable"
                    else -> "service not running"
                }
            )
        )

        if (running && uid == 2000) {
            val idProbe = PrivilegedShell.run("id -u")
            list.add(
                Capability(
                    "shell.exec",
                    "Shell command execution",
                    Tier.SHIZUKU_ADB,
                    if (idProbe.ok && idProbe.stdout.trim() == "2000") CapabilityState.AVAILABLE else CapabilityState.UNAVAILABLE,
                    if (idProbe.ok) "id -u returned ${idProbe.stdout.trim()}" else "probe failed: ${idProbe.combined.take(80)}"
                )
            )

            val pmProbe = PrivilegedShell.run("pm list packages | wc -l")
            val pmCount = pmProbe.stdout.trim().toIntOrNull()
            list.add(
                Capability(
                    "package.query",
                    "Package inventory",
                    Tier.SHIZUKU_ADB,
                    if (pmProbe.ok && (pmCount ?: 0) > 0) CapabilityState.AVAILABLE else CapabilityState.UNAVAILABLE,
                    if (pmProbe.ok) "$pmCount packages listed" else "probe failed: ${pmProbe.combined.take(80)}"
                )
            )

            // Read-only: appops get changes nothing. Writing modes comes later, with a read-back check.
            val opsProbe = PrivilegedShell.run("cmd appops get android")
            list.add(
                Capability(
                    "appops.read",
                    "AppOps read",
                    Tier.SHIZUKU_ADB,
                    if (opsProbe.ok) CapabilityState.AVAILABLE else CapabilityState.UNAVAILABLE,
                    if (opsProbe.ok) "cmd appops readable" else "probe failed: ${opsProbe.combined.take(80)}"
                )
            )
        } else {
            list.add(
                Capability(
                    "shell.exec",
                    "Shell command execution",
                    Tier.SHIZUKU_ADB,
                    CapabilityState.UNAVAILABLE,
                    "needs a running service with shell privilege"
                )
            )
            list.add(
                Capability(
                    "package.query",
                    "Package inventory",
                    Tier.SHIZUKU_ADB,
                    CapabilityState.UNAVAILABLE,
                    "needs a running service with shell privilege"
                )
            )
            list.add(
                Capability(
                    "appops.read",
                    "AppOps read",
                    Tier.SHIZUKU_ADB,
                    CapabilityState.UNAVAILABLE,
                    "needs a running service with shell privilege"
                )
            )
        }

        val adbEnabled = globalInt(context, Settings.Global.ADB_ENABLED)
        list.add(
            Capability(
                "debug.usb",
                "USB debugging",
                Tier.ANDROID_PERMISSION,
                if (adbEnabled == 1) CapabilityState.AVAILABLE else CapabilityState.UNAVAILABLE,
                if (adbEnabled == 1) "enabled in developer options" else "disabled in developer options"
            )
        )

        val wireless = globalInt(context, "adb_wifi_enabled")
        list.add(
            Capability(
                "debug.wireless",
                "Wireless debugging",
                Tier.ANDROID_PERMISSION,
                if (wireless == 1) CapabilityState.AVAILABLE else CapabilityState.UNAVAILABLE,
                if (wireless == 1) "enabled in developer options" else "disabled in developer options"
            )
        )

        val notifications = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        list.add(
            Capability(
                "permission.notifications",
                "Notifications",
                Tier.ANDROID_PERMISSION,
                if (notifications) CapabilityState.AVAILABLE else CapabilityState.UNAVAILABLE,
                if (notifications) "granted" else "not granted; pairing and watchdog cannot notify"
            )
        )

        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val secure = keyguard?.isDeviceSecure == true
        list.add(
            Capability(
                "security.credential",
                "Device screen lock",
                Tier.ANDROID_PERMISSION,
                if (secure) CapabilityState.AVAILABLE else CapabilityState.UNAVAILABLE,
                if (secure) "a secure lock is set, so app lock can be enabled" else "no secure lock set"
            )
        )

        val adapter = manufacturerLabel()
        list.add(
            Capability(
                "manufacturer.adapter",
                "Manufacturer adapter",
                Tier.MANUFACTURER,
                if (manufacturer() == Manufacturer.OTHER) CapabilityState.UNKNOWN else CapabilityState.UNAVAILABLE,
                if (manufacturer() == Manufacturer.OTHER) {
                    "no adapter for ${Build.MANUFACTURER}; generic Android capabilities only"
                } else {
                    "$adapter detected on SDK ${Build.VERSION.SDK_INT}, no manufacturer-specific actions implemented yet"
                }
            )
        )

        list
    }

    private fun globalInt(context: Context, key: String): Int = runCatching {
        Settings.Global.getInt(context.contentResolver, key, 0)
    }.getOrDefault(0)
}
