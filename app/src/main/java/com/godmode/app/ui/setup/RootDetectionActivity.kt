package com.godmode.app.ui.setup

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.godmode.app.GodModeApp
import com.godmode.app.databinding.ActivityRootDetectionBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RootDetectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRootDetectionBinding
    private var rootChecked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRootDetectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupButtons()
        startRootDetection()
    }

    private fun setupButtons() {
        binding.btnRequestRoot.setOnClickListener {
            lifecycleScope.launch {
                requestRootAccess()
            }
        }

        binding.btnOpenRootManager.setOnClickListener {
            openRootManager()
        }

        binding.btnContinueWithoutRoot.setOnClickListener {
            proceedToSetup(hasRoot = false)
        }

        binding.btnContinueWithRoot.setOnClickListener {
            proceedToSetup(hasRoot = true)
        }
    }

    private fun startRootDetection() {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            binding.tvStatus.text = "Detecting root access..."
            binding.cardRootStatus.visibility = View.GONE
            binding.layoutButtons.visibility = View.GONE

            delay(800) // Brief delay for UI feedback

            val rootManager = (application as GodModeApp).rootManager
            val rootStatus = withContext(Dispatchers.IO) {
                rootManager.getRootStatus()
            }

            rootChecked = true
            binding.progressBar.visibility = View.GONE
            binding.cardRootStatus.visibility = View.VISIBLE

            if (rootStatus.isRooted) {
                showRootDetected(rootStatus)
            } else {
                showRootNotDetected()
            }
        }
    }

    private fun showRootDetected(rootStatus: com.godmode.app.daemon.RootManager.RootStatus) {
        binding.tvStatus.text = "✓ Root Access Detected"
        binding.tvStatusDescription.text = buildString {
            append("Root access is available. GoMode can now operate at kernel level.\n\n")
            append("Detected:\n")
            if (rootStatus.hasMagisk) append("• Magisk installed\n")
            if (rootStatus.hasKernelSU) append("• KernelSU installed\n")
            if (rootStatus.isDaemonRunning) append("• GoMode daemon running\n")
        }

        binding.imgStatusIcon.setImageResource(android.R.drawable.checkbox_on_background)
        binding.cardRootStatus.setCardBackgroundColor(getColor(com.godmode.app.R.color.green_success_dim))
        
        binding.btnContinueWithRoot.visibility = View.VISIBLE
        binding.btnRequestRoot.visibility = View.GONE
        binding.btnOpenRootManager.visibility = View.GONE
        binding.btnContinueWithoutRoot.visibility = View.VISIBLE
        binding.layoutButtons.visibility = View.VISIBLE

        // Auto-proceed after 2 seconds
        lifecycleScope.launch {
            delay(2000)
            proceedToSetup(hasRoot = true)
        }
    }

    private fun showRootNotDetected() {
        binding.tvStatus.text = "⚠ Root Access Not Detected"
        binding.tvStatusDescription.text = buildString {
            append("GoMode requires root access to work at kernel level.\n\n")
            append("To grant root access:\n")
            append("1. Install Magisk or KernelSU\n")
            append("2. Open your root manager app\n")
            append("3. Grant root access to GoMode\n\n")
            append("Or continue without root for limited functionality.")
        }

        binding.imgStatusIcon.setImageResource(android.R.drawable.ic_dialog_alert)
        binding.cardRootStatus.setCardBackgroundColor(getColor(com.godmode.app.R.color.orange_warning))
        
        binding.btnRequestRoot.visibility = View.VISIBLE
        binding.btnOpenRootManager.visibility = View.VISIBLE
        binding.btnContinueWithoutRoot.visibility = View.VISIBLE
        binding.btnContinueWithRoot.visibility = View.GONE
        binding.layoutButtons.visibility = View.VISIBLE
    }

    private suspend fun requestRootAccess() {
        binding.tvStatus.text = "Requesting root access..."
        binding.progressBar.visibility = View.VISIBLE
        binding.layoutButtons.visibility = View.GONE

        val rootManager = (application as GodModeApp).rootManager
        val granted = withContext(Dispatchers.IO) {
            rootManager.requestRoot()
        }

        binding.progressBar.visibility = View.GONE

        if (granted) {
            binding.tvStatus.text = "✓ Root Access Granted!"
            binding.tvStatusDescription.text = "GoMode can now operate at kernel level."
            binding.imgStatusIcon.setImageResource(android.R.drawable.checkbox_on_background)
            binding.cardRootStatus.setCardBackgroundColor(getColor(com.godmode.app.R.color.green_success_dim))
            
            delay(1000)
            proceedToSetup(hasRoot = true)
        } else {
            showRootAccessDenied()
        }
    }

    private fun showRootAccessDenied() {
        binding.tvStatus.text = "✗ Root Access Denied"
        binding.tvStatusDescription.text = buildString {
            append("Root access was denied.\n\n")
            append("Please:\n")
            append("1. Open your root manager (Magisk/KernelSU)\n")
            append("2. Find GoMode in the app list\n")
            append("3. Grant root access\n")
            append("4. Come back and tap 'Request Root Access' again")
        }

        binding.imgStatusIcon.setImageResource(android.R.drawable.ic_delete)
        binding.cardRootStatus.setCardBackgroundColor(getColor(com.godmode.app.R.color.red_danger))
        
        binding.btnRequestRoot.visibility = View.VISIBLE
        binding.btnOpenRootManager.visibility = View.VISIBLE
        binding.btnContinueWithoutRoot.visibility = View.VISIBLE
        binding.layoutButtons.visibility = View.VISIBLE
    }

    private fun openRootManager() {
        // Try to open Magisk
        var intent = packageManager.getLaunchIntentForPackage("com.topjohnwu.magisk")
        if (intent == null) {
            // Try KernelSU
            intent = packageManager.getLaunchIntentForPackage("me.weishu.kernelsu")
        }
        if (intent == null) {
            // Try APatch
            intent = packageManager.getLaunchIntentForPackage("me.tool.passkey")
        }
        
        if (intent != null) {
            startActivity(intent)
        } else {
            // Show guide to install root manager
            binding.tvStatusDescription.text = buildString {
                append("No root manager found.\n\n")
                append("Please install:\n")
                append("• Magisk: https://github.com/topjohnwu/Magisk\n")
                append("• KernelSU: https://github.com/tiann/KernelSU\n\n")
                append("After installation, grant root to GoMode.")
            }
        }
    }

    private fun proceedToSetup(hasRoot: Boolean) {
        val intent = Intent(this, SetupWizardActivity::class.java)
        intent.putExtra("has_root", hasRoot)
        startActivity(intent)
        finish()
    }
}
