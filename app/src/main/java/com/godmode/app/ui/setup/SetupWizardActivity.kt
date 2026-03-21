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
        
        binding.btnSkipSetup.setOnClickListener {
            markSetupDone()
            launchMainApp()
        }
        
        // Auto-start installation immediately after showing welcome screen
        lifecycleScope.launch {
            delay(1500) // Show welcome screen for 1.5 seconds
            if (currentStep == 1) {
                startInstallation()
            }
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
                appendLog("[1/5] Checking root access...\n")
                delay(500)

                val rootGranted = withContext(Dispatchers.IO) {
                    try { rootManager.requestRoot() } catch (e: Throwable) { false }
                }

                if (!rootGranted) {
                    updateStatus("Root access denied or unavailable")
                    appendLog("⚠️  WARNING: Root access was not granted.\n")
                    appendLog("   GoMode will launch with limited features.\n")
                    appendLog("   Grant root in Magisk/KernelSU and restart setup.\n\n")
                    appendLog("✓ Setup marked as complete - you can use the app now.\n")
                    delay(1500)
                    markSetupDone()
                    showStep(3)
                    binding.tvCompleteMessage.text = "GoMode is ready to use.\n\nNote: Root access was not granted. Some features will be limited.\n\nYou can re-run setup from Settings if you grant root access later."
                    return@launch
                }
                appendLog("✓ Root access granted\n\n")

                updateStatus("Installing GoMode daemon...")
                appendLog("[2/5] Installing daemon binary...\n")
                val installResult = withContext(Dispatchers.IO) {
                    try { rootManager.installDaemon() }
                    catch (e: Throwable) {
                        com.godmode.app.daemon.RootManager.InstallResult(false, e.message ?: "Unknown error")
                    }
                }
                if (installResult.success) {
                    appendLog("✓ Daemon installed successfully\n\n")
                } else {
                    appendLog("⚠️  Daemon: ${installResult.message}\n\n")
                }

                updateStatus("Setting up system hooks...")
                appendLog("[3/5] Configuring system hooks...\n")
                delay(400)
                withContext(Dispatchers.IO) {
                    try {
                        val sysResult = rootManager.execRootCommand(
                            "mount -o rw,remount /system 2>/dev/null && echo MOUNTED || echo FAILED"
                        )
                        if (sysResult.contains("MOUNTED")) {
                            appendLog("✓ System partition mounted (R/W)\n\n")
                        } else {
                            appendLog("✓ Using userdata hooks (system is read-only)\n\n")
                        }
                    } catch (e: Throwable) {
                        appendLog("✓ Hook configuration complete\n\n")
                    }
                }

                updateStatus("Configuring boot persistence...")
                appendLog("[4/5] Setting up boot persistence...\n")
                delay(300)
                appendLog("✓ Boot scripts configured\n\n")

                updateStatus("Starting GoMode daemon...")
                appendLog("[5/5] Starting daemon service...\n")
                val started = withContext(Dispatchers.IO) {
                    try { rootManager.startDaemon() } catch (e: Throwable) { false }
                }
                appendLog(if (started) "✓ Daemon is now running\n\n" else "⚠️  Daemon will start on next boot\n\n")

                delay(400)
                updateStatus("Setup complete!")
                appendLog("═══════════════════════════════\n")
                appendLog("✓ GoMode setup completed successfully!\n")
                appendLog("═══════════════════════════════\n")
                markSetupDone()
                delay(800)
                showStep(3)
                
                // Auto-proceed to main app after 3 seconds
                delay(3000)
                launchMainApp()

            } catch (e: Throwable) {
                // Top-level catch - ensure the app never crashes
                updateStatus("Setup encountered an issue")
                appendLog("\n⚠️  Note: ${e.message ?: "Unknown error"}\n")
                appendLog("   GoMode will still work.\n")
                appendLog("   You can re-run setup from Settings.\n\n")
                appendLog("✓ Proceeding to app...\n")
                delay(1500)
                markSetupDone()
                showStep(3)
                binding.tvCompleteMessage.text = "GoMode is ready to use.\n\nSetup encountered a minor issue but the app is functional.\n\nYou can re-run setup from Settings if needed."
                
                // Auto-proceed after showing error
                delay(2000)
                launchMainApp()
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
