package moe.shizuku.manager.xion.diag

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import java.io.File
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.ActivityXionDiagnosticsBinding
import moe.shizuku.manager.xion.XionUi
import moe.shizuku.manager.xion.XionUi.applyScreenInsets

class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityXionDiagnosticsBinding
    private var report: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityXionDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyScreenInsets(binding.root, extraTop = 4, extraBottom = 8)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRefresh.setOnClickListener { collect() }
        binding.btnCopy.setOnClickListener { copyReport() }
        binding.btnExport.setOnClickListener { exportReport() }

        collect()
    }

    private fun collect() {
        binding.sections.removeAllViews()
        binding.sections.addView(note("Collecting…"))
        lifecycleScope.launch {
            val sections = DiagnosticsCollector.collect(this@DiagnosticsActivity)
            report = DiagnosticsCollector.render(sections)
            binding.sections.removeAllViews()
            sections.forEach { renderSection(it) }
        }
    }

    private fun renderSection(section: DiagnosticsCollector.Section) {
        val card = MaterialCardView(this).apply {
            setCardBackgroundColor(getColor(R.color.xion_surface_raised))
            radius = resources.getDimension(R.dimen.xion_radius_lg)
            strokeWidth = XionUi.dp(this@DiagnosticsActivity, 1)
            strokeColor = getColor(R.color.xion_hairline)
            cardElevation = 0f
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(XionUi.dp(this@DiagnosticsActivity, 16), XionUi.dp(this@DiagnosticsActivity, 14),
                XionUi.dp(this@DiagnosticsActivity, 16), XionUi.dp(this@DiagnosticsActivity, 14))
        }
        content.addView(TextView(this).apply {
            text = section.title.uppercase()
            setTextAppearance(R.style.TextAppearance_Xion_Overline)
            setTextColor(getColor(R.color.xion_brand_hi))
            setPadding(0, 0, 0, XionUi.dp(this@DiagnosticsActivity, 6))
        })
        section.rows.forEach { (key, value) ->
            content.addView(TextView(this).apply {
                text = "$key  ·  $value"
                setTextAppearance(R.style.TextAppearance_Xion_Mono)
                setTextColor(getColor(R.color.xion_text_body))
                setPadding(0, XionUi.dp(this@DiagnosticsActivity, 2), 0, XionUi.dp(this@DiagnosticsActivity, 2))
                setTextIsSelectable(true)
            })
        }
        card.addView(content)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = XionUi.dp(this, 10)
        binding.sections.addView(card, lp)
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        setTextAppearance(R.style.TextAppearance_Xion_Body_Muted)
        setTextColor(getColor(R.color.xion_text_muted))
    }

    private fun copyReport() {
        if (report.isEmpty()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Shizuku diagnostic report", report))
        Toast.makeText(this, "Report copied", Toast.LENGTH_SHORT).show()
    }

    private fun exportReport() {
        if (report.isEmpty()) return
        val file = File(cacheDir, "shizuku-diagnostics.txt")
        file.writeText(report)
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Shizuku Reimagined diagnostic report")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                getString(R.string.xion_diag_export)
            )
        )
    }
}
