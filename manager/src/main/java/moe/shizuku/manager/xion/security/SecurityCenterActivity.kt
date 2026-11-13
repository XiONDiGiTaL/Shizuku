package moe.shizuku.manager.xion.security

import android.content.Intent
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
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.ActivityXionSecurityBinding
import moe.shizuku.manager.databinding.ItemXionFindingBinding
import moe.shizuku.manager.databinding.ItemXionSecuritySectionBinding
import moe.shizuku.manager.xion.XionActivity
import moe.shizuku.manager.xion.XionUi
import moe.shizuku.manager.xion.XionUi.applyScreenInsets
import moe.shizuku.manager.xion.XionUi.applyTone
import moe.shizuku.manager.xion.shell.PrivilegedShell
import moe.shizuku.manager.xion.tools.AppOpsActivity
import moe.shizuku.manager.xion.tools.PermissionInspectorActivity

/**
 * Security and privacy center: one screen that answers three questions.
 *
 * - Is the app lock on, and does the device still have the screen lock it relies on?
 * - Which user apps hold a sensitive runtime permission?
 * - Where has an AppOps mode been set to allow or foreground while the matching permission is not
 *   granted, so the operation is allowed without the permission?
 *
 * Every row is a link to the tool that changes it: the lock dialog, the AppOps manager for that
 * package, or the permission inspector for that package. Data comes from `dumpsys package` and
 * `cmd appops get`, and nothing is reported as a finding without that output.
 */
class SecurityCenterActivity : XionActivity() {

    private enum class Kind { LOCK, OVERRIDE, PERMISSION }

    private data class Finding(
        val kind: Kind,
        val title: String,
        val meta: String,
        val badge: String,
        val tone: XionUi.Tone,
        val pkg: String?,
        val op: String? = null
    ) {
        val key: String get() = "$kind|$title|$meta|$badge|$op"
    }

    private sealed class Row {
        data class Section(val title: String, val count: Int) : Row()
        data class Note(val text: String) : Row()
        data class Entry(val finding: Finding) : Row()
    }

    private enum class Filter { ALL, OVERRIDES, PERMISSIONS }

    private lateinit var binding: ActivityXionSecurityBinding
    private val adapter = RowAdapter(
        onClick = { onFinding(it) },
        onFix = { fixOverride(it) },
        canFix = { it.key !in unfixable }
    )

    /** Findings other than the lock row, kept so returning to the screen does not rescan. */
    private var scanned: List<Finding> = emptyList()
    private var scannedOnce = false
    private var filter = Filter.ALL

    /** Guards against a second tap while a fix is being written and read back. */
    private var fixInFlight = false

    /** Rows whose write Android refused: the button is hidden instead of failing again. */
    private val unfixable = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityXionSecurityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyScreenInsets(binding.root, extraTop = 4, extraBottom = 8)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRefresh.setOnClickListener { scan() }
        binding.findingList.layoutManager = LinearLayoutManager(this)
        binding.findingList.adapter = adapter

        binding.filters.setOnCheckedStateChangeListener { _, checked ->
            filter = when (checked.firstOrNull()) {
                R.id.filterOverrides -> Filter.OVERRIDES
                R.id.filterPermissions -> Filter.PERMISSIONS
                else -> Filter.ALL
            }
            render()
        }

        render()
        scan()
    }

    override fun onResume() {
        super.onResume()
        // The lock can be toggled from the dashboard while this screen is in the back stack.
        if (scannedOnce) render()
    }

    private fun scan() {
        if (!PrivilegedShell.isAvailable()) {
            binding.summary.text = getString(R.string.xion_security_unavailable)
            scanned = emptyList()
            scannedOnce = true
            render()
            return
        }

        binding.summary.text = getString(R.string.xion_security_scanning)
        lifecycleScope.launch {
            val apps = async { PrivilegedShell.run(APPS_COMMAND, 30_000) }
            val permissions = async { PrivilegedShell.run(PERMISSIONS_COMMAND, 60_000) }
            val ops = async { PrivilegedShell.run(APPOPS_COMMAND, 60_000) }

            val appsResult = apps.await()
            val permissionsResult = permissions.await()
            val opsResult = ops.await()

            val failures = ArrayList<String>()
            val thirdParty = if (appsResult.ok) {
                parsePackages(appsResult.stdout)
            } else {
                failures.add(firstLine(appsResult.combined))
                emptySet()
            }

            val granted = if (permissionsResult.ok) {
                parseGranted(permissionsResult.stdout)
            } else {
                failures.add(firstLine(permissionsResult.combined))
                emptySet()
            }

            val labels = labelsFor(thirdParty)
            val overrides = if (opsResult.ok) {
                filterFixable(parseOverrides(opsResult.stdout, granted, thirdParty, labels))
            } else {
                failures.add(firstLine(opsResult.combined))
                emptyList()
            }
            val sensitive = parseSensitive(granted, thirdParty, labels)

            scanned = overrides + sensitive
            scannedOnce = true
            binding.summary.text = if (failures.isEmpty()) {
                getString(R.string.xion_security_summary, overrides.size, sensitive.size)
            } else {
                getString(R.string.xion_security_scan_failed, failures.joinToString("; "))
            }
            render()
        }
    }

    private fun render() {
        val rows = ArrayList<Row>()
        rows.add(Row.Section(getString(R.string.xion_security_section_lock), -1))
        rows.add(Row.Entry(lockFinding()))

        if (filter != Filter.PERMISSIONS) {
            val overrides = scanned.filter { it.kind == Kind.OVERRIDE }
            rows.add(Row.Section(getString(R.string.xion_security_section_overrides), overrides.size))
            if (overrides.isEmpty()) {
                rows.add(Row.Note(getString(R.string.xion_security_empty_overrides)))
            } else {
                overrides.forEach { rows.add(Row.Entry(it)) }
            }
        }

        if (filter != Filter.OVERRIDES) {
            val permissions = scanned.filter { it.kind == Kind.PERMISSION }
            rows.add(Row.Section(getString(R.string.xion_security_section_permissions), permissions.size))
            if (permissions.isEmpty()) {
                rows.add(Row.Note(getString(R.string.xion_security_empty_permissions)))
            } else {
                permissions.forEach { rows.add(Row.Entry(it)) }
            }
        }

        adapter.submitList(rows)
    }

    private fun lockFinding(): Finding {
        val enabled = AppLock.isEnabled(this)
        val secure = AppLock.isDeviceSecure(this)
        val meta = when {
            !secure -> getString(R.string.xion_security_lock_unavailable)
            enabled -> getString(R.string.xion_security_lock_meta_on)
            else -> getString(R.string.xion_security_lock_meta_off)
        }
        return Finding(
            Kind.LOCK,
            getString(R.string.xion_lock_title),
            meta,
            getString(if (enabled) R.string.xion_security_badge_on else R.string.xion_security_badge_off),
            if (enabled) XionUi.Tone.POSITIVE else XionUi.Tone.NEUTRAL,
            null
        )
    }

    private fun onFinding(finding: Finding) {
        when (finding.kind) {
            Kind.LOCK -> showLockDialog()
            Kind.OVERRIDE -> finding.pkg?.let { pkg ->
                startActivity(
                    Intent(this, AppOpsActivity::class.java)
                        .putExtra(AppOpsActivity.EXTRA_PACKAGE, pkg)
                )
            }
            Kind.PERMISSION -> finding.pkg?.let { pkg ->
                startActivity(
                    Intent(this, PermissionInspectorActivity::class.java)
                        .putExtra(PermissionInspectorActivity.EXTRA_PACKAGE, pkg)
                )
            }
        }
    }

    /**
     * Resets one override to the default mode and reads it back. The row is only dropped, and the
     * change only reported, when `cmd appops get` returns default; a mode Android refuses to change
     * stays on screen with the mode it still reads.
     */
    private fun fixOverride(finding: Finding) {
        val pkg = finding.pkg
        val op = finding.op
        if (pkg == null || op == null || fixInFlight) return
        fixInFlight = true
        binding.summary.text = getString(R.string.xion_security_fixing, op)

        lifecycleScope.launch {
            val write = PrivilegedShell.run("cmd appops set $pkg $op default")
            val read = PrivilegedShell.run("cmd appops get $pkg $op")
            val actual = parseOpMode(read)
            fixInFlight = false

            when {
                actual == "default" -> {
                    Toast.makeText(
                        this@SecurityCenterActivity,
                        getString(R.string.xion_security_fix_verified, op, "default"),
                        Toast.LENGTH_SHORT
                    ).show()
                    scanned = scanned.filterNot { it.key == finding.key }
                    updateSummary()
                    render()
                }
                !write.ok -> {
                    binding.summary.text = getString(
                        R.string.xion_security_fix_failed,
                        firstLine(write.combined)
                    )
                    Toast.makeText(
                        this@SecurityCenterActivity,
                        getString(R.string.xion_security_fix_failed, firstLine(write.combined)),
                        Toast.LENGTH_LONG
                    ).show()
                }
                else -> {
                    updateSummary()
                    unfixable.add(finding.key)
                    Toast.makeText(
                        this@SecurityCenterActivity,
                        getString(
                            R.string.xion_security_fix_not_applied,
                            op,
                            actual ?: getString(R.string.xion_security_fix_unreadable)
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                    val index = adapter.currentList.indexOfFirst {
                        it is Row.Entry && it.finding.key == finding.key
                    }
                    if (index >= 0) adapter.notifyItemChanged(index)
                }
            }
        }
    }

    /**
     * Reads the mode back from `cmd appops get <package> <op>`. The answer can hold a uid wide line
     * and a per package line; the per package one is the mode this screen changes, so it wins.
     */
    private fun parseOpMode(result: PrivilegedShell.CommandResult): String? {
        if (!result.ok) return null
        val lines = result.stdout.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return null
        if (lines.first().startsWith("No operations", ignoreCase = true)) return "default"
        val perPackage = lines.firstOrNull { !it.startsWith("Uid mode", ignoreCase = true) }
        if (perPackage == null) return null
        return Regex(":\\s*([a-z]+)").find(perPackage)?.groupValues?.get(1)
    }

    private fun updateSummary() {
        binding.summary.text = getString(
            R.string.xion_security_summary,
            scanned.count { it.kind == Kind.OVERRIDE },
            scanned.count { it.kind == Kind.PERMISSION }
        )
    }

    private fun showLockDialog() {
        val enabled = AppLock.isEnabled(this)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.xion_lock_title)
            .setMessage(R.string.xion_lock_body)
            .setPositiveButton(if (enabled) "Turn off" else "Turn on") { _, _ ->
                when {
                    enabled -> {
                        AppLock.setEnabled(this, false)
                        Toast.makeText(this, R.string.xion_lock_off, Toast.LENGTH_SHORT).show()
                        render()
                    }
                    !AppLock.isDeviceSecure(this) -> {
                        Toast.makeText(this, R.string.xion_lock_no_device_lock, Toast.LENGTH_LONG).show()
                    }
                    else -> {
                        AppLock.setEnabled(this, true)
                        Toast.makeText(this, R.string.xion_lock_on, Toast.LENGTH_SHORT).show()
                        render()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // --- Reading the device -------------------------------------------------

    private fun parsePackages(output: String): Set<String> = output.lineSequence()
        .mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("package:")) trimmed.removePrefix("package:") else null
        }
        .filter { it.isNotBlank() }
        .toSet()

    /** Pairs of "package permission" that the package manager reports as granted at runtime. */
    private fun parseGranted(output: String): Set<String> {
        val set = HashSet<String>()
        output.lineSequence().forEach { line ->
            val parts = line.trim().split(' ')
            if (parts.size == 2 && parts[1].startsWith("android.permission.")) {
                set.add(parts[0] + " " + parts[1])
            }
        }
        return set
    }

    /**
     * `cmd appops get` prints three things that look alike: `Uid mode: OP: mode` for state held by the
     * whole uid, `OP: mode` for the per package mode, and for permission backed operations a second
     * `OP: mode; time=...` line that reports the uid state next to the package one. Only an op that
     * appears exactly once, without a uid line, is a per package mode the shell user can set, so an
     * op with more than one entry is skipped: the write would be refused and the row would claim an
     * override that does not exist.
     */
    private fun parseOverrides(
        output: String,
        granted: Set<String>,
        thirdParty: Set<String>,
        labels: Map<String, String>
    ): List<Finding> {
        data class Entry(val op: String, val mode: String, val uid: Boolean)

        val entries = HashMap<String, MutableList<Entry>>()
        output.lineSequence().forEach { raw ->
            val parts = raw.trim().split(' ')
            if (parts.size < 3) return@forEach
            val pkg = parts[0]
            if (pkg !in thirdParty) return@forEach
            val rest = parts.drop(1)
            val isUid = rest.size >= 3 && rest[0] == "Uid" && rest[1] == "mode:"
            val op = (if (isUid) rest[2] else rest[0]).removeSuffix(":")
            val mode = (if (isUid) rest.getOrNull(3) else rest.getOrNull(1))?.removeSuffix(";")
                ?: return@forEach
            entries.getOrPut("$pkg|$op") { ArrayList() }.add(Entry(op, mode, isUid))
        }

        return entries.mapNotNull { (key, found) ->
            val entry = found.singleOrNull() ?: return@mapNotNull null
            if (entry.uid) return@mapNotNull null
            val (op, mode) = entry
            if (mode != "allow" && mode != "foreground") return@mapNotNull null

            // An override is unusual only when the permission it stands in for is not granted.
            val pkg = key.substringBefore('|')
            val permission = OP_PERMISSIONS[op]
            if (permission != null && "$pkg $permission" in granted) return@mapNotNull null

            Finding(
                Kind.OVERRIDE,
                labels[pkg] ?: pkg,
                getString(R.string.xion_security_override_meta, op, pkg),
                mode.uppercase(),
                if (mode == "allow") XionUi.Tone.NEGATIVE else XionUi.Tone.CAUTION,
                pkg,
                op
            )
        }.sortedWith(compareBy({ it.title.lowercase() }, { it.meta }))
    }

    /** One row per app that holds at least one sensitive runtime permission. */
    private fun parseSensitive(
        granted: Set<String>,
        thirdParty: Set<String>,
        labels: Map<String, String>
    ): List<Finding> {
        val byPackage = HashMap<String, MutableList<String>>()
        granted.forEach { entry ->
            val space = entry.indexOf(' ')
            if (space <= 0) return@forEach
            val pkg = entry.substring(0, space)
            val permission = entry.substring(space + 1)
            if (pkg !in thirdParty || permission !in SENSITIVE) return@forEach
            byPackage.getOrPut(pkg) { ArrayList() }.add(permission.substringAfterLast('.'))
        }
        return byPackage.entries
            .sortedBy { (labels[it.key] ?: it.key).lowercase() }
            .map { (pkg, permissions) ->
                Finding(
                    Kind.PERMISSION,
                    labels[pkg] ?: pkg,
                    permissions.sorted().joinToString(" · "),
                    getString(R.string.xion_security_badge_granted),
                    XionUi.Tone.CAUTION,
                    pkg
                )
            }
    }

    /**
     * A candidate is only kept when `cmd appops get <package> <op>` answers with a per package line.
     * When it answers `Uid mode: ...` the state belongs to the whole uid and the shell user cannot
     * set it for one package, so a Fix button there would never succeed.
     */
    private suspend fun filterFixable(candidates: List<Finding>): List<Finding> {
        val specs = candidates.mapNotNull { finding ->
            val pkg = finding.pkg ?: return@mapNotNull null
            val op = finding.op ?: return@mapNotNull null
            "$pkg:$op"
        }
        if (specs.isEmpty()) return candidates

        val script = buildString {
            append("for s in ").append(specs.joinToString(" "))
            append("; do p=\"\${s%%:*}\"; o=\"\${s#*:}\"; ")
            append("f=\"\$(cmd appops get \$p \$o | head -1)\"; ")
            append("case \"\$f\" in \"Uid mode:\"*) ;; *) echo \"\$p \$o\";; esac; done")
        }
        val result = PrivilegedShell.run(script, 60_000)
        if (!result.ok) return candidates

        val fixable = result.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        return candidates.filter { "${it.pkg} ${it.op}" in fixable }
    }

    private fun labelsFor(packages: Set<String>): Map<String, String> {
        val map = HashMap<String, String>()
        val pm = packageManager
        packages.forEach { pkg ->
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrNull()
            if (label != null && label != pkg) map[pkg] = label
        }
        return map
    }

    private fun firstLine(text: String): String =
        text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "no output"

    // --- List ---------------------------------------------------------------

    private class RowAdapter(
        private val onClick: (Finding) -> Unit,
        private val onFix: (Finding) -> Unit,
        private val canFix: (Finding) -> Boolean
    ) :
        ListAdapter<Row, RecyclerView.ViewHolder>(Diff) {

        class SectionHolder(val binding: ItemXionSecuritySectionBinding) : RecyclerView.ViewHolder(binding.root)

        inner class EntryHolder(val binding: ItemXionFindingBinding) : RecyclerView.ViewHolder(binding.root)

        override fun getItemViewType(position: Int): Int =
            if (getItem(position) is Row.Entry) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 1) {
                EntryHolder(ItemXionFindingBinding.inflate(inflater, parent, false))
            } else {
                SectionHolder(ItemXionSecuritySectionBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = getItem(position)) {
                is Row.Section -> (holder as SectionHolder).binding.apply {
                    sectionTitle.text = row.title
                    sectionCount.isVisible = row.count >= 0
                    sectionCount.text = row.count.toString()
                }
                is Row.Note -> (holder as SectionHolder).binding.apply {
                    sectionTitle.text = row.text
                    sectionTitle.textSize = 11f
                    sectionCount.isVisible = false
                }
                is Row.Entry -> (holder as EntryHolder).binding.apply {
                    val finding = row.finding
                    findingTitle.text = finding.title
                    findingMeta.text = finding.meta
                    findingBadge.text = finding.badge
                    findingBadge.applyTone(finding.tone)
                    root.setOnClickListener { onClick(finding) }
                    findingAction.isVisible = finding.kind == Kind.OVERRIDE && canFix(finding)
                    findingAction.setOnClickListener { onFix(finding) }
                }
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(oldItem: Row, newItem: Row): Boolean = when {
            oldItem is Row.Section && newItem is Row.Section -> oldItem.title == newItem.title
            oldItem is Row.Note && newItem is Row.Note -> oldItem.text == newItem.text
            oldItem is Row.Entry && newItem is Row.Entry -> oldItem.finding.key == newItem.finding.key
            else -> false
        }

        override fun areContentsTheSame(oldItem: Row, newItem: Row): Boolean = oldItem == newItem
    }

    private companion object {
        /** Modes come from `cmd appops get`; only per-package ops are asked for. */
        const val APPOPS_COMMAND =
            "for p in \$(pm list packages -3 | cut -d: -f2); do " +
                "cmd appops get \$p 2>/dev/null | " +
                "grep -E '^(Uid mode: )?(CAMERA|RECORD_AUDIO|FINE_LOCATION|COARSE_LOCATION|READ_CONTACTS|" +
                "READ_CALL_LOG|READ_SMS|SEND_SMS|READ_CALENDAR|BODY_SENSORS|CALL_PHONE|" +
                "READ_PHONE_NUMBERS|ACTIVITY_RECOGNITION|SYSTEM_ALERT_WINDOW|GET_USAGE_STATS): " +
                "(allow|foreground|ignore|deny|default|ask|errored)' | sed \"s|^|\$p |\"; done"

        const val APPS_COMMAND = "pm list packages -3"

        /** Compact "package permission" lines for granted runtime permissions. */
        const val PERMISSIONS_COMMAND =
            "dumpsys package | awk '/^  Package \\[/{p=\$2; gsub(/[][]/,\"\",p)} " +
                "/^      runtime permissions:/{r=1;next} /^      [^ ]/{r=0} " +
                "r && /granted=true/{split(\$1,a,\":\"); print p\" \"a[1]}'"

        val OP_PERMISSIONS = mapOf(
            "CAMERA" to "android.permission.CAMERA",
            "RECORD_AUDIO" to "android.permission.RECORD_AUDIO",
            "FINE_LOCATION" to "android.permission.ACCESS_FINE_LOCATION",
            "COARSE_LOCATION" to "android.permission.ACCESS_COARSE_LOCATION",
            "READ_CONTACTS" to "android.permission.READ_CONTACTS",
            "READ_CALL_LOG" to "android.permission.READ_CALL_LOG",
            "READ_SMS" to "android.permission.READ_SMS",
            "SEND_SMS" to "android.permission.SEND_SMS",
            "READ_CALENDAR" to "android.permission.READ_CALENDAR",
            "BODY_SENSORS" to "android.permission.BODY_SENSORS",
            "CALL_PHONE" to "android.permission.CALL_PHONE",
            "READ_PHONE_NUMBERS" to "android.permission.READ_PHONE_NUMBERS",
            "ACTIVITY_RECOGNITION" to "android.permission.ACTIVITY_RECOGNITION"
        )

        val SENSITIVE = setOf(
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
            "android.permission.ACCESS_MEDIA_LOCATION",
            "android.permission.READ_CONTACTS",
            "android.permission.READ_CALL_LOG",
            "android.permission.READ_SMS",
            "android.permission.SEND_SMS",
            "android.permission.READ_CALENDAR",
            "android.permission.BODY_SENSORS",
            "android.permission.CALL_PHONE",
            "android.permission.READ_PHONE_NUMBERS",
            "android.permission.ACTIVITY_RECOGNITION",
            "android.permission.MANAGE_EXTERNAL_STORAGE"
        )
    }
}
