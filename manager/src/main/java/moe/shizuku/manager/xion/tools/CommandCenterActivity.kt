package moe.shizuku.manager.xion.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.xion.XionActivity
import moe.shizuku.manager.databinding.ActivityXionShellBinding
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.xion.XionPrefs
import moe.shizuku.manager.xion.XionUi
import moe.shizuku.manager.xion.XionUi.applyScreenInsets
import moe.shizuku.manager.xion.XionUi.applyTone
import moe.shizuku.manager.xion.shell.PrivilegedShell
import org.json.JSONArray

class CommandCenterActivity : XionActivity() {

    private lateinit var binding: ActivityXionShellBinding
    private val transcript = StringBuilder()

    private val presets = listOf(
        "pm list packages -3",
        "dumpsys battery",
        "getprop ro.build.version.oneui",
        "settings get global adb_enabled",
        "dumpsys package | grep -c 'granted=true'",
        "ps -A -o PID,NAME | grep shizuku"
    )

    private val historyKey = "shell_history"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityXionShellBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyScreenInsets(binding.root, extraTop = 4, extraBottom = 8)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRun.setOnClickListener { runInput() }
        binding.btnClear.setOnClickListener {
            transcript.clear()
            binding.output.text = ""
        }
        binding.btnCopy.setOnClickListener { copyTranscript() }

        buildPresets()
        updatePrivilegeBanner()

        binding.input.setOnEditorActionListener { _, _, _ ->
            runInput()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        updatePrivilegeBanner()
    }

    private fun updatePrivilegeBanner() {
        val running = ShizukuStateMachine.isRunning()
        val uid = PrivilegedShell.serviceUid()
        when {
            !running -> {
                binding.privilege.text = getString(R.string.xion_shell_privilege_none)
                binding.privilege.applyTone(XionUi.Tone.NEGATIVE)
                binding.btnRun.isEnabled = false
            }
            uid == 0 -> {
                binding.privilege.text = getString(R.string.xion_shell_privilege_root)
                binding.privilege.applyTone(XionUi.Tone.CAUTION)
                binding.btnRun.isEnabled = true
            }
            else -> {
                binding.privilege.text = getString(R.string.xion_shell_privilege_adb, uid)
                binding.privilege.applyTone(XionUi.Tone.BRAND)
                binding.btnRun.isEnabled = true
            }
        }
    }

    private fun buildPresets() {
        binding.presets.removeAllViews()
        presets.forEach { preset ->
            val chip = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = preset
                textSize = 11f
                isAllCaps = false
                cornerRadius = resources.getDimensionPixelSize(R.dimen.xion_radius_pill)
                setPadding(8, 0, 8, 0)
                setOnClickListener {
                    binding.input.setText(preset)
                    binding.input.setSelection(preset.length)
                }
            }
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                XionUi.dp(this, 40)
            )
            lp.marginEnd = XionUi.dp(this, 8)
            binding.presets.addView(chip, lp)
        }
    }

    private fun runInput() {
        val command = binding.input.text?.toString()?.trim().orEmpty()
        if (command.isEmpty()) return

        if (looksDestructive(command)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.xion_shell_danger_title)
                .setMessage(getString(R.string.xion_shell_danger_msg, command))
                .setPositiveButton(R.string.xion_shell_run) { _, _ -> execute(command) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        execute(command)
    }

    private fun execute(command: String) {
        remember(command)
        binding.btnRun.isEnabled = false
        binding.btnRun.setText(R.string.xion_shell_running)
        appendLine("$ $command")

        lifecycleScope.launch {
            val result = PrivilegedShell.run(command)
            appendLine(result.combined)
            appendLine("[exit ${result.exitCode} · ${result.durationMs} ms]")
            appendLine("")
            binding.btnRun.isEnabled = true
            binding.btnRun.setText(R.string.xion_shell_run)
            binding.outputScroll.post { binding.outputScroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }

    private fun appendLine(line: String) {
        if (transcript.isNotEmpty()) transcript.append('\n')
        transcript.append(line)
        binding.output.text = transcript.toString()
    }

    private fun copyTranscript() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Shizuku shell", transcript.toString()))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun remember(command: String) {
        val prefs = XionPrefs.get(this)
        val existing = runCatching { JSONArray(prefs.getString(historyKey, "[]")) }.getOrDefault(JSONArray())
        val list = (0 until existing.length()).mapNotNull { existing.optString(it) }.toMutableList()
        list.remove(command)
        list.add(0, command)
        val trimmed = list.take(20)
        val array = JSONArray()
        trimmed.forEach { array.put(it) }
        prefs.edit().putString(historyKey, array.toString()).apply()
    }

    /** Only blocks the handful of patterns that can brick a device; everything else runs. */
    private fun looksDestructive(command: String): Boolean {
        val c = command.lowercase()
        return listOf(
            "rm -rf /", "mkfs", "dd if=", "> /dev/block", "mount -o rw", "pm uninstall --user 0 com.android",
            "settings put global device_provisioned", "reboot bootloader", "flash", "fastboot", "pm disable-user --user 0 com.android.systemui"
        ).any { c.contains(it) }
    }
}
