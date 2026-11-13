package moe.shizuku.manager.xion.tools

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.ActivityXionOptimizeBinding
import moe.shizuku.manager.databinding.ItemXionProfileBinding
import moe.shizuku.manager.xion.XionActivity
import moe.shizuku.manager.xion.XionPrefs
import moe.shizuku.manager.xion.XionUi.applyScreenInsets
import moe.shizuku.manager.xion.diag.DiagnosticsCollector
import moe.shizuku.manager.xion.shell.PrivilegedShell
import org.json.JSONArray
import org.json.JSONObject

/**
 * Device optimization center: a small set of profiles over settings Android exposes to the shell.
 *
 * The screen never applies anything blind. Tapping a profile reads every setting first and shows the
 * current value next to the value that would be written, and applying it verifies each write by
 * reading it back. Every written setting is stored with the value it replaced, so one restore puts the
 * device back exactly where it was. A profile that cannot change a setting reports that setting as
 * failed instead of claiming the profile worked.
 */
class OptimizationCenterActivity : XionActivity() {

    data class Setting(val namespace: String, val key: String, val value: String)

    data class Profile(
        val id: String,
        val name: String,
        val description: String,
        val settings: List<Setting>
    )

    data class Applied(val namespace: String, val key: String, val previous: String, val value: String)

    data class Snapshot(val profileName: String, val at: Long, val items: List<Applied>)

    private lateinit var binding: ActivityXionOptimizeBinding
    private val adapter = ProfileAdapter { preview(it) }

    private val profiles = listOf(
        Profile(
            "balanced", "Balanced",
            "Standard animation speeds and a one minute screen timeout.",
            listOf(
                Setting("global", "window_animation_scale", "1.0"),
                Setting("global", "transition_animation_scale", "1.0"),
                Setting("global", "animator_duration_scale", "1.0"),
                Setting("system", "screen_off_timeout", "60000")
            )
        ),
        Profile(
            "snappy", "Snappy",
            "Half length animations, same one minute screen timeout.",
            listOf(
                Setting("global", "window_animation_scale", "0.5"),
                Setting("global", "transition_animation_scale", "0.5"),
                Setting("global", "animator_duration_scale", "0.5"),
                Setting("system", "screen_off_timeout", "60000")
            )
        ),
        Profile(
            "instant", "Instant",
            "Animations off and a thirty second screen timeout.",
            listOf(
                Setting("global", "window_animation_scale", "0.0"),
                Setting("global", "transition_animation_scale", "0.0"),
                Setting("global", "animator_duration_scale", "0.0"),
                Setting("system", "screen_off_timeout", "30000")
            )
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityXionOptimizeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyScreenInsets(binding.root, extraTop = 4, extraBottom = 8)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRestore.setOnClickListener { confirmRestore() }

        binding.profileList.layoutManager = LinearLayoutManager(this)
        binding.profileList.adapter = adapter
        adapter.submitList(profiles)

        updateSnapshotNote()
    }

    private fun updateSnapshotNote() {
        val snapshot = loadSnapshot()
        if (snapshot == null) {
            binding.lastApplied.isVisible = false
            binding.btnRestore.isEnabled = false
        } else {
            binding.lastApplied.isVisible = true
            binding.lastApplied.text = getString(
                R.string.xion_optimize_last_applied,
                snapshot.profileName,
                DiagnosticsCollector.Format.timeAgo(snapshot.at)
            )
            binding.btnRestore.isEnabled = true
        }
    }

    private fun preview(profile: Profile) {
        if (!PrivilegedShell.isAvailable()) {
            Toast.makeText(this, R.string.xion_optimize_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val lines = profile.settings.map { setting ->
                val current = read(setting)
                "${setting.key}: ${current ?: getString(R.string.xion_optimize_unset)} -> ${setting.value}"
            }
            MaterialAlertDialogBuilder(this@OptimizationCenterActivity)
                .setTitle(getString(R.string.xion_optimize_preview_title, profile.name))
                .setMessage(lines.joinToString("\n\n"))
                .setPositiveButton(R.string.xion_optimize_apply) { _, _ -> applyProfile(profile) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun applyProfile(profile: Profile) {
        lifecycleScope.launch {
            val results = ArrayList<String>()
            val applied = ArrayList<Applied>()

            profile.settings.forEach { setting ->
                val previous = read(setting)
                PrivilegedShell.run("settings put ${setting.namespace} ${setting.key} ${setting.value}")
                val after = read(setting)
                if (after == setting.value) {
                    applied.add(
                        Applied(setting.namespace, setting.key, previous ?: NULL_VALUE, setting.value)
                    )
                    results.add("${setting.key} = ${setting.value}")
                } else {
                    results.add(
                        getString(
                            R.string.xion_optimize_failed_line,
                            setting.key,
                            after ?: getString(R.string.xion_optimize_unset)
                        )
                    )
                }
            }

            if (applied.isNotEmpty()) saveSnapshot(Snapshot(profile.name, System.currentTimeMillis(), applied))
            updateSnapshotNote()

            val title = if (applied.size == profile.settings.size) {
                getString(R.string.xion_optimize_applied_title, profile.name)
            } else {
                getString(R.string.xion_optimize_partial_title, applied.size, profile.settings.size)
            }
            MaterialAlertDialogBuilder(this@OptimizationCenterActivity)
                .setTitle(title)
                .setMessage(results.joinToString("\n"))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun confirmRestore() {
        val snapshot = loadSnapshot() ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.xion_optimize_restore_title)
            .setMessage(
                getString(
                    R.string.xion_optimize_restore_msg,
                    snapshot.profileName,
                    DiagnosticsCollector.Format.timeAgo(snapshot.at)
                )
            )
            .setPositiveButton(R.string.xion_optimize_restore) { _, _ -> restore(snapshot) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun restore(snapshot: Snapshot) {
        lifecycleScope.launch {
            val failed = ArrayList<String>()
            snapshot.items.forEach { item ->
                val command = if (item.previous == NULL_VALUE) {
                    "settings delete ${item.namespace} ${item.key}"
                } else {
                    "settings put ${item.namespace} ${item.key} ${item.previous}"
                }
                PrivilegedShell.run(command)
                val after = read(item.namespace, item.key)
                val expected = item.previous.takeIf { it != NULL_VALUE }
                if (after != expected) failed.add(item.key)
            }

            if (failed.isEmpty()) {
                clearSnapshot()
                Toast.makeText(
                    this@OptimizationCenterActivity,
                    getString(R.string.xion_optimize_restored, snapshot.profileName),
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    this@OptimizationCenterActivity,
                    getString(R.string.xion_optimize_restore_failed, failed.joinToString(", ")),
                    Toast.LENGTH_LONG
                ).show()
            }
            updateSnapshotNote()
        }
    }

    private suspend fun read(setting: Setting): String? = read(setting.namespace, setting.key)

    private suspend fun read(namespace: String, key: String): String? {
        val result = PrivilegedShell.run("settings get $namespace $key")
        if (!result.ok) return null
        val value = result.stdout.trim()
        return if (value.isEmpty() || value == "null") null else value
    }

    private fun saveSnapshot(snapshot: Snapshot) {
        val items = JSONArray()
        snapshot.items.forEach { item ->
            items.put(
                JSONObject().apply {
                    put("namespace", item.namespace)
                    put("key", item.key)
                    put("previous", item.previous)
                    put("value", item.value)
                }
            )
        }
        val json = JSONObject().apply {
            put("profile", snapshot.profileName)
            put("at", snapshot.at)
            put("items", items)
        }
        XionPrefs.get(this).edit().putString(KEY_SNAPSHOT, json.toString()).apply()
    }

    private fun loadSnapshot(): Snapshot? {
        val raw = XionPrefs.get(this).getString(KEY_SNAPSHOT, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val items = json.optJSONArray("items") ?: JSONArray()
            val applied = (0 until items.length()).mapNotNull { index ->
                val item = items.optJSONObject(index) ?: return@mapNotNull null
                Applied(
                    item.optString("namespace"),
                    item.optString("key"),
                    item.optString("previous"),
                    item.optString("value")
                )
            }
            Snapshot(json.optString("profile"), json.optLong("at"), applied)
        }.getOrNull()
    }

    private fun clearSnapshot() {
        XionPrefs.get(this).edit().remove(KEY_SNAPSHOT).apply()
    }

    private class ProfileAdapter(private val onClick: (Profile) -> Unit) :
        ListAdapter<Profile, ProfileAdapter.Holder>(Diff) {

        class Holder(val binding: ItemXionProfileBinding) : RecyclerView.ViewHolder(binding.root)

        object Diff : DiffUtil.ItemCallback<Profile>() {
            override fun areItemsTheSame(oldItem: Profile, newItem: Profile) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Profile, newItem: Profile) = oldItem == newItem
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemXionProfileBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val profile = getItem(position)
            val b = holder.binding
            b.profileName.text = profile.name
            b.profileDescription.text = profile.description
            b.profileMeta.text = profile.settings.joinToString(" · ") { "${it.key}=${it.value}" }
            b.root.setOnClickListener { onClick(profile) }
        }
    }

    private companion object {
        const val KEY_SNAPSHOT = "optimization_snapshot"
        const val NULL_VALUE = "null"
    }
}
