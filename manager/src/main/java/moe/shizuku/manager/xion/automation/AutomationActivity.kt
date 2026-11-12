package moe.shizuku.manager.xion.automation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.databinding.ActivityXionAutomationBinding
import moe.shizuku.manager.xion.XionUi
import moe.shizuku.manager.xion.XionUi.applyScreenInsets

class AutomationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityXionAutomationBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityXionAutomationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyScreenInsets(binding.root, extraTop = 4, extraBottom = 8)

        binding.btnBack.setOnClickListener { finish() }
        binding.token.text = ShizukuSettings.getAuthToken()
        binding.btnCopyToken.setOnClickListener {
            copy(ShizukuSettings.getAuthToken(), "Token copied")
        }

        render()
    }

    private fun render() {
        val container: LinearLayout = binding.sections
        container.removeAllViews()

        container.addView(sectionTitle("CONTROL INTENTS"))
        AutomationContract.describe().forEach { action ->
            container.addView(card(action, sendable = true))
        }

        container.addView(sectionTitle("BROADCAST EVENTS"))
        AutomationContract.describeEvents().forEach { action ->
            container.addView(card(action, sendable = false))
        }

        container.addView(TextView(this).apply {
            text = "Example (Tasker / adb):\n" +
                "${AutomationContract.adbExample(AutomationContract.ACTION_WATCHDOG)} --ez enabled true --es auth TOKEN\n\n" +
                "Events are emitted while this app or the watchdog is running. Enable the watchdog if you need them continuously."
            setTextAppearance(R.style.TextAppearance_Xion_Mono)
            setTextColor(getColor(R.color.xion_text_muted))
            setTextIsSelectable(true)
            setPadding(0, XionUi.dp(this@AutomationActivity, 12), 0, 0)
        })
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        setTextAppearance(R.style.TextAppearance_Xion_Overline)
        setTextColor(getColor(R.color.xion_brand_hi))
        setPadding(0, XionUi.dp(this@AutomationActivity, 14), 0, XionUi.dp(this@AutomationActivity, 6))
    }

    private fun card(action: AutomationContract.AutomationAction, sendable: Boolean): MaterialCardView {
        val card = MaterialCardView(this).apply {
            setCardBackgroundColor(getColor(R.color.xion_surface_raised))
            radius = resources.getDimension(R.dimen.xion_radius_lg)
            strokeWidth = XionUi.dp(this@AutomationActivity, 1)
            strokeColor = getColor(R.color.xion_hairline)
            cardElevation = 0f
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(XionUi.dp(this@AutomationActivity, 16), XionUi.dp(this@AutomationActivity, 12),
                XionUi.dp(this@AutomationActivity, 16), XionUi.dp(this@AutomationActivity, 12))
        }
        content.addView(TextView(this).apply {
            text = action.title
            setTextAppearance(R.style.TextAppearance_Xion_Title)
            setTextColor(getColor(R.color.xion_text))
            textSize = 15f
        })
        content.addView(TextView(this).apply {
            text = action.action
            setTextAppearance(R.style.TextAppearance_Xion_Mono)
            setTextColor(getColor(R.color.xion_brand_hi))
            setPadding(0, XionUi.dp(this@AutomationActivity, 2), 0, XionUi.dp(this@AutomationActivity, 4))
            setTextIsSelectable(true)
        })
        content.addView(TextView(this).apply {
            text = action.description
            setTextAppearance(R.style.TextAppearance_Xion_Body_Muted)
            setTextColor(getColor(R.color.xion_text_muted))
        })
        if (action.extras.isNotEmpty()) {
            content.addView(TextView(this).apply {
                text = action.extras.joinToString("\n")
                setTextAppearance(R.style.TextAppearance_Xion_Mono)
                setTextColor(getColor(R.color.xion_text_body))
                setPadding(0, XionUi.dp(this@AutomationActivity, 4), 0, 0)
            })
        }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.xion_auto_copy)
            textSize = 11f
            isAllCaps = false
            cornerRadius = resources.getDimensionPixelSize(R.dimen.xion_radius_pill)
            setOnClickListener { copy(action.action, "Action copied") }
        })
        if (sendable) {
            val test = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = getString(R.string.xion_auto_test)
                textSize = 11f
                isAllCaps = false
                cornerRadius = resources.getDimensionPixelSize(R.dimen.xion_radius_pill)
                setOnClickListener { confirmSend(action) }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginStart = XionUi.dp(this@AutomationActivity, 8)
            row.addView(test, lp)
        }
        content.addView(row)
        card.addView(content)

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = XionUi.dp(this, 8)
        card.layoutParams = lp
        return card
    }

    private fun confirmSend(action: AutomationContract.AutomationAction) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Send ${action.title}?")
            .setMessage("This broadcasts the real control intent to this app now.")
            .setPositiveButton(R.string.xion_auto_test) { _, _ ->
                val intent = Intent(action.action).setPackage(packageName)
                    .putExtra("auth", ShizukuSettings.getAuthToken())
                if (action.action == AutomationContract.ACTION_WATCHDOG) {
                    intent.putExtra(AutomationContract.EXTRA_ENABLED, !ShizukuSettings.getWatchdog())
                }
                sendBroadcast(intent)
                Toast.makeText(this, "Broadcast sent", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun copy(text: String, message: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Shizuku automation", text))
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
