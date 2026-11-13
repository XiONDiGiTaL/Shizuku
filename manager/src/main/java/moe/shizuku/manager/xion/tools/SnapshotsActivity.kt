package moe.shizuku.manager.xion.tools

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.ActivityXionSnapshotsBinding
import moe.shizuku.manager.databinding.ItemXionSnapshotBinding
import moe.shizuku.manager.xion.XionActivity
import moe.shizuku.manager.xion.XionPrefs
import moe.shizuku.manager.xion.XionUi.applyScreenInsets
import moe.shizuku.manager.xion.diag.DiagnosticsCollector
import moe.shizuku.manager.xion.shell.PrivilegedShell
import org.json.JSONArray
import org.json.JSONObject

/**
 * Named snapshots of the state this platform is allowed to change: the supported settings, the AppOps
 * modes of user installed packages, and whether those packages are enabled.
 *
 * Restoring always previews the difference first, reads the current value of everything before it
 * writes, and verifies each write afterwards. Items that a snapshot cannot put back are listed by name
 * instead of being counted as restored.
 */
class SnapshotsActivity : XionActivity() {

    data class SettingValue(val namespace: String, val key: String, val value: String?)

    data class OpValue(val pkg: String, val op: String, val mode: String)

    data class PkgState(val pkg: String, val enabled: Boolean)

    data class Snapshot(
        val name: String,
        val at: Long,
        val settings: List<SettingValue>,
        val ops: List<OpValue>,
        val packages: List<PkgState>
    )

    private lateinit var binding: ActivityXionSnapshotsBinding
    private val adapter = SnapshotAdapter { showActions(it) }
    private val snapshots = ArrayList<Snapshot>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityXionSnapshotsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyScreenInsets(binding.root, extraTop = 4, extraBottom = 8)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnNew.setOnClickListener { askName() }
        binding.snapshotList.layoutManager = LinearLayoutManager(this)
        binding.snapshotList.adapter = adapter

        reload()
    }

    private fun askName() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(defaultName())
            setSelection(text.length)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.xion_snapshots_name_title)
            .setView(input)
            .setPositiveButton(R.string.xion_snapshots_capture) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty().ifEmpty { defaultName() }
                capture(name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun defaultName(): String =
        "Snapshot " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

    private fun capture(name: String) {
        if (!PrivilegedShell.isAvailable()) {
            Toast.makeText(this, R.string.xion_snapshots_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            binding.btnNew.isEnabled = false
            binding.statusNote.setText(R.string.xion_snapshots_reading_settings)

            val settings = KEYS.map { (namespace, key) ->
                SettingValue(namespace, key, readSetting(namespace, key))
            }

            binding.statusNote.setText(R.string.xion_snapshots_reading_packages)
            val thirdParty = listThirdParty()
            val disabled = listDisabled(thirdParty)
            val packages = thirdParty.sorted().map { PkgState(it, it !in disabled) }

            val ops = ArrayList<OpValue>()
            thirdParty.sorted().forEachIndexed { index, pkg ->
                binding.statusNote.text = getString(R.string.xion_snapshots_reading_ops, index + 1, thirdParty.size)
                readOps(pkg).forEach { (op, mode) -> ops.add(OpValue(pkg, op, mode)) }
            }

            val snapshot = Snapshot(name, System.currentTimeMillis(), settings, ops, packages)
            persist(listOf(snapshot) + snapshots)
            reload()
            binding.statusNote.setText(R.string.xion_snapshots_note)
            binding.btnNew.isEnabled = true
            Toast.makeText(
                this@SnapshotsActivity,
                getString(R.string.xion_snapshots_saved, name),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showActions(snapshot: Snapshot) {
        val options = arrayOf(
            getString(R.string.xion_snapshots_restore),
            getString(R.string.xion_snapshots_delete)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(snapshot.name)
            .setItems(options) { _, which ->
                if (which == 0) preview(snapshot) else confirmDelete(snapshot)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun preview(snapshot: Snapshot) {
        lifecycleScope.launch {
            binding.statusNote.setText(R.string.xion_snapshots_comparing)
            val lines = ArrayList<String>()
            var changes = 0

            snapshot.settings.forEach { stored ->
                val current = readSetting(stored.namespace, stored.key)
                if (current != stored.value) {
                    changes++
                    lines.add("${stored.key}: ${label(current)} -> ${label(stored.value)}")
                }
            }

            val currentDisabled = listDisabled()
            snapshot.packages.forEach { stored ->
                val isEnabled = stored.pkg !in currentDisabled
                if (isEnabled != stored.enabled) {
                    changes++
                    lines.add(
                        getString(
                            R.string.xion_snapshots_package_line,
                            stored.pkg,
                            getString(if (isEnabled) R.string.xion_snapshots_state_enabled else R.string.xion_snapshots_state_disabled),
                            getString(if (stored.enabled) R.string.xion_snapshots_state_enabled else R.string.xion_snapshots_state_disabled)
                        )
                    )
                }
            }

            val opChanges = ArrayList<String>()
            // One dump per package: a per operation call would be thousands of shell round trips.
            snapshot.ops.groupBy { it.pkg }.forEach { (pkg, stored) ->
                val current = readOps(pkg)
                stored.forEach { op ->
                    val now = current[op.op] ?: "default"
                    if (now != op.mode) {
                        opChanges.add("$pkg ${op.op}: ${label(now)} -> ${op.mode}")
                    }
                }
            }
            changes += opChanges.size

            binding.statusNote.setText(R.string.xion_snapshots_note)

            if (changes == 0) {
                Toast.makeText(this@SnapshotsActivity, R.string.xion_snapshots_no_changes, Toast.LENGTH_LONG).show()
                return@launch
            }

            val shown = lines + opChanges.take(MAX_PREVIEW_LINES - lines.size)
            val text = buildString {
                append(resources.getQuantityString(R.plurals.xion_snapshots_changes, changes, changes))
                append("\n\n")
                append(shown.joinToString("\n"))
                if (changes > shown.size) {
                    append("\n")
                    append(getString(R.string.xion_snapshots_more, changes - shown.size))
                }
            }

            MaterialAlertDialogBuilder(this@SnapshotsActivity)
                .setTitle(getString(R.string.xion_snapshots_preview_title, snapshot.name))
                .setMessage(text)
                .setPositiveButton(R.string.xion_snapshots_restore) { _, _ -> restore(snapshot) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun restore(snapshot: Snapshot) {
        lifecycleScope.launch {
            binding.btnNew.isEnabled = false
            binding.statusNote.setText(R.string.xion_snapshots_restoring)
            val failures = ArrayList<String>()
            var restored = 0

            snapshot.settings.forEach { stored ->
                val current = readSetting(stored.namespace, stored.key)
                if (current == stored.value) return@forEach
                PrivilegedShell.run(
                    if (stored.value == null) {
                        "settings delete ${stored.namespace} ${stored.key}"
                    } else {
                        "settings put ${stored.namespace} ${stored.key} ${stored.value}"
                    }
                )
                if (readSetting(stored.namespace, stored.key) == stored.value) restored++ else failures.add(stored.key)
            }

            val currentDisabled = listDisabled()
            snapshot.packages.forEach { stored ->
                val isEnabled = stored.pkg !in currentDisabled
                if (isEnabled == stored.enabled) return@forEach
                PrivilegedShell.run(
                    if (stored.enabled) "pm enable ${stored.pkg}" else "pm disable-user --user 0 ${stored.pkg}"
                )
                val nowEnabled = stored.pkg !in listDisabled()
                if (nowEnabled == stored.enabled) restored++ else failures.add(stored.pkg)
            }

            snapshot.ops.groupBy { it.pkg }.forEach { (pkg, stored) ->
                val current = readOps(pkg)
                stored.forEach { op ->
                    val now = current[op.op] ?: "default"
                    if (now == op.mode) return@forEach
                    PrivilegedShell.run("cmd appops set $pkg ${op.op} ${op.mode}")
                    if (readOp(pkg, op.op) == op.mode) restored++ else failures.add("$pkg ${op.op}")
                }
            }

            binding.btnNew.isEnabled = true
            binding.statusNote.setText(R.string.xion_snapshots_note)

            val title = if (failures.isEmpty()) {
                getString(R.string.xion_snapshots_restored_title)
            } else {
                resources.getQuantityString(R.plurals.xion_snapshots_restored_partial, failures.size, failures.size)
            }
            val message = buildString {
                append(resources.getQuantityString(R.plurals.xion_snapshots_restored_count, restored, restored))
                if (failures.isNotEmpty()) {
                    append("\n\n")
                    append(failures.take(MAX_FAILURES_SHOWN).joinToString("\n"))
                    if (failures.size > MAX_FAILURES_SHOWN) {
                        append("\n")
                        append(getString(R.string.xion_snapshots_more, failures.size - MAX_FAILURES_SHOWN))
                    }
                }
            }
            MaterialAlertDialogBuilder(this@SnapshotsActivity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun confirmDelete(snapshot: Snapshot) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.xion_snapshots_delete_title, snapshot.name))
            .setMessage(R.string.xion_snapshots_delete_msg)
            .setPositiveButton(R.string.xion_snapshots_delete) { _, _ ->
                persist(snapshots.filterNot { it.at == snapshot.at && it.name == snapshot.name })
                reload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun reload() {
        snapshots.clear()
        snapshots.addAll(loadSnapshots())
        adapter.submitList(snapshots.toList())
        binding.statusNote.setText(if (snapshots.isEmpty()) R.string.xion_snapshots_empty else R.string.xion_snapshots_note)
    }

    private suspend fun readSetting(namespace: String, key: String): String? {
        val result = PrivilegedShell.run("settings get $namespace $key")
        if (!result.ok) return null
        val value = result.stdout.trim()
        return if (value.isEmpty() || value == "null") null else value
    }

    private suspend fun readOp(pkg: String, op: String): String? {
        val result = PrivilegedShell.run("cmd appops get $pkg $op")
        if (!result.ok) return null
        val text = result.stdout.trim()
        if (text.isEmpty() || text.startsWith("No operations", ignoreCase = true)) return "default"
        val line = text.lineSequence().firstOrNull { it.contains("$op:") } ?: return "default"
        return Regex(":\\s*([a-z]+)").find(line)?.groupValues?.get(1)
    }

    private suspend fun readOps(pkg: String): Map<String, String> {
        val result = PrivilegedShell.run("cmd appops get $pkg")
        if (!result.ok) return emptyMap()
        val modes = LinkedHashMap<String, String>()
        result.stdout.lineSequence().forEach { raw ->
            val line = raw.trim().removePrefix("Uid mode:").trim()
            val match = Regex("^([A-Za-z][A-Za-z0-9_.]*):\\s*([a-z]+)").find(line) ?: return@forEach
            val mode = match.groupValues[2]
            // "ignore" is the baseline Android applies to most operations and cannot be written per
            // package, so recording thousands of them would only bloat the snapshot.
            if (mode != "default" && mode != "ignore") modes[match.groupValues[1]] = mode
        }
        return modes
    }

    private suspend fun listThirdParty(): Set<String> =
        packageList("pm list packages -3")

    private suspend fun listDisabled(only: Set<String>? = null): Set<String> {
        val all = packageList("pm list packages -d")
        return if (only == null) all else all.filter { it in only }.toSet()
    }

    private suspend fun packageList(command: String): Set<String> {
        val result = PrivilegedShell.run(command)
        if (!result.ok) return emptySet()
        return result.stdout.lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("package:")) trimmed.removePrefix("package:") else null
            }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun label(value: String?): String = value ?: getString(R.string.xion_snapshots_unset)

    private fun loadSnapshots(): List<Snapshot> {
        val raw = XionPrefs.get(this).getString(KEY_SNAPSHOTS, "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val json = array.optJSONObject(index) ?: return@mapNotNull null
                val settingsJson = json.optJSONArray("settings") ?: JSONArray()
                val opsJson = json.optJSONArray("ops") ?: JSONArray()
                val packagesJson = json.optJSONArray("packages") ?: JSONArray()
                Snapshot(
                    json.optString("name"),
                    json.optLong("at"),
                    (0 until settingsJson.length()).mapNotNull { i ->
                        settingsJson.optJSONObject(i)?.let { item ->
                            SettingValue(
                                item.optString("namespace"),
                                item.optString("key"),
                                item.optString("value").takeIf { it.isNotEmpty() && it != "null" }
                            )
                        }
                    },
                    (0 until opsJson.length()).mapNotNull { i ->
                        opsJson.optJSONObject(i)?.let { item ->
                            OpValue(item.optString("pkg"), item.optString("op"), item.optString("mode"))
                        }
                    },
                    (0 until packagesJson.length()).mapNotNull { i ->
                        packagesJson.optJSONObject(i)?.let { item ->
                            PkgState(item.optString("pkg"), item.optBoolean("enabled", true))
                        }
                    }
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun persist(list: List<Snapshot>) {
        val array = JSONArray()
        list.take(MAX_SNAPSHOTS).forEach { snapshot ->
            val settingsJson = JSONArray()
            snapshot.settings.forEach { setting ->
                settingsJson.put(
                    JSONObject().apply {
                        put("namespace", setting.namespace)
                        put("key", setting.key)
                        put("value", setting.value ?: "null")
                    }
                )
            }
            val opsJson = JSONArray()
            snapshot.ops.forEach { op ->
                opsJson.put(
                    JSONObject().apply {
                        put("pkg", op.pkg)
                        put("op", op.op)
                        put("mode", op.mode)
                    }
                )
            }
            val packagesJson = JSONArray()
            snapshot.packages.forEach { state ->
                packagesJson.put(
                    JSONObject().apply {
                        put("pkg", state.pkg)
                        put("enabled", state.enabled)
                    }
                )
            }
            array.put(
                JSONObject().apply {
                    put("name", snapshot.name)
                    put("at", snapshot.at)
                    put("settings", settingsJson)
                    put("ops", opsJson)
                    put("packages", packagesJson)
                }
            )
        }
        XionPrefs.get(this).edit().putString(KEY_SNAPSHOTS, array.toString()).apply()
    }

    private class SnapshotAdapter(private val onClick: (Snapshot) -> Unit) :
        ListAdapter<Snapshot, SnapshotAdapter.Holder>(Diff) {

        class Holder(val binding: ItemXionSnapshotBinding) : RecyclerView.ViewHolder(binding.root)

        object Diff : DiffUtil.ItemCallback<Snapshot>() {
            override fun areItemsTheSame(oldItem: Snapshot, newItem: Snapshot) =
                oldItem.at == newItem.at && oldItem.name == newItem.name

            override fun areContentsTheSame(oldItem: Snapshot, newItem: Snapshot) = oldItem == newItem
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemXionSnapshotBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val snapshot = getItem(position)
            val context = holder.itemView.context
            holder.binding.snapshotName.text = snapshot.name
            holder.binding.snapshotMeta.text = context.getString(
                R.string.xion_snapshots_meta,
                snapshot.settings.size,
                snapshot.packages.size,
                snapshot.ops.size,
                DiagnosticsCollector.Format.timeAgo(snapshot.at)
            )
            holder.binding.root.setOnClickListener { onClick(snapshot) }
        }
    }

    private companion object {
        const val KEY_SNAPSHOTS = "snapshots"
        const val MAX_SNAPSHOTS = 5
        const val MAX_PREVIEW_LINES = 40
        const val MAX_FAILURES_SHOWN = 15

        val KEYS = listOf(
            "global" to "window_animation_scale",
            "global" to "transition_animation_scale",
            "global" to "animator_duration_scale",
            "system" to "screen_off_timeout"
        )
    }
}
