package moe.shizuku.manager.starter

import androidx.lifecycle.asFlow
import java.io.File
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.ShizukuApplication
import moe.shizuku.manager.utils.ShizukuStateMachine

private val app = ShizukuApplication.application

object Starter {

    private val starterFile = File(app.applicationInfo.nativeLibraryDir, "libshizuku.so")

    /**
     * The development identity runs beside the official Shizuku, so its server must not use
     * the shared "shizuku_server" process name: the native starter kills every process with
     * that name before starting, which would terminate the user's working installation.
     */
    private val serverName = if (BuildConfig.DEV_IDENTITY) "xion_shizuku_server" else "shizuku_server"

    val userCommand: String = "${starterFile.absolutePath} --server-name=$serverName"
    val internalCommand = "$userCommand --apk=${app.applicationInfo.sourceDir}"

    /**
     * Official Shizuku ships a `start.sh` next to its external files directory and tells people to
     * run it with `adb shell sh <path>`. This build writes the same kind of script, so the command
     * is one line with no placeholders, no line continuations (which PowerShell does not accept),
     * and no version number that changes with every release.
     *
     * The app-private external directory is world-readable by `adb shell`, and the two paths baked
     * in here change on every install or update, which is why the script is rewritten per process.
     */
    private val scriptFile: File? by lazy { writeScript() }

    /** Absolute path of the generated script, or null when external storage is unavailable. */
    val adbScriptPath: String?
        get() = scriptFile?.absolutePath

    /** The command to copy into PowerShell, cmd or a POSIX shell. */
    val adbCommand: String
        get() = scriptFile?.let { "adb shell sh ${it.absolutePath}" }
            ?: "adb shell $internalCommand"

    /** Writes the script early so it exists before somebody goes looking for it. */
    fun ensureAdbScript() {
        scriptFile
    }

    private fun writeScript(): File? = runCatching {
        val dir = app.getExternalFilesDir(null)?.parentFile ?: return null
        val file = File(dir, "start.sh")
        file.writeText(
            "#!/system/bin/sh\n" +
                "# Shizuku Reimagined: starts the service from a computer.\n" +
                "# adb shell sh ${file.absolutePath}\n" +
                "exec \"${starterFile.absolutePath}\" --server-name=$serverName --apk=\"${app.applicationInfo.sourceDir}\"\n"
        )
        file.setReadable(true, false)
        file.setExecutable(true, false)
        file
    }.getOrNull()

    val serviceStartedMessage = "Service started, this window will be automatically closed in 3 seconds"

    suspend fun waitForBinder(log: ((String) -> Unit)? = null) {
        try {
            log?.invoke("\nWaiting for service. This may take up to 1 minute...")
            withTimeout(60_000) {
                ShizukuStateMachine.asFlow()
                    .first { it == ShizukuStateMachine.State.RUNNING }
            }
            log?.invoke(serviceStartedMessage)
        } catch (e: TimeoutCancellationException) {
            throw TimeoutException("Failed to receive binder within 1 minute")
        }
    }

}
