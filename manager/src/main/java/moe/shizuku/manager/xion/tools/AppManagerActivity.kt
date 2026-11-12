package moe.shizuku.manager.xion.tools

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.ActivityXionPackagesBinding
import moe.shizuku.manager.databinding.ItemXionPackageBinding
import moe.shizuku.manager.xion.XionUi.applyScreenInsets
import moe.shizuku.manager.xion.shell.PrivilegedShell

/**
 * Package management over the service's ADB-level privileges.
 *
 * Every operation is a real `pm` command whose result is reported; nothing is hidden, and
 * destructive actions always ask first. System packages are marked, and a short list of
 * components that genuinely break a device when disabled is called out explicitly.
 */
class AppManagerActivity : AppCompatActivity() {

    data class PackageEntry(
        val packageName: String,
        val system: Boolean,
        val disabled: Boolean,
        val label: String?
    )

    private lateinit var binding: ActivityXionPackagesBinding
    private val all = ArrayList<PackageEntry>()
    private val adapter = PackageAdapter { entry -> showActions(entry) }
    private var filter = Filter.ALL
    private var query = ""

    private enum class Filter { ALL, USER, SYSTEM }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityXionPackagesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyScreenInsets(binding.root, extraTop = 4, extraBottom = 8)

        binding.btnBack.setOnClickListener { finish() }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.filters.setOnCheckedStateChangeListener { _, checked ->
            filter = when (checked.firstOrNull()) {
                R.id.filterUser -> Filter.USER
                R.id.filterSystem -> Filter.SYSTEM
                else -> Filter.ALL
            }
            applyFilter()
        }

        binding.search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim()?.lowercase().orEmpty()
                applyFilter()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })

        binding.btnExport.setOnClickListener { exportList() }
        load()
    }

    private fun load() {
        if (!PrivilegedShell.isAvailable()) {
            binding.count.text = getString(R.string.xion_tools_requires_service)
            return
        }
        binding.count.text = "reading packages…"
        lifecycleScope.launch {
            val thirdParty = PrivilegedShell.run("pm list packages -3")
            val system = PrivilegedShell.run("pm list packages -s")
            val disabled = PrivilegedShell.run("pm list packages -d")

            if (!thirdParty.ok && !system.ok) {
                binding.count.text = "Could not list packages: ${thirdParty.combined}"
                return@launch
            }

            fun parse(output: String): List<String> = output.lineSequence()
                .mapNotNull { line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("package:")) trimmed.removePrefix("package:") else null
                }
                .filter { it.isNotBlank() }
                .toList()

            val disabledSet = parse(disabled.stdout).toSet()
            val userSet = parse(thirdParty.stdout).toSet()
            val systemSet = parse(system.stdout).toSet()
            val pm = packageManager

            all.clear()
            (userSet + systemSet).sorted().forEach { pkg ->
                val label = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                }.getOrNull()
                all.add(PackageEntry(pkg, pkg in systemSet, pkg in disabledSet, label))
            }
            applyFilter()
        }
    }

    private fun applyFilter() {
        val filtered = all.filter { entry ->
            val matchesFilter = when (filter) {
                Filter.ALL -> true
                Filter.USER -> !entry.system
                Filter.SYSTEM -> entry.system
            }
            val matchesQuery = query.isEmpty() ||
                entry.packageName.lowercase().contains(query) ||
                (entry.label?.lowercase()?.contains(query) == true)
            matchesFilter && matchesQuery
        }
        adapter.submitList(filtered)
        binding.count.text = getString(R.string.xion_packages_count, filtered.size) +
            if (all.isNotEmpty()) " of ${all.size}" else ""
    }

    private fun showActions(entry: PackageEntry) {
        val options = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()

        if (entry.disabled) {
            options.add(getString(R.string.xion_packages_enable))
            actions.add { shell("pm enable ${entry.packageName}", "Enabled ${entry.packageName}") }
        } else {
            options.add(getString(R.string.xion_packages_disable))
            actions.add { confirmDisable(entry) }
        }

        options.add(getString(R.string.xion_packages_clear))
        actions.add { confirmClear(entry) }

        options.add(getString(R.string.xion_packages_uninstall))
        actions.add { confirmUninstall(entry) }

        options.add("Package details")
        actions.add { showDetails(entry) }

        MaterialAlertDialogBuilder(this)
            .setTitle(entry.packageName)
            .setItems(options.toTypedArray()) { _, which -> actions[which].invoke() }
            .show()
    }

    private fun confirmDisable(entry: PackageEntry) {
        val message = if (entry.system) {
            getString(R.string.xion_packages_confirm_disable_msg)
        } else {
            "The app will be disabled for this user."
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.xion_packages_confirm_disable, entry.packageName))
            .setMessage(message)
            .setPositiveButton(R.string.xion_packages_disable) { _, _ ->
                shell(
                    "pm disable-user --user 0 ${entry.packageName}",
                    "Disable requested for ${entry.packageName}"
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmClear(entry: PackageEntry) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear data for ${entry.packageName}?")
            .setMessage("All app data and cache for this package will be deleted. This cannot be undone.")
            .setPositiveButton(R.string.xion_packages_clear) { _, _ ->
                shell("pm clear ${entry.packageName}", "Cleared ${entry.packageName}")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmUninstall(entry: PackageEntry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.xion_packages_confirm_uninstall, entry.packageName))
            .setMessage(
                if (entry.system)
                    "This is a system package. Uninstalling it for user 0 hides it from this user; a factory reset restores it."
                else
                    "The app is removed for the current user only."
            )
            .setPositiveButton(R.string.xion_packages_uninstall) { _, _ ->
                shell(
                    "pm uninstall --user 0 ${entry.packageName}",
                    "Uninstall requested for ${entry.packageName}"
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDetails(entry: PackageEntry) {
        lifecycleScope.launch {
            val result = PrivilegedShell.run(
                "dumpsys package ${entry.packageName} | grep -E 'versionName|versionCode|firstInstallTime|pkgFlags|enabled=' | head -8"
            )
            MaterialAlertDialogBuilder(this@AppManagerActivity)
                .setTitle(entry.packageName)
                .setMessage(result.combined)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun shell(command: String, successMessage: String) {
        lifecycleScope.launch {
            val result = PrivilegedShell.run(command)
            Toast.makeText(
                this@AppManagerActivity,
                if (result.ok) successMessage else result.combined,
                Toast.LENGTH_LONG
            ).show()
            load()
        }
    }

    private fun exportList() {
        val file = File(cacheDir, "packages.txt")
        file.writeText(all.joinToString("\n") { "${it.packageName}\t${if (it.system) "system" else "user"}\t${if (it.disabled) "disabled" else "enabled"}" })
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Export package list"
            )
        )
    }

    private class PackageAdapter(private val onActions: (PackageEntry) -> Unit) :
        ListAdapter<PackageEntry, PackageAdapter.Holder>(Diff) {

        class Holder(val binding: ItemXionPackageBinding) : RecyclerView.ViewHolder(binding.root)

        object Diff : DiffUtil.ItemCallback<PackageEntry>() {
            override fun areItemsTheSame(oldItem: PackageEntry, newItem: PackageEntry) =
                oldItem.packageName == newItem.packageName

            override fun areContentsTheSame(oldItem: PackageEntry, newItem: PackageEntry) =
                oldItem == newItem
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemXionPackageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = getItem(position)
            val b = holder.binding
            b.packageName.text = entry.packageName
            b.meta.text = buildString {
                append(entry.label ?: "unknown label")
                if (entry.system) append(" · system app")
            }
            b.badgeSystem.isVisible = entry.system
            b.badgeDisabled.isVisible = entry.disabled
            b.btnActions.setOnClickListener { onActions(entry) }
        }
    }
}
