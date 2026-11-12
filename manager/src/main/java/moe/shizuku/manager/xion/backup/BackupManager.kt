package moe.shizuku.manager.xion.backup

import android.content.Context
import android.net.Uri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.xion.XionPrefs
import org.json.JSONObject

/**
 * Exports the configuration this app actually owns: its own preferences (watchdog tuning,
 * startup preferences, UI state, histories). It cannot export Shizuku authorisations —
 * those live in the service's own store and are not readable by the manager.
 */
object BackupManager {

    private const val FORMAT_VERSION = 1

    suspend fun export(context: Context): File = withContext(Dispatchers.IO) {
        val root = JSONObject()
        root.put("format", FORMAT_VERSION)
        root.put("package", BuildConfig.APPLICATION_ID)
        root.put("version", BuildConfig.VERSION_NAME)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("settings", XionPrefs.exportAll(context))
        root.put("restartHistory", XionPrefs.restartHistory(context).let { events ->
            org.json.JSONArray().also { array ->
                events.forEach {
                    array.put(
                        JSONObject()
                            .put("t", it.timestamp)
                            .put("reason", it.reason)
                            .put("attempt", it.attempt)
                            .put("success", it.success)
                    )
                }
            }
        })

        val file = File(context.cacheDir, "shizuku-reimagined-config.json")
        file.writeText(root.toString(2))
        file
    }

    suspend fun importFrom(context: Context, uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val text = context.contentResolver.openInputStream(uri)?.use { it.reader().readText() }
                ?: error("The selected file could not be read.")
            val root = JSONObject(text)
            val settings = root.optJSONObject("settings")
                ?: error("This file is not a Shizuku Reimagined configuration export.")
            XionPrefs.importAll(context, settings)

            root.optJSONArray("restartHistory")?.let { array ->
                XionPrefs.clearRestartHistory(context)
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    XionPrefs.addRestartEvent(
                        context,
                        XionPrefs.RestartEvent(
                            o.optLong("t"),
                            o.optString("reason"),
                            o.optInt("attempt"),
                            o.optBoolean("success")
                        )
                    )
                }
            }
            settings.length()
        }
    }
}
