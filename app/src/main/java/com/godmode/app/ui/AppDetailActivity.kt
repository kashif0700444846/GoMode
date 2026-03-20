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
        binding.btnSave.setOnClickListener {
            saveConfig()
        }

        binding.btnRandomizeAll.setOnClickListener {
            randomizeAll()
        }

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
    }

    private fun saveConfig() {
        val config = collectConfigFromUI()
        lifecycleScope.launch {
            repository.saveConfig(config)
            currentConfig = config
            Toast.makeText(this@AppDetailActivity, "Configuration saved", Toast.LENGTH_SHORT).show()
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
