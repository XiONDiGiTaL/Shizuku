package moe.shizuku.manager.xion.shell

import android.content.pm.PackageManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.manager.utils.ShizukuStateMachine
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess

/**
 * Runs shell commands through the Shizuku service. The service executes as the shell user
 * (uid 2000) unless it was started as root, so the privilege level reported here is real:
 * [uid] comes from the running service, not from a guess.
 */
object PrivilegedShell {

    data class CommandResult(
        val command: String,
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val durationMs: Long
    ) {
        val ok: Boolean get() = exitCode == 0
        val combined: String
            get() = buildString {
                if (stdout.isNotBlank()) append(stdout.trimEnd())
                if (stderr.isNotBlank()) {
                    if (isNotEmpty()) append('\n')
                    append(stderr.trimEnd())
                }
                if (isEmpty()) append("(no output)")
            }
    }

    /** Privilege level of the running service, or -1 when there is none. */
    fun serviceUid(): Int {
        if (!ShizukuStateMachine.isRunning()) return -1
        return runCatching { Shizuku.getUid() }.getOrDefault(-1)
    }

    fun isRoot(): Boolean = serviceUid() == 0

    /**
     * Availability is about the service, not about this app's runtime permission: the service
     * always trusts its own manager (manager calls bypass the client permission check), and the
     * server executes commands as its own uid. If a call is ever refused, [run] returns the
     * error instead of pretending it worked.
     */
    fun isAvailable(): Boolean = ShizukuStateMachine.isRunning()

    suspend fun run(command: String, timeoutMs: Long = 25_000): CommandResult =
        withContext(Dispatchers.IO) {
            val started = System.nanoTime()
            fun elapsed() = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

            if (!isAvailable()) {
                return@withContext CommandResult(
                    command, -1, "", "The Shizuku service is not running.", elapsed()
                )
            }

            var process: Process? = null
            try {
                process = newProcess(
                    arrayOf("/system/bin/sh", "-c", command),
                    null,
                    null
                )
                val stdout = StringBuilder()
                val stderr = StringBuilder()

                val outReader = Thread {
                    runCatching { process.inputStream.bufferedReader().use { it.copyTo(StringBuilderWriter(stdout)) } }
                }
                val errReader = Thread {
                    runCatching { process.errorStream.bufferedReader().use { it.copyTo(StringBuilderWriter(stderr)) } }
                }
                outReader.start()
                errReader.start()

                // A remote process does not implement the timed Process.waitFor overload; the
                // Shizuku API exposes waitForTimeout for exactly this, and only after it reports
                // the process as gone is exitValue() readable.
                val finished = (process as ShizukuRemoteProcess).waitForTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                if (!finished) {
                    runCatching { process.destroy() }
                    outReader.join(500)
                    errReader.join(500)
                    return@withContext CommandResult(
                        command, -1, stdout.toString(), "Timed out after ${timeoutMs}ms", elapsed()
                    )
                }
                outReader.join(4000)
                errReader.join(4000)
                CommandResult(command, process.exitValue(), stdout.toString(), stderr.toString(), elapsed())
            } catch (e: Throwable) {
                CommandResult(command, -1, "", e.message ?: e.javaClass.simpleName, elapsed())
            } finally {
                runCatching { process?.destroy() }
            }
        }

    /**
     * The process API is not public in every Shizuku API revision this fork can build against.
     * The class ships inside this APK, so reflection is not subject to hidden-API restrictions.
     */
    private fun newProcess(command: Array<String>, env: Array<String>?, dir: String?): Process {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        method.isAccessible = true
        return method.invoke(null, command, env, dir) as Process
    }

    private class StringBuilderWriter(private val sb: StringBuilder) : java.io.Writer() {
        override fun write(cbuf: CharArray, off: Int, len: Int) {
            sb.append(cbuf, off, len)
        }
        override fun flush() {}
        override fun close() {}
    }
}
