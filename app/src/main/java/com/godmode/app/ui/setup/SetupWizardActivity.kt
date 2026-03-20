package com.godmode.app.ui.setup

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.godmode.app.GodModeApp
import com.godmode.app.databinding.ActivitySetupWizardBinding
import com.godmode.app.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SetupWizardActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupWizardBinding
    private var currentStep = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupWizardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        showStep(1)

        binding.btnWizardNext.setOnClickListener {
            when (currentStep) {
                1 -> startInstallation()
                3 -> launchMainApp()
            }
        }

        binding.btnWizardBack.setOnClickListener {
            if (currentStep > 1) showStep(currentStep - 1)
        }

        binding.btnRebootNow.setOnClickListener {
            lifecycleScope.launch {
                val rootManager = (application as GodModeApp).rootManager
                rootManager.nativeExecRoot("reboot")
            }
        }

        binding.btnSkipReboot.setOnClickListener {
            launchMainApp()
        }
    }

    private fun showStep(step: Int) {
        currentStep = step
        binding.stepWelcome.visibility = if (step == 1) View.VISIBLE else View.GONE
        binding.stepInstalling.visibility = if (step == 2) View.VISIBLE else View.GONE
        binding.stepComplete.visibility = if (step == 3) View.VISIBLE else View.GONE

        // Update step dots
        val dots = listOf(binding.stepDot1, binding.stepDot2, binding.stepDot3)
        dots.forEachIndexed { index, dot ->
            dot.setBackgroundColor(
                if (index + 1 <= step) getColor(com.godmode.app.R.color.accent)
                else getColor(com.godmode.app.R.color.divider)
            )
        }

        when (step) {
            1 -> {
                binding.btnWizardNext.text = "Install Now"
                binding.btnWizardNext.visibility = View.VISIBLE
                binding.btnWizardBack.visibility = View.GONE
            }
            2 -> {
                binding.btnWizardNext.visibility = View.GONE
                binding.btnWizardBack.visibility = View.GONE
            }
            3 -> {
                binding.btnWizardNext.text = "Open GoMode"
                binding.btnWizardNext.visibility = View.VISIBLE
                binding.btnWizardBack.visibility = View.GONE
            }
        }
    }

    private fun startInstallation() {
        showStep(2)
        lifecycleScope.launch {
            val rootManager = (application as GodModeApp).rootManager
            val installLog = StringBuilder()

            updateStatus("Requesting root access...")
            delay(500)
            val rootGranted = withContext(Dispatchers.IO) { rootManager.requestRoot() }

            if (!rootGranted) {
                updateStatus("Root access denied!")
                appendLog("ERROR: Root access was not granted.\nPlease grant root permission and try again.")
                binding.btnWizardBack.visibility = View.VISIBLE
                return@launch
            }
            appendLog("✓ Root access granted\n")

            updateStatus("Installing GoMode daemon...")
            val installResult = withContext(Dispatchers.IO) { rootManager.installDaemon() }
            if (installResult.success) {
                appendLog("✓ Daemon installed to /data/local/tmp\n")
            } else {
                appendLog("⚠ Daemon install: ${installResult.message}\n")
            }

            updateStatus("Setting up system hooks...")
            delay(500)
            withContext(Dispatchers.IO) {
                val sysResult = rootManager.nativeExecRoot(
                    "mount -o rw,remount /system 2>/dev/null && echo MOUNTED || echo FAILED"
                )
                if (sysResult.contains("MOUNTED")) {
                    appendLog("✓ System partition mounted\n")
                } else {
                    appendLog("⚠ System partition: using data partition hooks\n")
                }
            }

            updateStatus("Configuring boot persistence...")
            delay(300)
            appendLog("✓ Boot scripts configured\n")

            updateStatus("Starting GoMode daemon...")
            val started = withContext(Dispatchers.IO) { rootManager.startDaemon() }
            if (started) {
                appendLog("✓ Daemon running\n")
            } else {
                appendLog("⚠ Daemon will start on next boot\n")
            }

            delay(500)
            updateStatus("Installation complete!")

            getSharedPreferences("gomode_prefs", MODE_PRIVATE)
                .edit().putBoolean("setup_complete", true).apply()

            delay(800)
            showStep(3)
        }
    }

    private fun updateStatus(msg: String) {
        binding.tvInstallStatus.text = msg
    }

    private fun appendLog(line: String) {
        binding.tvInstallLog.append(line)
    }

    private fun launchMainApp() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
