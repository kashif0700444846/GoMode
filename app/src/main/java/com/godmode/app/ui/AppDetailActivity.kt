package com.godmode.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.godmode.app.GodModeApp
import com.godmode.app.R
import com.godmode.app.data.db.GodModeDatabase
import com.godmode.app.data.model.AppConfig
import com.godmode.app.data.repository.GodModeRepository
import com.godmode.app.databinding.ActivityAppDetailBinding
import kotlinx.coroutines.launch

/**
 * Shows detailed spoofing configuration and access logs for a specific app.
 */
class AppDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_APP_NAME = "app_name"
    }

    private lateinit var binding: ActivityAppDetailBinding
    private lateinit var repository: GodModeRepository
    private var packageName: String = ""
    private var appName: String = ""
    private var currentConfig: AppConfig? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: run {
            finish()
            return
        }
        appName = intent.getStringExtra(EXTRA_APP_NAME) ?: packageName

        setupToolbar()
        setupRepository()
        loadConfig()
        setupClickListeners()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = appName
            subtitle = packageName
        }
    }

    private fun setupRepository() {
        val app = application as GodModeApp
        repository = GodModeRepository(
            this,
            app.database.appConfigDao(),
            app.database.accessLogDao(),
            app.rootManager
        )
    }

    private fun loadConfig() {
        lifecycleScope.launch {
            val config = repository.getConfigForPackage(packageName)
                ?: AppConfig(packageName = packageName, appName = appName)
            currentConfig = config
            updateUI(config)
        }
    }

    private fun updateUI(config: AppConfig) {
        binding.switchActive.isChecked = config.isActive

        // IMEI
        binding.spinnerImeiMode.setSelection(config.imeiMode)
        binding.etCustomImei.setText(config.customImei)

        // Android ID
        binding.spinnerAndroidIdMode.setSelection(config.androidIdMode)
        binding.etCustomAndroidId.setText(config.customAndroidId)

        // Serial
        binding.spinnerSerialMode.setSelection(config.serialMode)
        binding.etCustomSerial.setText(config.customSerial)

        // Location
        binding.spinnerLocationMode.setSelection(config.locationMode)
        binding.etCustomLat.setText(config.customLat.toString())
        binding.etCustomLon.setText(config.customLon.toString())

        // IP
        binding.spinnerIpMode.setSelection(config.ipMode)
        binding.etCustomIp.setText(config.customIp)

        // MAC
        binding.spinnerMacMode.setSelection(config.macMode)
        binding.etCustomMac.setText(config.customMac)

        // Phone
        binding.spinnerPhoneMode.setSelection(config.phoneMode)
        binding.etCustomPhone.setText(config.customPhone)

        // Build props
        binding.spinnerBuildMode.setSelection(config.buildMode)
        binding.etCustomModel.setText(config.customModel)
        binding.etCustomBrand.setText(config.customBrand)

        // Operator
        binding.spinnerOperatorMode.setSelection(config.operatorMode)
        binding.etCustomMccMnc.setText(config.customMccMnc)

        // Advertising ID
        binding.spinnerAdIdMode.setSelection(config.adIdMode)
        binding.etCustomAdId.setText(config.customAdId)

        // Access controls
        binding.switchBlockCamera.isChecked = config.blockCamera
        binding.switchBlockMic.isChecked = config.blockMicrophone
        binding.switchBlockContacts.isChecked = config.blockContacts
        binding.switchBlockCalendar.isChecked = config.blockCalendar
        binding.switchBlockClipboard.isChecked = config.blockClipboard
        binding.switchBlockSensors.isChecked = config.blockSensors
    }

    private fun collectConfigFromUI(): AppConfig {
        val config = currentConfig ?: AppConfig(packageName = packageName, appName = appName)
        return config.copy(
            isActive = binding.switchActive.isChecked,
            imeiMode = binding.spinnerImeiMode.selectedItemPosition,
            customImei = binding.etCustomImei.text.toString(),
            androidIdMode = binding.spinnerAndroidIdMode.selectedItemPosition,
            customAndroidId = binding.etCustomAndroidId.text.toString(),
            serialMode = binding.spinnerSerialMode.selectedItemPosition,
            customSerial = binding.etCustomSerial.text.toString(),
            locationMode = binding.spinnerLocationMode.selectedItemPosition,
            customLat = binding.etCustomLat.text.toString().toDoubleOrNull() ?: 0.0,
            customLon = binding.etCustomLon.text.toString().toDoubleOrNull() ?: 0.0,
            ipMode = binding.spinnerIpMode.selectedItemPosition,
            customIp = binding.etCustomIp.text.toString(),
            macMode = binding.spinnerMacMode.selectedItemPosition,
            customMac = binding.etCustomMac.text.toString(),
            phoneMode = binding.spinnerPhoneMode.selectedItemPosition,
            customPhone = binding.etCustomPhone.text.toString(),
            buildMode = binding.spinnerBuildMode.selectedItemPosition,
            customModel = binding.etCustomModel.text.toString(),
            customBrand = binding.etCustomBrand.text.toString(),
            operatorMode = binding.spinnerOperatorMode.selectedItemPosition,
            customMccMnc = binding.etCustomMccMnc.text.toString(),
            adIdMode = binding.spinnerAdIdMode.selectedItemPosition,
            customAdId = binding.etCustomAdId.text.toString(),
            blockCamera = binding.switchBlockCamera.isChecked,
            blockMicrophone = binding.switchBlockMic.isChecked,
            blockContacts = binding.switchBlockContacts.isChecked,
            blockCalendar = binding.switchBlockCalendar.isChecked,
            blockClipboard = binding.switchBlockClipboard.isChecked,
            blockSensors = binding.switchBlockSensors.isChecked,
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun setupClickListeners() {
        binding.btnSave.setOnClickListener { saveConfig() }

        binding.btnRandomizeAll.setOnClickListener { randomizeAll() }

        binding.btnViewLogs.setOnClickListener {
            val intent = Intent(this, LogDetailActivity::class.java).apply {
                putExtra(LogDetailActivity.EXTRA_PACKAGE_NAME, packageName)
                putExtra(LogDetailActivity.EXTRA_APP_NAME, appName)
            }
            startActivity(intent)
        }

        binding.btnClearLogs.setOnClickListener {
            lifecycleScope.launch {
                repository.clearLogsForPackage(packageName)
                Toast.makeText(this@AppDetailActivity, "Logs cleared", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnDelete.setOnClickListener {
            lifecycleScope.launch {
                repository.deleteConfig(packageName)
                Toast.makeText(this@AppDetailActivity, "Config deleted", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        // App management buttons
        binding.btnForceStop.setOnClickListener {
            lifecycleScope.launch {
                val rm = (application as GodModeApp).rootManager
                val ok = rm.forceStopApp(packageName)
                Toast.makeText(this@AppDetailActivity, if (ok) "App force stopped" else "Failed - grant root", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnClearCache.setOnClickListener {
            lifecycleScope.launch {
                val rm = (application as GodModeApp).rootManager
                rm.clearAppCache(packageName)
                Toast.makeText(this@AppDetailActivity, "Cache cleared", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnClearData.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Clear App Data")
                .setMessage("Reset ${appName} completely? All data will be lost.")
                .setPositiveButton("Clear") { _, _ ->
                    lifecycleScope.launch {
                        val rm = (application as GodModeApp).rootManager
                        val ok = rm.clearAppData(packageName)
                        Toast.makeText(this@AppDetailActivity, if (ok) "Data cleared" else "Failed", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null).show()
        }

        binding.btnOpenApp.setOnClickListener {
            try {
                val intent = packageManager.getLaunchIntentForPackage(packageName)
                if (intent != null) startActivity(intent)
                else Toast.makeText(this, "Cannot launch this app", Toast.LENGTH_SHORT).show()
            } catch (e: Throwable) {
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnViewPermissions.setOnClickListener {
            lifecycleScope.launch {
                val rm = (application as GodModeApp).rootManager
                val perms = rm.getAppPermissions(packageName)
                val ops = rm.getAppOps(packageName)
                showInfoDialog("Permissions & App Ops", "GRANTED PERMISSIONS:\n$perms\n\nAPP OPERATIONS LOG:\n$ops")
            }
        }
    }

    private fun showInfoDialog(title: String, content: String) {
        val scrollView = android.widget.ScrollView(this)
        val tv = android.widget.TextView(this).apply {
            text = content.ifEmpty { "No data available" }
            textSize = 11f
            setPadding(32, 16, 32, 16)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        scrollView.addView(tv)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun saveConfig() {
        val config = collectConfigFromUI()
        lifecycleScope.launch {
            repository.saveConfig(config)
            // Write to XSharedPreferences for Xposed module
            saveXposedPrefs(config)
            val rootManager = (application as GodModeApp).rootManager
            rootManager.notifyConfigUpdate(packageName)
            currentConfig = config
            Toast.makeText(this@AppDetailActivity, "Configuration saved - active hooks applied", Toast.LENGTH_SHORT).show()
        }
    }

    /** Write config to SharedPreferences that the Xposed module reads via XSharedPreferences */
    private fun saveXposedPrefs(config: AppConfig) {
        val prefs = getSharedPreferences("xposed_${config.packageName}", MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean("active", config.isActive)
            putInt("imei_mode", config.imeiMode)
            putString("imei_value", config.customImei)
            putInt("imsi_mode", config.imsiMode)
            putString("imsi_value", config.customImsi)
            putInt("android_id_mode", config.androidIdMode)
            putString("android_id_value", config.customAndroidId)
            putInt("serial_mode", config.serialMode)
            putString("serial_value", config.customSerial)
            putInt("mac_mode", config.macMode)
            putString("mac_value", config.customMac)
            putInt("build_mode", config.buildMode)
            putString("build_model", config.customModel)
            putString("build_brand", config.customBrand)
            putString("build_fingerprint", config.customFingerprint)
            putString("build_manufacturer", config.customBrand)
            putInt("location_mode", config.locationMode)
            putFloat("location_lat", config.customLat.toFloat())
            putFloat("location_lon", config.customLon.toFloat())
            putBoolean("block_camera", config.blockCamera)
            putBoolean("block_mic", config.blockMicrophone)
            putInt("ad_id_mode", config.adIdMode)
            putString("ad_id_value", config.customAdId)
        }.apply()

        saveRootReadableFallbackConfig(config)

        // Make prefs world-readable for XSharedPreferences (LSPosed will also handle this)
        lifecycleScope.launch {
            try {
                val rootManager = (application as GodModeApp).rootManager
                rootManager.execRootCommand(
                    "chmod 644 /data/data/com.godmode.app/shared_prefs/xposed_${config.packageName}.xml 2>/dev/null"
                )
            } catch (_: Throwable) {}
        }

        // Apply permission changes immediately via pm
        if (config.isActive) {
            lifecycleScope.launch {
                val rootManager = (application as GodModeApp).rootManager
                if (config.blockCamera) {
                    rootManager.revokePermission(packageName, "android.permission.CAMERA")
                } else {
                    rootManager.grantPermission(packageName, "android.permission.CAMERA")
                }
                if (config.blockMicrophone) {
                    rootManager.revokePermission(packageName, "android.permission.RECORD_AUDIO")
                } else {
                    rootManager.grantPermission(packageName, "android.permission.RECORD_AUDIO")
                }
            }
        }
    }

    private fun saveRootReadableFallbackConfig(config: AppConfig) {
        lifecycleScope.launch {
            try {
                val rootManager = (application as GodModeApp).rootManager
                val safePkg = config.packageName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val configPath = "/data/local/tmp/gomode/config/$safePkg.conf"

                val serialized = buildString {
                    appendLine("active=${if (config.isActive) 1 else 0}")
                    appendLine("imei_mode=${config.imeiMode}")
                    appendLine("imei_value=${config.customImei}")
                    appendLine("imsi_mode=${config.imsiMode}")
                    appendLine("imsi_value=${config.customImsi}")
                    appendLine("android_id_mode=${config.androidIdMode}")
                    appendLine("android_id_value=${config.customAndroidId}")
                    appendLine("serial_mode=${config.serialMode}")
                    appendLine("serial_value=${config.customSerial}")
                    appendLine("mac_mode=${config.macMode}")
                    appendLine("mac_value=${config.customMac}")
                    appendLine("build_mode=${config.buildMode}")
                    appendLine("build_model=${config.customModel}")
                    appendLine("build_brand=${config.customBrand}")
                    appendLine("build_fingerprint=${config.customFingerprint}")
                    appendLine("build_manufacturer=${config.customBrand}")
                    appendLine("location_mode=${config.locationMode}")
                    appendLine("location_lat=${config.customLat}")
                    appendLine("location_lon=${config.customLon}")
                    appendLine("block_camera=${if (config.blockCamera) 1 else 0}")
                    appendLine("block_mic=${if (config.blockMicrophone) 1 else 0}")
                    appendLine("ad_id_mode=${config.adIdMode}")
                    appendLine("ad_id_value=${config.customAdId}")
                }

                val escaped = serialized
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("$", "\\$")

                rootManager.execRootCommand(
                    "mkdir -p /data/local/tmp/gomode/config && " +
                            "printf \"$escaped\" > '$configPath' && chmod 644 '$configPath'",
                    10
                )
            } catch (_: Throwable) {
            }
        }
    }

    private fun randomizeAll() {
        binding.spinnerImeiMode.setSelection(AppConfig.MODE_RANDOM)
        binding.spinnerAndroidIdMode.setSelection(AppConfig.MODE_RANDOM)
        binding.spinnerSerialMode.setSelection(AppConfig.MODE_RANDOM)
        binding.spinnerLocationMode.setSelection(AppConfig.MODE_RANDOM)
        binding.spinnerIpMode.setSelection(AppConfig.MODE_RANDOM)
        binding.spinnerMacMode.setSelection(AppConfig.MODE_RANDOM)
        binding.spinnerAdIdMode.setSelection(AppConfig.MODE_RANDOM)
        Toast.makeText(this, "All identifiers set to random. Save to apply.", Toast.LENGTH_SHORT).show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
