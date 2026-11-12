package moe.shizuku.manager.xion.backup

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.ActivityXionBackupBinding
import moe.shizuku.manager.xion.XionPrefs
import moe.shizuku.manager.xion.XionUi
import moe.shizuku.manager.xion.XionUi.applyScreenInsets

class BackupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityXionBackupBinding

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            BackupManager.importFrom(this@BackupActivity, uri)
                .onSuccess {
                    Toast.makeText(this@BackupActivity, R.string.xion_backup_restored, Toast.LENGTH_SHORT).show()
                    renderHistory()
                }
                .onFailure {
                    Toast.makeText(
                        this@BackupActivity,
                        getString(R.string.xion_backup_failed, it.message ?: "unknown error"),
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityXionBackupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyScreenInsets(binding.root, extraTop = 4, extraBottom = 8)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnExport.setOnClickListener { export() }
        binding.btnImport.setOnClickListener {
            pickFile.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
        renderHistory()
    }

    private fun export() {
        lifecycleScope.launch {
            runCatching { BackupManager.export(this@BackupActivity) }
                .onSuccess { file ->
                    val uri = FileProvider.getUriForFile(
                        this@BackupActivity, "$packageName.fileprovider", file
                    )
                    startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "application/json"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                putExtra(Intent.EXTRA_SUBJECT, "Shizuku Reimagined configuration")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            },
                            getString(R.string.xion_backup_export)
                        )
                    )
                }
                .onFailure {
                    Toast.makeText(
                        this@BackupActivity,
                        getString(R.string.xion_backup_failed, it.message ?: "unknown error"),
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

    private fun renderHistory() {
        val container: LinearLayout = binding.history
        container.removeAllViews()
        val events = XionPrefs.restartHistory(this)
        if (events.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.xion_watchdog_empty_history)
                setTextAppearance(R.style.TextAppearance_Xion_Body_Muted)
                setTextColor(getColor(R.color.xion_text_muted))
            })
            return
        }
        val format = SimpleDateFormat("d MMM HH:mm:ss", Locale.getDefault())
        events.reversed().forEach { event ->
            container.addView(TextView(this).apply {
                text = "${format.format(Date(event.timestamp))} · attempt ${event.attempt} · " +
                    "${if (event.success) "ok" else "failed"} · ${event.reason}"
                setTextAppearance(R.style.TextAppearance_Xion_Mono)
                setTextColor(getColor(if (event.success) R.color.xion_text_body else R.color.xion_negative))
                setPadding(0, XionUi.dp(this@BackupActivity, 4), 0, XionUi.dp(this@BackupActivity, 4))
            })
        }
    }
}
