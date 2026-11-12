package moe.shizuku.manager.xion.auth

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.authorization.AuthorizationManager
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.utils.ShizukuSystemApis
import moe.shizuku.manager.xion.XionPrefs
import moe.shizuku.manager.xion.shell.PrivilegedShell
import rikka.shizuku.Shizuku

/**
 * Why this exists
 * ---------------
 * The classic screen counted authorizations with a single hidden-API call. On Android 17
 * (Samsung One UI 9.0) that call returns an empty list, so a working installation displayed
 * "Authorized 0 applications" while granted apps kept working. That is a reporting failure,
 * not an authorization failure.
 *
 * This repository resolves each application independently and keeps the reasons apart:
 *  - GRANTED / DENIED / NOT_REQUESTED come from the flag the *service* stores per uid;
 *  - UNKNOWN means the service answered but that specific uid could not be resolved;
 *  - NOT_INSTALLED comes from local history when an authorised package disappears;
 * and [AuthorizationSnapshot.diagnostic] records what the service actually reported so a
 * failure can never again be rendered as the number zero.
 */
enum class AuthorizationState { GRANTED, DENIED, NOT_REQUESTED, UNKNOWN, NOT_INSTALLED }

data class AuthorizedApp(
    val packageName: String,
    val uid: Int,
    val label: String,
    val icon: Drawable?,
    val state: AuthorizationState,
    val note: String? = null
)

data class AuthorizationSnapshot(
    val apps: List<AuthorizedApp>,
    val grantedCount: Int,
    val deniedCount: Int,
    val unknownCount: Int,
    val missingCount: Int,
    val serviceReportedCount: Int,
    /** Installed apps that actually request the Shizuku permission. -1 when unreadable. */
    val candidateCount: Int,
    /** True when the package query works but the service listed nothing to report. */
    val reportingAnomaly: Boolean,
    val diagnostic: String
)

sealed interface AuthLoadResult {
    data class Ok(val snapshot: AuthorizationSnapshot) : AuthLoadResult
    data class Failure(val message: String, val detail: String?) : AuthLoadResult
}

object AuthorizationRepository {

    private const val FLAG_ALLOWED = 1 shl 1
    private const val FLAG_DENIED = 1 shl 2
    private const val MASK_PERMISSION = FLAG_ALLOWED or FLAG_DENIED

    suspend fun load(context: Context, includeHistory: Boolean = true): AuthLoadResult =
        withContext(Dispatchers.IO) {
            if (!ShizukuStateMachine.isRunning()) {
                return@withContext AuthLoadResult.Failure(
                    "The Shizuku service is not running.",
                    "Start the service, then reload this list."
                )
            }

            val packages = try {
                AuthorizationManager.getPackages(exclude = listOf(context.packageName))
            } catch (e: Throwable) {
                return@withContext AuthLoadResult.Failure(
                    "The service did not return an application list.",
                    "${e.javaClass.simpleName}: ${e.message}"
                )
            }

            // Independent cross-check, straight from the package manager rather than from
            // Shizuku's own bookkeeping: how many apps does Android itself report as holding
            // the Shizuku permission? If that number is above zero while the service reports
            // nothing, the list is broken (the Android 17 / One UI 9.0 symptom) and must be
            // shown as a reporting failure instead of a plain "0".
            val candidateCount = systemGrantedCount()

            val pm = context.packageManager
            val entries = ArrayList<AuthorizedApp>(packages.size)
            var granted = 0
            var denied = 0
            var unknown = 0

            for (pi in packages) {
                val ai = pi.applicationInfo ?: continue
                val uid = ai.uid
                val state = try {
                    val flags = Shizuku.getFlagsForUid(uid, MASK_PERMISSION)
                    when {
                        flags and FLAG_ALLOWED != 0 -> AuthorizationState.GRANTED
                        flags and FLAG_DENIED != 0 -> AuthorizationState.DENIED
                        else -> AuthorizationState.NOT_REQUESTED
                    }
                } catch (e: Throwable) {
                    AuthorizationState.UNKNOWN
                }

                when (state) {
                    AuthorizationState.GRANTED -> granted++
                    AuthorizationState.DENIED -> denied++
                    AuthorizationState.UNKNOWN -> unknown++
                    else -> Unit
                }

                entries.add(
                    AuthorizedApp(
                        packageName = pi.packageName,
                        uid = uid,
                        label = labelOf(pm, pi.packageName),
                        icon = iconOf(pm, pi.packageName),
                        state = state,
                        note = if (state == AuthorizationState.UNKNOWN) "The service could not report this app's flags." else null
                    )
                )
            }

            // Packages that were granted before but are not in the service's list now.
            var missing = 0
            if (includeHistory) {
                val known = XionPrefs.authHistory(context)
                val present = entries.map { it.packageName }.toSet()
                known.values
                    .filter { it.wasGranted && it.packageName !in present && it.packageName != BuildConfig.APPLICATION_ID }
                    .sortedByDescending { it.lastSeen }
                    .forEach { history ->
                        missing++
                        entries.add(
                            AuthorizedApp(
                                packageName = history.packageName,
                                uid = -1,
                                label = history.packageName,
                                icon = null,
                                state = AuthorizationState.NOT_INSTALLED,
                                note = "Previously authorised, no longer installed."
                            )
                        )
                    }
                XionPrefs.recordAuthHistory(
                    context,
                    entries.filter { it.state == AuthorizationState.GRANTED }.map { it.packageName },
                    entries.filter { it.state == AuthorizationState.DENIED || it.state == AuthorizationState.NOT_REQUESTED }
                        .map { it.packageName }
                )
            }

            val sorted = entries.sortedWith(
                compareBy(
                    { it.state != AuthorizationState.GRANTED },
                    { it.state == AuthorizationState.NOT_INSTALLED },
                    { it.label.lowercase() }
                )
            )

            AuthLoadResult.Ok(
                AuthorizationSnapshot(
                    apps = sorted,
                    grantedCount = granted,
                    deniedCount = denied,
                    unknownCount = unknown,
                    missingCount = missing,
                    serviceReportedCount = packages.size,
                    candidateCount = candidateCount,
                    reportingAnomaly = packages.isEmpty() && candidateCount > 0,
                    diagnostic = buildString {
                        appendLine("service running: ${ShizukuStateMachine.isRunning()}")
                        appendLine("service uid: ${runCatching { Shizuku.getUid() }.getOrDefault(-1)}")
                        appendLine("api version: ${runCatching { Shizuku.getVersion() }.getOrDefault(-1)}")
                        appendLine("server patch: ${runCatching { Shizuku.getServerPatchVersion() }.getOrDefault(-1)}")
                        appendLine("applications reported by service: ${packages.size}")
                        appendLine("apps Android reports as holding the permission: $candidateCount")
                        appendLine("authorized: $granted / denied: $denied / unresolved: $unknown")
                        appendLine("remembered but absent: $missing")
                        if (packages.isEmpty() && candidateCount > 0) {
                            append("anomaly: $candidateCount app(s) hold the permission but the service listed none")
                        } else {
                            append("reporting looks consistent")
                        }
                    }
                )
            )
        }

    suspend fun revoke(packageName: String, uid: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            AuthorizationManager.revoke(packageName, uid)
            true
        }.getOrDefault(false)
    }

    suspend fun grant(packageName: String, uid: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            AuthorizationManager.grant(packageName, uid)
            true
        }.getOrDefault(false)
    }

    /** The client-facing permission name, owned by the official Shizuku installation. */
    private const val SHIZUKU_PERMISSION = "moe.shizuku.manager.permission.API_V23"

    private suspend fun systemGrantedCount(): Int {
        if (PrivilegedShell.isAvailable()) {
            val result = PrivilegedShell.run(
                "dumpsys package 2>/dev/null | grep -c '$SHIZUKU_PERMISSION: granted=true'", 30_000
            )
            result.stdout.trim().toIntOrNull()?.let { return it }
        }
        // Fallback when no service is available: count the packages that declare the
        // permission locally. Visibility restrictions may make this lower than reality.
        return runCatching {
            ShizukuSystemApis.getInstalledPackages(PackageManager.GET_PERMISSIONS.toLong(), 0)
                .count { it.requestedPermissions?.contains(SHIZUKU_PERMISSION) == true }
        }.getOrDefault(-1)
    }

    private fun labelOf(pm: PackageManager, packageName: String): String = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    private fun iconOf(pm: PackageManager, packageName: String): Drawable? = runCatching {
        pm.getApplicationIcon(packageName)
    }.getOrNull()
}
