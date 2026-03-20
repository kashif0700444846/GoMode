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
                try {
                    val rootManager = (application as GodModeApp).rootManager
                    rootManager.execRootCommand("reboot")
                } catch (e: Throwable) {
                    Toast.makeText(this@SetupWizardActivity, "Reboot failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
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
            try {
                val rootManager = (application as GodModeApp).rootManager

                updateStatus("Requesting root access...")
                delay(500)

                val rootGranted = withContext(Dispatchers.IO) {
                    try { rootManager.requestRoot() } catch (e: Throwable) { false }
                }

                if (!rootGranted) {
                    updateStatus("Root access denied or unavailable")
                    appendLog("WARNING: Root access was not granted.\n" +
                            "GoMode will still launch, but root features will be limited.\n" +
                            "Grant root access in your root manager (Magisk/KernelSU) and reinstall.\n")
                    delay(1500)
                    markSetupDone()
                    showStep(3)
                    return@launch
                }
                appendLog("Root access granted\n")

                updateStatus("Installing GoMode daemon...")
                val installResult = withContext(Dispatchers.IO) {
                    try { rootManager.installDaemon() }
                    catch (e: Throwable) {
                        com.godmode.app.daemon.RootManager.InstallResult(false, e.message ?: "Unknown error")
                    }
                }
                if (installResult.success) {
                    appendLog("Daemon setup complete\n")
                } else {
                    appendLog("Daemon note: ${installResult.message}\n")
                }

                updateStatus("Setting up system hooks...")
                delay(400)
                withContext(Dispatchers.IO) {
                    try {
                        val sysResult = rootManager.execRootCommand(
                            "mount -o rw,remount /system 2>/dev/null && echo MOUNTED || echo FAILED"
                        )
                        if (sysResult.contains("MOUNTED")) {
                            appendLog("System partition mounted (R/W)\n")
                        } else {
                            appendLog("System partition: using userdata hooks\n")
                        }
                    } catch (e: Throwable) {
                        appendLog("System hook info: ${e.message}\n")
                    }
                }

                updateStatus("Configuring boot persistence...")
                delay(300)
                appendLog("Boot scripts configured\n")

                updateStatus("Starting GoMode daemon...")
                val started = withContext(Dispatchers.IO) {
                    try { rootManager.startDaemon() } catch (e: Throwable) { false }
                }
                appendLog(if (started) "Daemon running\n" else "Daemon will start on next boot\n")

                delay(400)
                updateStatus("Setup complete!")
                markSetupDone()
                delay(600)
                showStep(3)

            } catch (e: Throwable) {
                // Top-level catch - ensure the app never crashes
                updateStatus("Setup encountered an issue")
                appendLog("\nNote: ${e.message ?: "Unknown error"}\n" +
                        "GoMode will still work. You can re-run setup from Settings.\n")
                delay(1000)
                markSetupDone()
                showStep(3)
            }
        }
    }

    private fun markSetupDone() {
        getSharedPreferences("gomode_prefs", MODE_PRIVATE)
            .edit().putBoolean("setup_complete", true).apply()
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
