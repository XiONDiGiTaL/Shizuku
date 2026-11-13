package moe.shizuku.manager.xion.tools

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import androidx.core.content.ContextCompat
import moe.shizuku.manager.R
import moe.shizuku.manager.xion.XionActivity
import moe.shizuku.manager.databinding.ActivityXionToolsBinding
import moe.shizuku.manager.databinding.XionNavRowBinding
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.xion.XionUi.applyScreenInsets
import moe.shizuku.manager.xion.security.SecurityCenterActivity

class ToolsActivity : XionActivity() {

    private lateinit var binding: ActivityXionToolsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityXionToolsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyScreenInsets(binding.root, extraTop = 4, extraBottom = 16)

        binding.btnBack.setOnClickListener { finish() }

        row(
            binding.navSecurity,
            R.drawable.ic_x_shield,
            R.string.xion_tools_security,
            R.string.xion_tools_security_sub
        ) { startActivity(Intent(this, SecurityCenterActivity::class.java)) }

        row(
            binding.navPackages,
            R.drawable.ic_x_apps,
            R.string.xion_tools_packages,
            R.string.xion_tools_packages_sub
        ) { startActivity(Intent(this, AppManagerActivity::class.java)) }

        row(
            binding.navAppOps,
            R.drawable.ic_x_shield,
            R.string.xion_tools_appops,
            R.string.xion_tools_appops_sub
        ) { startActivity(Intent(this, AppOpsActivity::class.java)) }

        row(
            binding.navPermissions,
            R.drawable.ic_x_check,
            R.string.xion_tools_permissions,
            R.string.xion_tools_permissions_sub
        ) { startActivity(Intent(this, PermissionInspectorActivity::class.java)) }

        row(
            binding.navComponents,
            R.drawable.ic_x_bolt,
            R.string.xion_tools_components,
            R.string.xion_tools_components_sub
        ) { startActivity(Intent(this, ComponentManagerActivity::class.java)) }

        row(
            binding.navTweaks,
            R.drawable.ic_x_gear,
            R.string.xion_tools_tweaks,
            R.string.xion_tools_tweaks_sub
        ) { startActivity(Intent(this, TweaksActivity::class.java)) }

        row(
            binding.navOptimize,
            R.drawable.ic_x_chart,
            R.string.xion_tools_optimize,
            R.string.xion_tools_optimize_sub
        ) { startActivity(Intent(this, OptimizationCenterActivity::class.java)) }

        row(
            binding.navSnapshots,
            R.drawable.ic_x_clock,
            R.string.xion_tools_snapshots,
            R.string.xion_tools_snapshots_sub
        ) { startActivity(Intent(this, SnapshotsActivity::class.java)) }

        row(
            binding.navShell,
            R.drawable.ic_x_terminal,
            R.string.xion_tools_shell,
            R.string.xion_tools_shell_sub
        ) { startActivity(Intent(this, CommandCenterActivity::class.java)) }
    }

    override fun onResume() {
        super.onResume()
        val running = ShizukuStateMachine.isRunning()
        binding.statusNote.text = if (running) {
            "Service running, tools will execute with ADB privileges."
        } else {
            getString(R.string.xion_tools_requires_service)
        }
    }

    private fun row(
        row: XionNavRowBinding,
        icon: Int,
        title: Int,
        subtitle: Int,
        onClick: () -> Unit
    ) {
        row.icon.setImageResource(icon)
        row.icon.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.xion_brand_hi)
        )
        row.title.setText(title)
        row.subtitle.setText(subtitle)
        row.root.setOnClickListener { onClick() }
    }
}
