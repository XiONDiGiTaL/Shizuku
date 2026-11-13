package moe.shizuku.manager.xion.tools

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import moe.shizuku.manager.databinding.ActivityXionPermissionsBinding
import moe.shizuku.manager.databinding.ItemXionAppopsPackageBinding
import moe.shizuku.manager.databinding.ItemXionPermissionBinding
import moe.shizuku.manager.xion.XionActivity
import moe.shizuku.manager.xion.XionUi
import moe.shizuku.manager.xion.XionUi.applyScreenInsets
import moe.shizuku.manager.xion.XionUi.applyTone
import moe.shizuku.manager.xion.shell.PrivilegedShell

/**
 * Permission inspector: what each package requests, whether it is granted, and a way to change the
 * ones Android allows to be changed.
 *
 * `dumpsys package` is the source, so the states shown are the package manager's own. Grant and
 * revoke run `pm grant` / `pm revoke`, and the permission is read back afterwards: a change is only
 * reported when the package manager agrees it happened. Install-time permissions are shown but not
 * offered for change, because ADB cannot revoke those.
 */
class PermissionInspectorActivity : XionActivity() {

    data class PackageEntry(val packageName: String, val system: Boolean, val label: String?)

    data class PermissionEntry(
        val name: String,
        val granted: Boolean,
        val runtime: Boolean,
        val flags: String
    )

    private lateinit var binding: ActivityXionPermissionsBinding

    private val packages = ArrayList<PackageEntry>()
    private val packageAdapter = PackageAdapter { openPackage(it) }
    private var filter = Filter.ALL
    private var packageQuery = ""

    private var currentPackage: String? = null
    private var permissions: List<PermissionEntry> = emptyList()
    private var permissionQuery = ""
    private val permissionAdapter = PermissionAdapter { confirmChange(it) }

    private enum class Filter { ALL, USER, SYSTEM }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityXionPermissionsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyScreenInsets(binding.root, extraTop = 4, extraBottom = 8)

        binding.btnBack.setOnClickListener { if (binding.detailScreen.isVisible) showPackages() else finish() }
        binding.btnRefresh.setOnClickListener { refresh() }

        binding.packageList.layoutManager = LinearLayoutManager(this)
        binding.packageList.adapter = packageAdapter
        binding.permissionList.layoutManager = LinearLayoutManager(this)
        binding.permissionList.adapter = permissionAdapter

        binding.filters.setOnCheckedStateChangeListener { _, checked ->
            filter = when (checked.firstOrNull()) {
                R.id.filterUser -> Filter.USER
                R.id.filterSystem -> Filter.SYSTEM
                else -> Filter.ALL
            }
            applyPackageFilter()
        }

        binding.searchPackages.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                packageQuery = s?.toString()?.trim()?.lowercase().orEmpty()
                applyPackageFilter()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })

        binding.searchPermissions.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                permissionQuery = s?.toString()?.trim()?.lowercase().orEmpty()
                applyPermissionFilter()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.detailScreen.isVisible) {
                    showPackages()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        loadPackages()
    }

    private fun refresh() {
        val pkg = currentPackage
        if (pkg != null && binding.detailScreen.isVisible) loadPermissions(pkg) else loadPackages()
    }

    private fun loadPackages() {
        if (!PrivilegedShell.isAvailable()) {
            binding.packageCount.text = getString(R.string.xion_perms_unavailable)
            return
        }
        binding.packageCount.text = getString(R.string.xion_perms_reading_packages)
        lifecycleScope.launch {
            val thirdParty = PrivilegedShell.run("pm list packages -3")
            val system = PrivilegedShell.run("pm list packages -s")
            if (!thirdParty.ok && !system.ok) {
                binding.packageCount.text = getString(R.string.xion_perms_packages_failed, thirdParty.combined)
                return@launch
            }

            fun parse(output: String): List<String> = output.lineSequence()
                .mapNotNull { line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("package:")) trimmed.removePrefix("package:") else null
                }
                .filter { it.isNotBlank() }
                .toList()

            val userSet = parse(thirdParty.stdout).toSet()
            val systemSet = parse(system.stdout).toSet()
            val pm = packageManager

            packages.clear()
            (userSet + systemSet).sorted().forEach { pkg ->
                val label = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                }.getOrNull()
                packages.add(PackageEntry(pkg, pkg in systemSet, label))
            }
            applyPackageFilter()
        }
    }

    private fun applyPackageFilter() {
        val filtered = packages.filter { entry ->
            val matchesFilter = when (filter) {
                Filter.ALL -> true
                Filter.USER -> !entry.system
                Filter.SYSTEM -> entry.system
            }
            val matchesQuery = packageQuery.isEmpty() ||
                entry.packageName.lowercase().contains(packageQuery) ||
                (entry.label?.lowercase()?.contains(packageQuery) == true)
            matchesFilter && matchesQuery
        }
        packageAdapter.submitList(filtered)
        binding.packageCount.text = getString(R.string.xion_perms_packages_count, filtered.size) +
            if (packages.isNotEmpty()) " of ${packages.size}" else ""
    }

    private fun openPackage(entry: PackageEntry) {
        currentPackage = entry.packageName
        permissionQuery = ""
        binding.searchPermissions.setText("")
        binding.listScreen.isVisible = false
        binding.detailScreen.isVisible = true
        binding.screenTitle.setText(R.string.xion_perms_detail_title)
        binding.packageName.text = entry.packageName + (entry.label?.let { " · $it" } ?: "")
        loadPermissions(entry.packageName)
    }

    private fun showPackages() {
        currentPackage = null
        binding.detailScreen.isVisible = false
        binding.listScreen.isVisible = true
        binding.screenTitle.setText(R.string.xion_perms_title)
    }

    private fun loadPermissions(pkg: String) {
        if (!PrivilegedShell.isAvailable()) {
            binding.summary.text = getString(R.string.xion_perms_unavailable)
            return
        }
        binding.summary.text = getString(R.string.xion_perms_reading)
        lifecycleScope.launch {
            val result = PrivilegedShell.run("dumpsys package $pkg")
            if (!result.ok) {
                binding.summary.text = getString(R.string.xion_perms_read_failed, result.combined)
                return@launch
            }
            permissions = parsePermissions(result.stdout)
            if (permissions.isEmpty()) {
                binding.summary.text = getString(R.string.xion_perms_empty)
                permissionAdapter.submitList(emptyList())
                return@launch
            }
            val granted = permissions.count { it.granted }
            binding.summary.text = getString(R.string.xion_perms_summary, permissions.size, granted)
            applyPermissionFilter()
        }
    }

    private fun applyPermissionFilter() {
        val filtered = permissions.filter { permissionQuery.isEmpty() || it.name.lowercase().contains(permissionQuery) }
        permissionAdapter.submitList(filtered)
    }

    private fun confirmChange(entry: PermissionEntry) {
        val pkg = currentPackage ?: return

        if (!entry.runtime) {
            MaterialAlertDialogBuilder(this)
                .setTitle(entry.name)
                .setMessage(R.string.xion_perms_install_time)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val grant = !entry.granted
        val message = if (grant) {
            getString(R.string.xion_perms_grant_msg)
        } else {
            getString(R.string.xion_perms_revoke_msg)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(entry.name)
            .setMessage(message)
            .setPositiveButton(if (grant) R.string.xion_perms_grant else R.string.xion_perms_revoke) { _, _ ->
                lifecycleScope.launch { setAndVerify(pkg, entry.name, grant) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private suspend fun setAndVerify(pkg: String, permission: String, grant: Boolean) {
        val command = if (grant) "pm grant $pkg $permission" else "pm revoke $pkg $permission"
        val write = PrivilegedShell.run(command)
        val read = PrivilegedShell.run("dumpsys package $pkg | grep -F \"$permission: granted=\"")
        val actual = Regex("granted=(true|false)").find(read.stdout)?.groupValues?.get(1)?.toBoolean()

        when {
            actual == grant -> {
                Toast.makeText(
                    this,
                    getString(
                        R.string.xion_perms_verified,
                        permission,
                        getString(if (grant) R.string.xion_perms_state_granted else R.string.xion_perms_state_denied)
                    ),
                    Toast.LENGTH_SHORT
                ).show()
                loadPermissions(pkg)
            }
            !write.ok -> Toast.makeText(
                this,
                getString(R.string.xion_perms_write_failed, write.combined),
                Toast.LENGTH_LONG
            ).show()
            else -> Toast.makeText(
                this,
                getString(
                    R.string.xion_perms_not_applied,
                    permission,
                    when (actual) {
                        true -> getString(R.string.xion_perms_state_granted)
                        false -> getString(R.string.xion_perms_state_denied)
                        null -> getString(R.string.xion_perms_unreadable)
                    }
                ),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Reads the permission states from `dumpsys package`. The dump groups them under section headers,
     * so the parser tracks the current section to label a permission as runtime or install-time.
     */
    private fun parsePermissions(output: String): List<PermissionEntry> {
        val map = LinkedHashMap<String, PermissionEntry>()
        var section = ""

        output.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            if (line.endsWith(":") && !line.contains("granted=")) {
                section = line.removeSuffix(":").trim()
                return@forEach
            }
            val match = GRANTED_LINE.find(line) ?: return@forEach
            val name = match.groupValues[1]
            val granted = match.groupValues[2].toBoolean()
            val flags = match.groupValues[3].trim()
            val runtime = section == "runtime permissions"
            val existing = map[name]
            if (existing == null || (!existing.runtime && runtime)) {
                map[name] = PermissionEntry(name, granted, runtime, flags)
            }
        }

        return map.values.sortedWith(compareBy({ !it.runtime }, { it.name }))
    }

    private class PackageAdapter(private val onClick: (PackageEntry) -> Unit) :
        ListAdapter<PackageEntry, PackageAdapter.Holder>(Diff) {

        class Holder(val binding: ItemXionAppopsPackageBinding) : RecyclerView.ViewHolder(binding.root)

        object Diff : DiffUtil.ItemCallback<PackageEntry>() {
            override fun areItemsTheSame(oldItem: PackageEntry, newItem: PackageEntry) =
                oldItem.packageName == newItem.packageName

            override fun areContentsTheSame(oldItem: PackageEntry, newItem: PackageEntry) =
                oldItem == newItem
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemXionAppopsPackageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = getItem(position)
            holder.binding.packageName.text = entry.packageName
            holder.binding.meta.text = buildString {
                append(entry.label ?: "unknown label")
                if (entry.system) append(" · system app")
            }
            holder.binding.root.setOnClickListener { onClick(entry) }
        }
    }

    private inner class PermissionAdapter(private val onClick: (PermissionEntry) -> Unit) :
        ListAdapter<PermissionEntry, PermissionAdapter.Holder>(Diff) {

        inner class Holder(val binding: ItemXionPermissionBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemXionPermissionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = getItem(position)
            val b = holder.binding
            b.permissionName.text = entry.name
            b.permissionMeta.text = buildString {
                append(if (entry.runtime) "runtime permission" else "install-time permission")
                if (entry.flags.isNotEmpty()) append(" · ${entry.flags.replace("USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED", "user sensitive")}")
            }
            b.stateBadge.text = if (entry.granted) "GRANTED" else "DENIED"
            b.stateBadge.applyTone(if (entry.granted) XionUi.Tone.POSITIVE else XionUi.Tone.CAUTION)
            b.root.setOnClickListener { onClick(entry) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<PermissionEntry>() {
        override fun areItemsTheSame(oldItem: PermissionEntry, newItem: PermissionEntry) =
            oldItem.name == newItem.name

        override fun areContentsTheSame(oldItem: PermissionEntry, newItem: PermissionEntry) =
            oldItem == newItem
    }

    private companion object {
        val GRANTED_LINE = Regex(
            """^([A-Za-z0-9_.]+):\s*granted=(true|false)(?:,\s*flags=\[([^\]]*)\])?"""
        )
    }
}
