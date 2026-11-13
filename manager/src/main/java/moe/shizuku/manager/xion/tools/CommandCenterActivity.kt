package moe.shizuku.manager.xion.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.ActivityXionShellBinding
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.xion.XionActivity
import moe.shizuku.manager.xion.XionPrefs
import moe.shizuku.manager.xion.XionUi
import moe.shizuku.manager.xion.XionUi.applyScreenInsets
import moe.shizuku.manager.xion.XionUi.applyTone
import moe.shizuku.manager.xion.shell.PrivilegedShell
import org.json.JSONArray
import org.json.JSONObject

/**
 * Command center: run shell commands through the service, recall what was run before, keep named
 * recipes and search the transcript.
 *
 * History and recipes are plain local records of commands, so they never claim that something ran:
 * only the transcript, which is written from actual command results, does that.
 */
class CommandCenterActivity : XionActivity() {

    data class Recipe(val name: String, val command: String)

    private lateinit var binding: ActivityXionShellBinding
    private val entries = ArrayList<String>()
    private var filterQuery = ""

    private val presets = listOf(
        "pm list packages -3",
        "dumpsys battery",
        "getprop ro.build.version.oneui",
        "settings get global adb_enabled",
        "dumpsys package | grep -c 'granted=true'",
        "ps -A -o PID,NAME | grep shizuku"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityXionShellBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyScreenInsets(binding.root, extraTop = 4, extraBottom = 8)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRun.setOnClickListener { runInput() }
        binding.btnClear.setOnClickListener {
            entries.clear()
            render()
        }
        binding.btnCopy.setOnClickListener { copyTranscript() }

        binding.btnHistory.setOnClickListener { showHistory() }
        binding.btnRecipes.setOnClickListener { showRecipes() }
        binding.btnSaveRecipe.setOnClickListener { askRecipeName() }
        binding.btnFind.setOnClickListener {
            binding.searchRow.isVisible = !binding.searchRow.isVisible
            if (!binding.searchRow.isVisible) {
                binding.searchOutput.setText("")
                filterQuery = ""
                render()
            }
        }

        binding.searchOutput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                filterQuery = s?.toString().orEmpty()
                render()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })

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
        runCommand(command)
    }

    private fun runCommand(command: String) {
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
        entries.add(line)
        render()
    }

    /** The output is rendered from the recorded lines, so a search narrows it without losing them. */
    private fun render() {
        val query = filterQuery.trim().lowercase()
        val visible = if (query.isEmpty()) entries else entries.filter { it.lowercase().contains(query) }
        binding.output.text = visible.joinToString("\n")
        binding.searchCount.isVisible = query.isNotEmpty()
        if (query.isNotEmpty()) {
            binding.searchCount.text = getString(R.string.xion_shell_matches, visible.size)
        }
    }

    private fun copyTranscript() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Shizuku shell", entries.joinToString("\n")))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    // History

    private fun remember(command: String) {
        val list = loadHistory().toMutableList()
        list.remove(command)
        list.add(0, command)
        saveHistory(list.take(MAX_HISTORY))
    }

    private fun showHistory() {
        val history = loadHistory()
        if (history.isEmpty()) {
            Toast.makeText(this, R.string.xion_shell_history_empty, Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.xion_shell_history_title)
            .setItems(history.toTypedArray()) { _, which ->
                binding.input.setText(history[which])
                binding.input.setSelection(history[which].length)
            }
            .setNeutralButton(R.string.xion_shell_history_clear) { _, _ -> saveHistory(emptyList()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun loadHistory(): List<String> {
        val raw = XionPrefs.get(this).getString(KEY_HISTORY, "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { array.optString(it).takeIf { value -> value.isNotBlank() } }
        }.getOrDefault(emptyList())
    }

    private fun saveHistory(list: List<String>) {
        val array = JSONArray()
        list.forEach { array.put(it) }
        XionPrefs.get(this).edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    // Recipes

    private fun askRecipeName() {
        val command = binding.input.text?.toString()?.trim().orEmpty()
        if (command.isEmpty()) {
            Toast.makeText(this, R.string.xion_shell_recipe_empty_command, Toast.LENGTH_SHORT).show()
            return
        }
        val nameInput = EditText(this).apply {
            setText(command.take(40))
            setSelection(text.length)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.xion_shell_recipe_name)
            .setView(nameInput)
            .setPositiveButton(R.string.xion_shell_recipe_save) { _, _ ->
                val name = nameInput.text?.toString()?.trim().orEmpty().ifEmpty { command.take(40) }
                val recipes = loadRecipes().toMutableList()
                recipes.removeAll { it.name == name }
                recipes.add(0, Recipe(name, command))
                saveRecipes(recipes)
                Toast.makeText(this, getString(R.string.xion_shell_recipe_saved, name), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRecipes() {
        val recipes = loadRecipes()
        if (recipes.isEmpty()) {
            Toast.makeText(this, R.string.xion_shell_recipes_empty, Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.xion_shell_recipes_title)
            .setItems(recipes.map { "${it.name}\n${it.command}" }.toTypedArray()) { _, which ->
                showRecipeActions(recipes[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRecipeActions(recipe: Recipe) {
        val options = arrayOf(
            getString(R.string.xion_shell_recipe_run),
            getString(R.string.xion_shell_recipe_insert),
            getString(R.string.xion_shell_recipe_delete)
        )
        // A message and a list together render the message only, so the list is the dialog content.
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.xion_shell_recipe_actions, recipe.name))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        binding.input.setText(recipe.command)
                        runCommand(recipe.command)
                    }
                    1 -> {
                        binding.input.setText(recipe.command)
                        binding.input.setSelection(recipe.command.length)
                    }
                    else -> {
                        saveRecipes(loadRecipes().filterNot { it.name == recipe.name })
                        Toast.makeText(this, R.string.xion_shell_recipe_deleted, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun loadRecipes(): List<Recipe> {
        val raw = XionPrefs.get(this).getString(KEY_RECIPES, "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val name = item.optString("name")
                val command = item.optString("command")
                if (name.isBlank() || command.isBlank()) null else Recipe(name, command)
            }
        }.getOrDefault(emptyList())
    }

    private fun saveRecipes(recipes: List<Recipe>) {
        val array = JSONArray()
        recipes.forEach { recipe ->
            array.put(
                JSONObject().apply {
                    put("name", recipe.name)
                    put("command", recipe.command)
                }
            )
        }
        XionPrefs.get(this).edit().putString(KEY_RECIPES, array.toString()).apply()
    }

    /** Only blocks the handful of patterns that can brick a device; everything else runs. */
    private fun looksDestructive(command: String): Boolean {
        val c = command.lowercase()
        return listOf(
            "rm -rf /", "mkfs", "dd if=", "> /dev/block", "mount -o rw", "pm uninstall --user 0 com.android",
            "settings put global device_provisioned", "reboot bootloader", "flash", "fastboot", "pm disable-user --user 0 com.android.systemui"
        ).any { c.contains(it) }
    }

    private companion object {
        const val KEY_HISTORY = "shell_history"
        const val KEY_RECIPES = "shell_recipes"
        const val MAX_HISTORY = 20
    }
}
