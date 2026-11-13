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
import moe.shizuku.manager.databinding.ActivityXionComponentsBinding
import moe.shizuku.manager.databinding.ItemXionAppopsPackageBinding
import moe.shizuku.manager.databinding.ItemXionComponentBinding
import moe.shizuku.manager.xion.XionActivity
import moe.shizuku.manager.xion.XionPrefs
import moe.shizuku.manager.xion.XionUi
import moe.shizuku.manager.xion.XionUi.applyScreenInsets
import moe.shizuku.manager.xion.XionUi.applyTone
import moe.shizuku.manager.xion.diag.DiagnosticsCollector
import moe.shizuku.manager.xion.shell.PrivilegedShell
import org.json.JSONArray
import org.json.JSONObject

/**
 * System component manager: activities, services and receivers per package, read from the package
 * manager's resolver tables.
 *
 * Two rules keep this honest:
 * - a component is only shown as disabled when the dump lists it under disabledComponents;
 * - a change is recorded in the undo list only after the package manager confirms it, and every
 *   entry can be restored from that list.
 *
 * On Android 17 the shell cannot change component state at all (the framework answers with a
 * SecurityException), so the screen reports that refusal instead of pretending otherwise. Disabling
 * a whole package still works, so that action is offered as well and is undoable.
 */
class ComponentManagerActivity : XionActivity() {

    data class PackageEntry(val packageName: String, val system: Boolean, val label: String?)

    data class ComponentEntry(
        val packageName: String,
        val fullClass: String,
        val type: String,
        val disabled: Boolean
    ) {
        val componentName: String get() = "$packageName/$fullClass"
    }

    data class Change(
        val packageName: String,
        val component: String?,
        val previousEnabled: Boolean,
        val currentEnabled: Boolean,
        val at: Long
    )

    private lateinit var binding: ActivityXionComponentsBinding

    private val packages = ArrayList<PackageEntry>()
    private val packageAdapter = PackageAdapter { openPackage(it) }
    private var filter = Filter.ALL
    private var packageQuery = ""

    private var currentPackage: String? = null
    private var components: List<ComponentEntry> = emptyList()
    private var packageDisabled = false
    private val componentAdapter = ComponentAdapter { confirmToggle(it) }

    private enum class Filter { ALL, USER, SYSTEM }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityXionComponentsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyScreenInsets(binding.root, extraTop = 4, extraBottom = 8)

        binding.btnBack.setOnClickListener { if (binding.detailScreen.isVisible) showPackages() else finish() }
        binding.btnRefresh.setOnClickListener { refresh() }
        binding.btnChanges.setOnClickListener { showChanges() }
        binding.btnPackageToggle.setOnClickListener { confirmPackageToggle() }

        binding.packageList.layoutManager = LinearLayoutManager(this)
        binding.packageList.adapter = packageAdapter
        binding.componentList.layoutManager = LinearLayoutManager(this)
        binding.componentList.adapter = componentAdapter

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

        updateChangeCount()
        loadPackages()
    }

    private fun refresh() {
        val pkg = currentPackage
        if (pkg != null && binding.detailScreen.isVisible) loadComponents(pkg) else loadPackages()
        updateChangeCount()
    }

    private fun loadPackages() {
        if (!PrivilegedShell.isAvailable()) {
            binding.packageCount.text = getString(R.string.xion_components_unavailable)
            return
        }
        binding.packageCount.text = getString(R.string.xion_components_reading_packages)
        lifecycleScope.launch {
            val thirdParty = PrivilegedShell.run("pm list packages -3")
            val system = PrivilegedShell.run("pm list packages -s")
            if (!thirdParty.ok && !system.ok) {
                binding.packageCount.text = getString(R.string.xion_components_packages_failed, thirdParty.combined)
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
        binding.packageCount.text = getString(R.string.xion_components_packages_count, filtered.size) +
            if (packages.isNotEmpty()) " of ${packages.size}" else ""
    }

    private fun openPackage(entry: PackageEntry) {
        currentPackage = entry.packageName
        binding.listScreen.isVisible = false
        binding.detailScreen.isVisible = true
        binding.screenTitle.setText(R.string.xion_components_detail_title)
        binding.packageName.text = entry.packageName + (entry.label?.let { " · $it" } ?: "")
        loadComponents(entry.packageName)
    }

    private fun showPackages() {
        currentPackage = null
        binding.detailScreen.isVisible = false
        binding.listScreen.isVisible = true
        binding.screenTitle.setText(R.string.xion_components_title)
    }

    private fun loadComponents(pkg: String) {
        if (!PrivilegedShell.isAvailable()) {
            binding.summary.text = getString(R.string.xion_components_unavailable)
            return
        }
        binding.summary.text = getString(R.string.xion_components_reading)
        lifecycleScope.launch {
            val dump = PrivilegedShell.run("dumpsys package $pkg")
            if (!dump.ok) {
                binding.summary.text = getString(R.string.xion_components_read_failed, dump.combined)
                return@launch
            }
            val disabledPackages = PrivilegedShell.run("pm list packages -d")
            packageDisabled = disabledPackages.stdout.lineSequence()
                .any { it.trim() == "package:$pkg" }

            components = parseComponents(dump.stdout, pkg)
            binding.btnPackageToggle.setText(
                if (packageDisabled) R.string.xion_components_enable_package else R.string.xion_components_disable_package
            )
            binding.summary.text = getString(
                R.string.xion_components_summary,
                components.size,
                getString(if (packageDisabled) R.string.xion_components_state_disabled else R.string.xion_components_state_enabled)
            )
            componentAdapter.submitList(components)
        }
    }

    private fun confirmToggle(entry: ComponentEntry) {
        if (packageDisabled) {
            MaterialAlertDialogBuilder(this)
                .setTitle(entry.fullClass)
                .setMessage(R.string.xion_components_package_disabled_note)
                .setPositiveButton(R.string.xion_components_enable_package) { _, _ -> packageToggle() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        val enabling = entry.disabled
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(if (enabling) R.string.xion_components_enable_title else R.string.xion_components_disable_title, entry.fullClass))
            .setMessage(if (enabling) R.string.xion_components_enable_msg else R.string.xion_components_disable_msg)
            .setPositiveButton(if (enabling) R.string.xion_components_enable else R.string.xion_components_disable) { _, _ ->
                lifecycleScope.launch { toggleComponent(entry) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private suspend fun toggleComponent(entry: ComponentEntry) {
        val enabling = entry.disabled
        val command = if (enabling) {
            "pm enable ${entry.componentName}"
        } else {
            "pm disable-user --user 0 ${entry.componentName}"
        }
        val write = PrivilegedShell.run(command)
        val actual = componentDisabled(entry.packageName, entry.fullClass)

        when {
            actual == !enabling -> {
                record(
                    Change(
                        entry.packageName,
                        entry.fullClass,
                        previousEnabled = !entry.disabled,
                        currentEnabled = entry.disabled,
                        at = System.currentTimeMillis()
                    )
                )
                Toast.makeText(this, getString(R.string.xion_components_verified, entry.fullClass, stateLabel(!enabling)), Toast.LENGTH_SHORT).show()
                loadComponents(entry.packageName)
            }
            !write.ok -> {
                val hint = if (write.combined.contains("SecurityException")) {
                    getString(R.string.xion_components_blocked)
                } else {
                    getString(R.string.xion_components_write_failed, write.combined.take(180))
                }
                Toast.makeText(this, hint, Toast.LENGTH_LONG).show()
            }
            else -> Toast.makeText(
                this,
                getString(R.string.xion_components_not_applied, entry.fullClass, stateLabel(actual == true)),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun confirmPackageToggle() {
        val pkg = currentPackage ?: return
        val enabling = packageDisabled
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(if (enabling) R.string.xion_components_enable_package else R.string.xion_components_disable_package))
            .setMessage(if (enabling) R.string.xion_components_package_enable_msg else R.string.xion_components_package_disable_msg)
            .setPositiveButton(if (enabling) R.string.xion_components_enable_package else R.string.xion_components_disable_package) { _, _ ->
                packageToggle()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun packageToggle() {
        val pkg = currentPackage ?: return
        val enabling = packageDisabled
        lifecycleScope.launch {
            val command = if (enabling) "pm enable $pkg" else "pm disable-user --user 0 $pkg"
            val write = PrivilegedShell.run(command)
            val nowDisabled = packageIsDisabled(pkg)
            val success = nowDisabled != null && nowDisabled == !enabling

            if (success) {
                record(
                    Change(
                        pkg,
                        null,
                        previousEnabled = !enabling,
                        currentEnabled = enabling,
                        at = System.currentTimeMillis()
                    )
                )
                Toast.makeText(this@ComponentManagerActivity, getString(R.string.xion_components_verified, pkg, stateLabel(!enabling)), Toast.LENGTH_SHORT).show()
                loadComponents(pkg)
            } else if (!write.ok) {
                Toast.makeText(this@ComponentManagerActivity, getString(R.string.xion_components_write_failed, write.combined.take(180)), Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@ComponentManagerActivity, getString(R.string.xion_components_not_applied, pkg, stateLabel(nowDisabled == true)), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** True when the dump lists the component under disabledComponents. Null when it cannot be read. */
    private suspend fun componentDisabled(pkg: String, fullClass: String): Boolean? {
        val dump = PrivilegedShell.run("dumpsys package $pkg")
        if (!dump.ok) return null
        val block = dump.stdout.substringAfter("disabledComponents:", "")
        if (block.isEmpty()) return false
        val shortName = fullClass.removePrefix("$pkg.")
        return block.lineSequence()
            .takeWhile { it.trim().isNotEmpty() }
            .any { line ->
                val value = line.trim()
                value == "$pkg/$fullClass" || value == "$pkg/.$shortName" || value.endsWith("/$fullClass")
            }
    }

    private suspend fun packageIsDisabled(pkg: String): Boolean? {
        val result = PrivilegedShell.run("pm list packages -d")
        if (!result.ok) return null
        return result.stdout.lineSequence().any { it.trim() == "package:$pkg" }
    }

    private fun showChanges() {
        val changes = loadChanges()
        if (changes.isEmpty()) {
            Toast.makeText(this, R.string.xion_components_no_changes, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = changes.map { change ->
            val target = change.component ?: getString(R.string.xion_components_whole_package)
            val state = stateLabel(!change.currentEnabled)
            "$target\n$state · ${DiagnosticsCollector.Format.timeAgo(change.at)}"
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.xion_components_changes_title)
            .setItems(labels) { _, which -> restore(changes[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun restore(change: Change) {
        lifecycleScope.launch {
            val pkg = change.packageName
            val component = change.component
            val target = change.previousEnabled
            val write = PrivilegedShell.run(
                when {
                    component == null && target -> "pm enable $pkg"
                    component == null -> "pm disable-user --user 0 $pkg"
                    target -> "pm enable $pkg/$component"
                    else -> "pm disable-user --user 0 $pkg/$component"
                }
            )
            val actual: Boolean? = if (component == null) {
                packageIsDisabled(pkg)?.let { !it }
            } else {
                componentDisabled(pkg, component)?.let { !it }
            }

            if (actual == target) {
                val remaining = loadChanges().filterNot { it == change }
                saveChanges(remaining)
                updateChangeCount()
                Toast.makeText(this@ComponentManagerActivity, getString(R.string.xion_components_restored, component ?: pkg), Toast.LENGTH_SHORT).show()
                val open = currentPackage
                if (open != null) loadComponents(open)
            } else if (!write.ok) {
                Toast.makeText(this@ComponentManagerActivity, getString(R.string.xion_components_write_failed, write.combined.take(180)), Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@ComponentManagerActivity, getString(R.string.xion_components_not_applied, component ?: pkg, stateLabel(actual == true)), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateChangeCount() {
        val count = loadChanges().size
        binding.btnChanges.text = if (count == 0) {
            getString(R.string.xion_components_changes)
        } else {
            getString(R.string.xion_components_changes_count, count)
        }
    }

    private fun stateLabel(disabled: Boolean): String =
        getString(if (disabled) R.string.xion_components_state_disabled else R.string.xion_components_state_enabled)

    private fun record(change: Change) {
        val changes = loadChanges().apply { add(change) }
        saveChanges(changes)
        updateChangeCount()
    }

    private fun loadChanges(): MutableList<Change> {
        val raw = XionPrefs.get(this).getString(KEY_CHANGES, "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                Change(
                    item.optString("package"),
                    item.optString("component").takeIf { it.isNotEmpty() },
                    item.optBoolean("previous", true),
                    item.optBoolean("current", false),
                    item.optLong("at", 0L)
                )
            }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    private fun saveChanges(changes: List<Change>) {
        val array = JSONArray()
        changes.forEach { change ->
            array.put(
                JSONObject().apply {
                    put("package", change.packageName)
                    put("component", change.component.orEmpty())
                    put("previous", change.previousEnabled)
                    put("current", change.currentEnabled)
                    put("at", change.at)
                }
            )
        }
        XionPrefs.get(this).edit().putString(KEY_CHANGES, array.toString()).apply()
    }

    /**
     * The resolver tables list every component that declares an intent filter, grouped by type. A
     * component without a filter cannot be disabled from here, and is not listed.
     */
    private fun parseComponents(output: String, pkg: String): List<ComponentEntry> {
        val result = LinkedHashMap<String, ComponentEntry>()
        val disabled = disabledFromDump(output, pkg)
        var type = ""

        output.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("Activity Resolver Table") -> { type = "activity"; return@forEach }
                line.startsWith("Service Resolver Table") -> { type = "service"; return@forEach }
                line.startsWith("Receiver Resolver Table") -> { type = "receiver"; return@forEach }
                line.startsWith("Provider Resolver Table") -> { type = "provider"; return@forEach }
                type.isEmpty() -> return@forEach
            }
            val match = COMPONENT_LINE.find(line) ?: return@forEach
            val owner = match.groupValues[1]
            if (owner != pkg) return@forEach
            val rawClass = match.groupValues[2]
            val fullClass = when {
                rawClass.startsWith(".") -> pkg + rawClass
                !rawClass.contains('.') -> "$pkg.$rawClass"
                else -> rawClass
            }
            val key = "$type|$fullClass"
            if (result.containsKey(key)) return@forEach
            result[key] = ComponentEntry(pkg, fullClass, type, disabled = fullClass in disabled)
        }

        return result.values.sortedWith(compareBy({ TYPE_ORDER.indexOf(it.type) }, { it.fullClass }))
    }

    /** Full class names listed under disabledComponents, expanded to package-qualified names. */
    private fun disabledFromDump(output: String, pkg: String): Set<String> {
        val block = output.substringAfter("disabledComponents:", "")
        if (block.isEmpty()) return emptySet()
        return block.lineSequence()
            .takeWhile { it.trim().isNotEmpty() }
            .mapNotNull { line ->
                val value = line.trim()
                if (!value.startsWith("$pkg/")) return@mapNotNull null
                val cls = value.removePrefix("$pkg/")
                when {
                    cls.startsWith(".") -> pkg + cls
                    !cls.contains('.') -> "$pkg.$cls"
                    else -> cls
                }
            }
            .toSet()
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

    private inner class ComponentAdapter(private val onClick: (ComponentEntry) -> Unit) :
        ListAdapter<ComponentEntry, ComponentAdapter.Holder>(Diff) {

        inner class Holder(val binding: ItemXionComponentBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemXionComponentBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = getItem(position)
            val b = holder.binding
            b.componentName.text = entry.fullClass
            b.componentMeta.text = buildString {
                append(entry.type)
                if (packageDisabled) append(" · package disabled")
            }
            b.stateBadge.text = if (entry.disabled) "DISABLED" else "ENABLED"
            b.stateBadge.applyTone(if (entry.disabled) XionUi.Tone.NEGATIVE else XionUi.Tone.POSITIVE)
            b.root.setOnClickListener { onClick(entry) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<ComponentEntry>() {
        override fun areItemsTheSame(oldItem: ComponentEntry, newItem: ComponentEntry) =
            oldItem.packageName == newItem.packageName && oldItem.fullClass == newItem.fullClass

        override fun areContentsTheSame(oldItem: ComponentEntry, newItem: ComponentEntry) = oldItem == newItem
    }

    private companion object {
        const val KEY_CHANGES = "component_changes"
        val COMPONENT_LINE = Regex("""([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)\s+filter""")
        val TYPE_ORDER = listOf("activity", "service", "receiver", "provider")
    }
}
