package moe.shizuku.manager.xion.tools

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.ActivityXionTweaksBinding
import moe.shizuku.manager.xion.XionUi
import moe.shizuku.manager.xion.XionUi.applyScreenInsets
import moe.shizuku.manager.xion.shell.PrivilegedShell

/**
 * Only settings that are read back after writing are shown, so the displayed value is always
 * what the device actually holds. Anything that cannot be read is reported as unavailable
 * instead of being offered as if it worked.
 */
class TweaksActivity : AppCompatActivity() {

    private data class Tweak(
        val namespace: String,
        val key: String,
        val title: String,
        val description: String,
        val choices: List<Pair<String, String>>
    )

    private lateinit var binding: ActivityXionTweaksBinding

    private val tweaks = listOf(
        Tweak(
            "global", "window_animation_scale", "Window animation scale",
            "Speed of window open and close animations.",
            listOf("0" to "Off", "0.5" to "0.5×", "1" to "1× (default)", "1.5" to "1.5×")
        ),
        Tweak(
            "global", "transition_animation_scale", "Transition animation scale",
            "Speed of activity transition animations.",
            listOf("0" to "Off", "0.5" to "0.5×", "1" to "1× (default)", "1.5" to "1.5×")
        ),
        Tweak(
            "global", "animator_duration_scale", "Animator duration scale",
            "Speed of property animator durations.",
            listOf("0" to "Off", "0.5" to "0.5×", "1" to "1× (default)", "1.5" to "1.5×")
        ),
        Tweak(
            "system", "screen_off_timeout", "Screen timeout",
            "Time before the display turns off, in milliseconds.",
            listOf("15000" to "15 s", "30000" to "30 s", "60000" to "1 min", "300000" to "5 min", "600000" to "10 min")
        ),
        Tweak(
            "system", "show_touches", "Show taps",
            "Draws a dot wherever the screen is touched (useful for screen recordings).",
            listOf("0" to "Off", "1" to "On")
        ),
        Tweak(
            "system", "pointer_location", "Pointer location",
            "Shows touch coordinates across the top of the screen.",
            listOf("0" to "Off", "1" to "On")
        ),
        Tweak(
            "global", "private_dns_mode", "Private DNS mode",
            "Controls DNS-over-TLS. 'Automatic' uses the network's DNS if it supports it.",
            listOf("off" to "Off", "opportunistic" to "Automatic", "hostname" to "Hostname (set in Settings)")
        )
    )

    private val current = HashMap<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityXionTweaksBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyScreenInsets(binding.root, extraTop = 4, extraBottom = 8)

        binding.btnBack.setOnClickListener { finish() }
        load()
    }

    private fun load() {
        if (!PrivilegedShell.isAvailable()) {
            binding.tweaks.removeAllViews()
            binding.tweaks.addView(label(getString(R.string.xion_tools_requires_service)))
            return
        }
        lifecycleScope.launch {
            val command = tweaks.joinToString("; ") { "echo \"${it.namespace}/${it.key}=\$(settings get ${it.namespace} ${it.key})\"" }
            val result = PrivilegedShell.run(command)
            current.clear()
            result.stdout.lineSequence().forEach { line ->
                val idx = line.indexOf('=')
                if (idx > 0) current[line.substring(0, idx)] = line.substring(idx + 1).trim()
            }
            render()
        }
    }

    private fun render() {
        binding.tweaks.removeAllViews()
        tweaks.forEach { tweak ->
            val key = "${tweak.namespace}/${tweak.key}"
            val value = current[key].orEmpty()
            val available = value.isNotEmpty() && value != "null"

            val card = MaterialCardView(this).apply {
                setCardBackgroundColor(getColor(R.color.xion_surface_raised))
                radius = resources.getDimension(R.dimen.xion_radius_lg)
                strokeWidth = XionUi.dp(this@TweaksActivity, 1)
                strokeColor = getColor(R.color.xion_hairline)
                cardElevation = 0f
            }
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(XionUi.dp(this@TweaksActivity, 16), XionUi.dp(this@TweaksActivity, 14),
                    XionUi.dp(this@TweaksActivity, 16), XionUi.dp(this@TweaksActivity, 14))
            }

            content.addView(TextView(this).apply {
                text = tweak.title
                setTextAppearance(R.style.TextAppearance_Xion_Title)
                setTextColor(getColor(R.color.xion_text))
            })
            content.addView(TextView(this).apply {
                text = tweak.description
                setTextAppearance(R.style.TextAppearance_Xion_Body_Muted)
                setTextColor(getColor(R.color.xion_text_muted))
                setPadding(0, XionUi.dp(this@TweaksActivity, 2), 0, XionUi.dp(this@TweaksActivity, 8))
            })
            content.addView(TextView(this).apply {
                text = if (available) getString(R.string.xion_tweaks_current, value)
                else getString(R.string.xion_tweaks_unavailable)
                setTextAppearance(R.style.TextAppearance_Xion_Mono)
                setTextColor(if (available) getColor(R.color.xion_brand_hi) else getColor(R.color.xion_negative))
                setPadding(0, 0, 0, XionUi.dp(this@TweaksActivity, 8))
            })

            if (available) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.START
                }
                tweak.choices.forEach { (raw, display) ->
                    val selected = value == raw || (raw == "1" && value == "true") || (raw == "0" && value == "false")
                    val button = MaterialButton(
                        this,
                        null,
                        if (selected) com.google.android.material.R.attr.materialButtonOutlinedStyle
                        else com.google.android.material.R.attr.materialButtonOutlinedStyle
                    ).apply {
                        text = display
                        textSize = 11f
                        isAllCaps = false
                        cornerRadius = resources.getDimensionPixelSize(R.dimen.xion_radius_pill)
                        if (selected) {
                            setTextColor(getColor(R.color.xion_on_accent))
                            backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.xion_brand))
                        }
                        setOnClickListener { apply(tweak, raw) }
                    }
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        XionUi.dp(this@TweaksActivity, 38)
                    )
                    lp.marginEnd = XionUi.dp(this@TweaksActivity, 6)
                    row.addView(button, lp)
                }
                content.addView(row)
            }

            card.addView(content)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = XionUi.dp(this, 10)
            binding.tweaks.addView(card, lp)
        }

        binding.tweaks.addView(TextView(this).apply {
            text = "Not every setting exists on every manufacturer's build. Values shown here were read back from the device after the last change."
            setTextAppearance(R.style.TextAppearance_Xion_Body_Muted)
            setTextColor(getColor(R.color.xion_text_muted))
            setPadding(0, XionUi.dp(this@TweaksActivity, 8), 0, 0)
        })
    }

    private fun apply(tweak: Tweak, value: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(tweak.title)
            .setMessage("Set ${tweak.namespace}/${tweak.key} to \"$value\"?")
            .setPositiveButton(R.string.xion_tweaks_apply) { _, _ ->
                lifecycleScope.launch {
                    val result = PrivilegedShell.run("settings put ${tweak.namespace} ${tweak.key} $value")
                    Toast.makeText(
                        this@TweaksActivity,
                        if (result.ok) "Applied" else result.combined,
                        Toast.LENGTH_SHORT
                    ).show()
                    load()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextAppearance(R.style.TextAppearance_Xion_Body)
        setTextColor(getColor(R.color.xion_text_body))
        setPadding(0, XionUi.dp(this@TweaksActivity, 16), 0, 0)
    }
}
