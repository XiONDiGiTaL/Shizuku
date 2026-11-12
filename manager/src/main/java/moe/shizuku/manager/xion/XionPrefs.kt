package moe.shizuku.manager.xion

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Settings owned by the new UI. Stored separately from [moe.shizuku.manager.ShizukuSettings]
 * so the classic screens keep working unchanged.
 */
object XionPrefs {

    const val NAME = "xion_settings"

    private const val KEY_WATCHDOG_MAX_RESTARTS = "watchdog_max_restarts"
    private const val KEY_WATCHDOG_BACKOFF = "watchdog_backoff_seconds"
    private const val KEY_WATCHDOG_INTERVAL = "watchdog_health_interval_seconds"
    private const val KEY_AUTH_HISTORY = "auth_history"
    private const val KEY_RESTART_HISTORY = "watchdog_restart_history"
    private const val KEY_SETUP_DONE = "setup_done"
    private const val KEY_LAST_STARTED_AT = "last_started_at"
    private const val KEY_LAST_STOPPED_AT = "last_stopped_at"
    private const val KEY_LAST_CRASHED_AT = "last_crashed_at"

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    // --- Watchdog tuning -------------------------------------------------

    fun watchdogMaxRestarts(context: Context) =
        get(context).getInt(KEY_WATCHDOG_MAX_RESTARTS, 3)

    fun setWatchdogMaxRestarts(context: Context, value: Int) =
        get(context).edit().putInt(KEY_WATCHDOG_MAX_RESTARTS, value.coerceIn(1, 10)).apply()

    fun watchdogBackoffSeconds(context: Context) =
        get(context).getInt(KEY_WATCHDOG_BACKOFF, 15)

    fun setWatchdogBackoffSeconds(context: Context, value: Int) =
        get(context).edit().putInt(KEY_WATCHDOG_BACKOFF, value.coerceIn(5, 300)).apply()

    fun watchdogHealthIntervalSeconds(context: Context) =
        get(context).getInt(KEY_WATCHDOG_INTERVAL, 60)

    fun setWatchdogHealthIntervalSeconds(context: Context, value: Int) =
        get(context).edit().putInt(KEY_WATCHDOG_INTERVAL, value.coerceIn(15, 600)).apply()

    // --- Timestamps ------------------------------------------------------

    fun markStarted(context: Context) =
        get(context).edit().putLong(KEY_LAST_STARTED_AT, System.currentTimeMillis()).apply()

    fun markStopped(context: Context) =
        get(context).edit().putLong(KEY_LAST_STOPPED_AT, System.currentTimeMillis()).apply()

    fun markCrashed(context: Context) =
        get(context).edit().putLong(KEY_LAST_CRASHED_AT, System.currentTimeMillis()).apply()

    fun lastStartedAt(context: Context) = get(context).getLong(KEY_LAST_STARTED_AT, 0L)
    fun lastStoppedAt(context: Context) = get(context).getLong(KEY_LAST_STOPPED_AT, 0L)
    fun lastCrashedAt(context: Context) = get(context).getLong(KEY_LAST_CRASHED_AT, 0L)

    // --- Setup -----------------------------------------------------------

    fun isSetupDone(context: Context) = get(context).getBoolean(KEY_SETUP_DONE, false)
    fun setSetupDone(context: Context, done: Boolean) =
        get(context).edit().putBoolean(KEY_SETUP_DONE, done).apply()

    // --- Restart history -------------------------------------------------

    data class RestartEvent(val timestamp: Long, val reason: String, val attempt: Int, val success: Boolean)

    fun restartHistory(context: Context): List<RestartEvent> {
        val raw = get(context).getString(KEY_RESTART_HISTORY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                RestartEvent(
                    o.optLong("t"),
                    o.optString("reason"),
                    o.optInt("attempt"),
                    o.optBoolean("success")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun addRestartEvent(context: Context, event: RestartEvent) {
        val list = (restartHistory(context) + event).takeLast(50)
        val array = JSONArray()
        list.forEach {
            array.put(
                JSONObject()
                    .put("t", it.timestamp)
                    .put("reason", it.reason)
                    .put("attempt", it.attempt)
                    .put("success", it.success)
            )
        }
        get(context).edit().putString(KEY_RESTART_HISTORY, array.toString()).apply()
    }

    fun clearRestartHistory(context: Context) =
        get(context).edit().remove(KEY_RESTART_HISTORY).apply()

    // --- Authorization history ------------------------------------------
    // The service only reports installed packages, so an app that was authorised and then
    // uninstalled disappears without a trace. We remember what we have seen granted so the
    // UI can say "no longer installed" instead of silently dropping it.

    data class AuthHistoryEntry(val packageName: String, val lastSeen: Long, val wasGranted: Boolean)

    fun authHistory(context: Context): Map<String, AuthHistoryEntry> {
        val raw = get(context).getString(KEY_AUTH_HISTORY, null) ?: return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { key ->
                val e = o.getJSONObject(key)
                AuthHistoryEntry(key, e.optLong("lastSeen"), e.optBoolean("granted"))
            }
        }.getOrDefault(emptyMap())
    }

    fun recordAuthHistory(context: Context, granted: Collection<String>, denied: Collection<String>) {
        val now = System.currentTimeMillis()
        val map = authHistory(context).toMutableMap()
        granted.forEach { map[it] = AuthHistoryEntry(it, now, true) }
        denied.forEach { pkg ->
            val existing = map[pkg]
            map[pkg] = AuthHistoryEntry(pkg, now, existing?.wasGranted ?: false)
        }
        val o = JSONObject()
        map.values.forEach {
            o.put(
                it.packageName,
                JSONObject().put("lastSeen", it.lastSeen).put("granted", it.wasGranted)
            )
        }
        get(context).edit().putString(KEY_AUTH_HISTORY, o.toString()).apply()
    }

    fun forgetAuthHistory(context: Context, packageName: String) {
        val map = authHistory(context).toMutableMap()
        map.remove(packageName)
        val o = JSONObject()
        map.values.forEach {
            o.put(
                it.packageName,
                JSONObject().put("lastSeen", it.lastSeen).put("granted", it.wasGranted)
            )
        }
        get(context).edit().putString(KEY_AUTH_HISTORY, o.toString()).apply()
    }

    // --- Backup ----------------------------------------------------------

    fun exportAll(context: Context): JSONObject {
        val json = JSONObject()
        get(context).all.forEach { (key, value) ->
            when (value) {
                is String, is Int, is Long, is Float, is Boolean -> json.put(key, value)
            }
        }
        return json
    }

    fun importAll(context: Context, json: JSONObject) {
        val editor = get(context).edit()
        json.keys().forEach { key ->
            when (val value = json.get(key)) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
            }
        }
        editor.apply()
    }
}
