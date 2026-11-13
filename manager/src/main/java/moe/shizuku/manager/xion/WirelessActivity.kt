package moe.shizuku.manager.xion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbPairingTutorialActivity
import moe.shizuku.manager.databinding.ActivityXionWirelessBinding
import moe.shizuku.manager.home.AdbDialogFragment
import moe.shizuku.manager.utils.SettingsHelper
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.xion.XionUi.applyScreenInsets
import rikka.shizuku.Shizuku

/**
 * The wireless path, in one place.
 *
 * Shizuku cannot switch wireless debugging on by itself on Android 11+, and the start itself needs
 * a one-time pairing. Both halves already exist in the app (the pairing foreground service and the
 * mDNS + ADB-protocol starter), so this screen only reports what the device says and hands the
 * work to them — instead of a dialog that only described the situation.
 */
class WirelessActivity : XionActivity() {

    private lateinit var binding: ActivityXionWirelessBinding

    // The pairing code arrives as a notification you reply to, so Android 13+ needs the runtime
    // notification permission before the pairing service can be useful at all.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) toast("Pairing needs notifications to ask for the code")
            openPairingTutorial()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityXionWirelessBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyScreenInsets(binding.root, extraTop = 4)

        binding.btnBack.setOnClickListener { finish() }

        binding.btnDevOptions.setOnClickListener {
            SettingsHelper.launchOrHighlightWirelessDebugging(this)
        }

        binding.btnPair.setOnClickListener {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                toast(getString(R.string.xion_wireless_needs_r))
                return@setOnClickListener
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                openPairingTutorial()
            }
        }

        binding.btnStartWireless.setOnClickListener {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                toast(getString(R.string.xion_wireless_needs_r))
                return@setOnClickListener
            }
            if (!isWirelessDebuggingOn()) {
                toast("Turn on Wireless debugging first")
                SettingsHelper.launchOrHighlightWirelessDebugging(this)
                return@setOnClickListener
            }
            AdbDialogFragment().show(supportFragmentManager, "adb_start")
        }

        binding.btnTcpMode.setOnClickListener {
            ShizukuSettings.setTcpMode(!ShizukuSettings.getTcpMode())
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val wirelessOn = isWirelessDebuggingOn()
        val usbOn = runCatching {
            Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        }.getOrDefault(false)

        val running = ShizukuStateMachine.update() == ShizukuStateMachine.State.RUNNING
        val uid = if (running) runCatching { Shizuku.getUid() }.getOrDefault(-1) else -1

        val port = ShizukuSettings.getTcpPort()
        val tcpMode = ShizukuSettings.getTcpMode()

        setValue(binding.valueWireless, if (wirelessOn) R.string.xion_wireless_on else R.string.xion_wireless_off, wirelessOn)
        setValue(binding.valueUsb, if (usbOn) R.string.xion_wireless_on else R.string.xion_wireless_off, usbOn)
        binding.valueService.text = if (running) {
            getString(R.string.xion_wireless_service_running, uid)
        } else {
            getString(R.string.xion_wireless_service_stopped)
        }
        binding.valueService.setTextColor(
            color(if (running) R.color.xion_positive else R.color.xion_text_muted)
        )
        binding.valuePort.text = if (tcpMode) "$port · TCP" else "$port · discovery"

        binding.tcpBody.text = getString(R.string.xion_wireless_tcp_body, port)
        binding.btnTcpMode.text = getString(
            if (tcpMode) R.string.xion_wireless_tcp_on else R.string.xion_wireless_tcp_off,
            port
        )

        val wirelessPossible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        binding.btnPair.isEnabled = wirelessPossible
        binding.btnStartWireless.isEnabled = wirelessPossible
    }

    private fun setValue(view: TextView, textRes: Int, positive: Boolean) {
        view.setText(textRes)
        view.setTextColor(color(if (positive) R.color.xion_positive else R.color.xion_text_muted))
    }

    private fun openPairingTutorial() {
        startActivity(Intent(this, AdbPairingTutorialActivity::class.java))
    }

    private fun isWirelessDebuggingOn(): Boolean = runCatching {
        Settings.Global.getInt(contentResolver, "adb_wifi_enabled", 0) == 1
    }.getOrDefault(false)

    private fun color(res: Int) = ContextCompat.getColor(this, res)

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
