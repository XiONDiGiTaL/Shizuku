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
import moe.shizuku.manager.databinding.ActivityXionAppopsBinding
import moe.shizuku.manager.databinding.ItemXionAppopsOpBinding
import moe.shizuku.manager.databinding.ItemXionAppopsPackageBinding
import moe.shizuku.manager.xion.XionActivity
import moe.shizuku.manager.xion.XionUi
import moe.shizuku.manager.xion.XionUi.applyScreenInsets
import moe.shizuku.manager.xion.XionUi.applyTone
import moe.shizuku.manager.xion.shell.PrivilegedShell

/**
 * AppOps manager: browse the operation modes assigned to any package, change one, and read it back.
 *
 * Two rules shape this screen:
 * - the list is parsed from `cmd appops get`, so what is shown is the service's own answer;
 * - a change is reported only after `cmd appops get <package> <op>` returns the mode that was
 *   requested. If the read-back disagrees, the screen says so instead of pretending it worked.
 */
class AppOpsActivity : XionActivity() {

    data class PackageEntry(val packageName: String, val system: Boolean, val label: String?)

    data class OpEntry(val op: String, val mode: String, val detail: String) {
        val isDefault: Boolean get() = mode == "default"
    }

    private lateinit var binding: ActivityXionAppopsBinding

    private val packages = ArrayList<PackageEntry>()
    private val packageAdapter = PackageAdapter { openPackage(it) }
    private var filter = Filter.ALL
    private var packageQuery = ""

    private var currentPackage: String? = null
    private var ops: List<OpEntry> = emptyList()
    private var opQuery = ""
    private val opAdapter = OpAdapter { confirmChange(it) }

    private enum class Filter { ALL, USER, SYSTEM }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityXionAppopsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyScreenInsets(binding.root, extraTop = 4, extraBottom = 8)

        binding.btnBack.setOnClickListener { if (binding.detailScreen.isVisible) showPackages() else finish() }
        binding.btnRefresh.setOnClickListener { refresh() }

        binding.packageList.layoutManager = LinearLayoutManager(this)
        binding.packageList.adapter = packageAdapter
        binding.opList.layoutManager = LinearLayoutManager(this)
        binding.opList.adapter = opAdapter

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

        binding.searchOps.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                opQuery = s?.toString()?.trim()?.lowercase().orEmpty()
                applyOpFilter()
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
        if (pkg != null && binding.detailScreen.isVisible) loadOps(pkg) else loadPackages()
    }

    private fun loadPackages() {
        if (!PrivilegedShell.isAvailable()) {
            binding.packageCount.text = getString(R.string.xion_appops_unavailable)
            return
        }
        binding.packageCount.text = getString(R.string.xion_appops_reading_packages)
        lifecycleScope.launch {
            val thirdParty = PrivilegedShell.run("pm list packages -3")
            val system = PrivilegedShell.run("pm list packages -s")
            if (!thirdParty.ok && !system.ok) {
                binding.packageCount.text = getString(R.string.xion_appops_packages_failed, thirdParty.combined)
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
        binding.packageCount.text = getString(R.string.xion_appops_packages_count, filtered.size) +
            if (packages.isNotEmpty()) " of ${packages.size}" else ""
    }

    private fun openPackage(entry: PackageEntry) {
        currentPackage = entry.packageName
        opQuery = ""
        binding.searchOps.setText("")
        binding.listScreen.isVisible = false
        binding.detailScreen.isVisible = true
        binding.screenTitle.setText(R.string.xion_appops_detail_title)
        binding.packageName.text = entry.packageName + (entry.label?.let { " · $it" } ?: "")
        loadOps(entry.packageName)
    }

    private fun showPackages() {
        currentPackage = null
        binding.detailScreen.isVisible = false
        binding.listScreen.isVisible = true
        binding.screenTitle.setText(R.string.xion_appops_title)
    }

    private fun loadOps(pkg: String) {
        if (!PrivilegedShell.isAvailable()) {
            binding.summary.text = getString(R.string.xion_appops_unavailable)
            return
        }
        binding.summary.text = getString(R.string.xion_appops_reading_ops)
        lifecycleScope.launch {
            val result = PrivilegedShell.run("cmd appops get $pkg")
            if (!result.ok) {
                binding.summary.text = getString(R.string.xion_appops_read_failed, result.combined)
                return@launch
            }
            ops = parseOps(result.stdout)
            if (ops.isEmpty()) {
                binding.summary.text = getString(R.string.xion_appops_empty)
                opAdapter.submitList(emptyList())
                return@launch
            }
            val changed = ops.count { !it.isDefault }
            binding.summary.text = getString(R.string.xion_appops_summary, ops.size, changed)
            applyOpFilter()
        }
    }

    private fun applyOpFilter() {
        val filtered = ops.filter { opQuery.isEmpty() || it.op.lowercase().contains(opQuery) }
        opAdapter.submitList(filtered)
    }

    private fun confirmChange(entry: OpEntry) {
        val pkg = currentPackage ?: return
        val modes = arrayOf("allow", "foreground", "ignore", "default")
        val labels = arrayOf(
            getString(R.string.xion_appops_mode_allow),
            getString(R.string.xion_appops_mode_foreground),
            getString(R.string.xion_appops_mode_ignore),
            getString(R.string.xion_appops_mode_default)
        )
        val checked = modes.indexOf(entry.mode)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.xion_appops_change_title, entry.op))
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dialog.dismiss()
                lifecycleScope.launch { setAndVerify(pkg, entry.op, modes[which]) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private suspend fun setAndVerify(pkg: String, op: String, mode: String) {
        val write = PrivilegedShell.run("cmd appops set $pkg $op $mode")
        val read = PrivilegedShell.run("cmd appops get $pkg $op")
        val actual = parseMode(read)

        when {
            actual == mode -> {
                Toast.makeText(
                    this,
                    getString(R.string.xion_appops_verified, op, mode),
                    Toast.LENGTH_SHORT
                ).show()
                loadOps(pkg)
            }
            !write.ok -> Toast.makeText(
                this,
                getString(R.string.xion_appops_write_failed, write.combined),
                Toast.LENGTH_LONG
            ).show()
            else -> Toast.makeText(
                this,
                getString(R.string.xion_appops_not_applied, op, actual ?: getString(R.string.xion_appops_unreadable)),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** Reads the mode from `cmd appops get <package> <op>`. Null means the answer was not readable. */
    private fun parseMode(result: PrivilegedShell.CommandResult): String? {
        if (!result.ok) return null
        val text = result.stdout.trim()
        if (text.isEmpty()) return null
        val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        if (first.startsWith("No operations", ignoreCase = true)) return "default"
        return parseLineMode(first.removePrefix("Uid mode: ").trim())
    }

    private fun parseOps(output: String): List<OpEntry> {
        val map = LinkedHashMap<String, OpEntry>()
        output.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            val body = line.replaceFirst(Regex("^Uid mode:\\s*", RegexOption.IGNORE_CASE), "")
            val op = Regex("^([A-Za-z][A-Za-z0-9_.]*):").find(body)?.groupValues?.get(1) ?: return@forEach
            val mode = parseLineMode(body) ?: return@forEach
            val detail = line.substringAfter(';', "").trim()
            map[op] = OpEntry(op, mode, detail)
        }
        return map.values.sortedWith(compareBy({ it.isDefault }, { it.op }))
    }

    private fun parseLineMode(line: String): String? {
        val mode = Regex(":\\s*([a-z]+)").find(line)?.groupValues?.get(1) ?: return null
        return if (mode in MODES) mode else null
    }

    private fun modeLabel(mode: String): String = when (mode) {
        "allow" -> "ALLOW"
        "foreground" -> "FOREGROUND"
        "ignore" -> "IGNORE"
        "deny" -> "DENY"
        "ask" -> "ASK"
        else -> "DEFAULT"
    }

    private fun modeTone(mode: String): XionUi.Tone = when (mode) {
        "allow" -> XionUi.Tone.POSITIVE
        "foreground" -> XionUi.Tone.CAUTION
        "ignore", "deny" -> XionUi.Tone.NEGATIVE
        else -> XionUi.Tone.NEUTRAL
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

    private inner class OpAdapter(private val onClick: (OpEntry) -> Unit) :
        ListAdapter<OpEntry, OpAdapter.Holder>(OpDiff) {

        inner class Holder(val binding: ItemXionAppopsOpBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemXionAppopsOpBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = getItem(position)
            val b = holder.binding
            b.opName.text = entry.op
            b.opMeta.text = if (entry.detail.isNotEmpty()) entry.detail else entry.mode
            b.modeBadge.text = modeLabel(entry.mode)
            b.modeBadge.applyTone(modeTone(entry.mode))
            b.root.setOnClickListener { onClick(entry) }
        }
    }

    private object OpDiff : DiffUtil.ItemCallback<OpEntry>() {
        override fun areItemsTheSame(oldItem: OpEntry, newItem: OpEntry) = oldItem.op == newItem.op
        override fun areContentsTheSame(oldItem: OpEntry, newItem: OpEntry) = oldItem == newItem
    }

    private companion object {
        val MODES = setOf("allow", "ignore", "deny", "default", "foreground", "ask", "errored")
    }
}
