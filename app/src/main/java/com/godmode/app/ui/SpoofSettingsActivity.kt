package com.godmode.app.ui

import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.godmode.app.GodModeApp
import com.godmode.app.R
import com.godmode.app.daemon.RootManager
import com.godmode.app.data.repository.GodModeRepository
import com.godmode.app.databinding.ActivitySpoofSettingsBinding
import kotlinx.coroutines.launch

/**
 * Global spoofing settings and device information display.
 * Shows real device identifiers and allows global configuration.
 */
class SpoofSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySpoofSettingsBinding
    private lateinit var repository: GodModeRepository
    private lateinit var rootManager: RootManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySpoofSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Device Information"
        }

        val app = application as GodModeApp
        rootManager = app.rootManager
        repository = GodModeRepository(
            this,
            app.database.appConfigDao(),
            app.database.accessLogDao(),
            rootManager
        )

        loadDeviceInfo()
        setupClickListeners()
    }

    private fun loadDeviceInfo() {
        lifecycleScope.launch {
            val info = repository.getRealDeviceInfo()
            binding.tvImei.text = info.realImei.ifEmpty { "Unknown (requires READ_PHONE_STATE)" }
            binding.tvAndroidId.text = info.realAndroidId.ifEmpty { "Unknown" }
            binding.tvSerial.text = info.realSerial.ifEmpty { "Unknown" }
            binding.tvIpAddress.text = info.realIp.ifEmpty { "Unknown" }
            binding.tvMacAddress.text = info.realMac.ifEmpty { "Unknown" }

            // Additional system info
            loadAdditionalInfo()
        }
    }

    private fun loadAdditionalInfo() {
        lifecycleScope.launch {
            // Get additional device info via root
            val buildProps = rootManager.nativeExecRoot(
                "getprop ro.product.model && getprop ro.product.brand && " +
                "getprop ro.build.fingerprint && getprop ro.serialno"
            )
            val props = buildProps.trim().split("\n")
            binding.tvModel.text = props.getOrNull(0) ?: "Unknown"
            binding.tvBrand.text = props.getOrNull(1) ?: "Unknown"
            binding.tvFingerprint.text = props.getOrNull(2) ?: "Unknown"

            // Network info
            // Network info — no awk/single-quote shell issues
            val networkInfo = rootManager.nativeExecRoot(
                "ip addr show wlan0 2>/dev/null | grep inet | grep -v inet6 | sed s/.*inet// | cut -d/ -f1 | tr -d [:space:]"
            )
            binding.tvNetworkInfo.text = networkInfo.trim().ifEmpty { "Not connected" }

            // SIM info
            val simInfo = rootManager.nativeExecRoot(
                "getprop gsm.sim.operator.numeric 2>/dev/null"
            )
            binding.tvSimOperator.text = simInfo.trim().ifEmpty { "Unknown" }
        }
    }

    private fun setupClickListeners() {
        binding.btnRefreshInfo.setOnClickListener {
            loadDeviceInfo()
        }

        binding.btnCopyImei.setOnClickListener {
            copyToClipboard("IMEI", binding.tvImei.text.toString())
        }

        binding.btnCopyAndroidId.setOnClickListener {
            copyToClipboard("Android ID", binding.tvAndroidId.text.toString())
        }

        binding.btnCopySerial.setOnClickListener {
            copyToClipboard("Serial", binding.tvSerial.text.toString())
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        val clip = android.content.ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "$label copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
