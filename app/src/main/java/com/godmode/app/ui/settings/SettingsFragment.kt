package com.godmode.app.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.godmode.app.GodModeApp
import com.godmode.app.data.db.GodModeDatabase
import com.godmode.app.data.repository.GodModeRepository
import com.godmode.app.databinding.FragmentSettingsBinding
import com.godmode.app.ui.SpoofSettingsActivity
import com.godmode.app.ui.setup.SetupWizardActivity
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var repository: GodModeRepository

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val app = requireActivity().application as GodModeApp
        repository = GodModeRepository(
            requireContext(),
            app.database.appConfigDao(),
            app.database.accessLogDao(),
            app.rootManager
        )

        setupClickListeners()
        loadDaemonStatus()
    }

    private fun setupClickListeners() {
        binding.btnDeviceInfo.setOnClickListener {
            startActivity(Intent(requireContext(), SpoofSettingsActivity::class.java))
        }

        binding.btnRestartDaemon.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val app = requireActivity().application as GodModeApp
                app.rootManager.stopDaemon()
                val started = app.rootManager.startDaemon()
                Toast.makeText(
                    requireContext(),
                    if (started) "Daemon restarted" else "Failed to restart daemon",
                    Toast.LENGTH_SHORT
                ).show()
                loadDaemonStatus()
            }
        }

        binding.btnReinstallDaemon.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val app = requireActivity().application as GodModeApp
                binding.progressBar.visibility = View.VISIBLE
                val result = app.rootManager.installDaemon()
                binding.progressBar.visibility = View.GONE
                Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
            }
        }

        binding.btnClearAllLogs.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                repository.clearAllLogs()
                Toast.makeText(requireContext(), "All logs cleared", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnClearOldLogs.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                repository.clearOldLogs(daysToKeep = 7)
                Toast.makeText(requireContext(), "Logs older than 7 days cleared", Toast.LENGTH_SHORT).show()
            }
        }

        binding.switchAutoStart.setOnCheckedChangeListener { _, checked ->
            requireContext().getSharedPreferences("gomode_prefs", 0)
                .edit().putBoolean("auto_start", checked).apply()
        }

        binding.switchNotifications.setOnCheckedChangeListener { _, checked ->
            requireContext().getSharedPreferences("gomode_prefs", 0)
                .edit().putBoolean("notifications", checked).apply()
        }

        // ---- Power Controls ----
        binding.btnReboot.setOnClickListener {
            confirmPower("Reboot", "Reboot the device now?") {
                viewLifecycleOwner.lifecycleScope.launch { (requireActivity().application as GodModeApp).rootManager.reboot() }
            }
        }
        binding.btnPowerOff.setOnClickListener {
            confirmPower("Power Off", "Power off the device now?") {
                viewLifecycleOwner.lifecycleScope.launch { (requireActivity().application as GodModeApp).rootManager.powerOff() }
            }
        }
        binding.btnRebootRecovery.setOnClickListener {
            confirmPower("Reboot to Recovery", "Reboot to Recovery mode?") {
                viewLifecycleOwner.lifecycleScope.launch { (requireActivity().application as GodModeApp).rootManager.rebootRecovery() }
            }
        }
        binding.btnRebootBootloader.setOnClickListener {
            confirmPower("Reboot to Bootloader", "Reboot to Bootloader/Fastboot?") {
                viewLifecycleOwner.lifecycleScope.launch { (requireActivity().application as GodModeApp).rootManager.rebootBootloader() }
            }
        }
        binding.btnRebootEdl.setOnClickListener {
            confirmPower("Reboot to EDL", "Reboot to EDL / Download mode?") {
                viewLifecycleOwner.lifecycleScope.launch { (requireActivity().application as GodModeApp).rootManager.rebootEdl() }
            }
        }
        binding.btnRebootSafe.setOnClickListener {
            confirmPower("Safe Mode", "Reboot in Safe Mode?") {
                viewLifecycleOwner.lifecycleScope.launch { (requireActivity().application as GodModeApp).rootManager.rebootSafeMode() }
            }
        }

        // ---- System Tools ----
        binding.btnRunShellCmd.setOnClickListener {
            showShellCommandDialog()
        }

        binding.btnOpenTerminal.setOnClickListener {
            findNavController().navigate(com.godmode.app.R.id.terminalFragment)
        }

        binding.btnRemountSystem.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val rootManager = (requireActivity().application as GodModeApp).rootManager
                val result = try {
                    rootManager.execRootCommand("mount -o rw,remount /system 2>&1")
                } catch (e: Throwable) { "Error: ${e.message}" }
                Toast.makeText(requireContext(), if (result.isEmpty() || !result.lowercase().contains("error")) "Remounted /system as R/W" else result, Toast.LENGTH_LONG).show()
            }
        }

        binding.btnResetSetup.setOnClickListener {
            requireContext().getSharedPreferences("gomode_prefs", 0)
                .edit().putBoolean("setup_complete", false).apply()
            startActivity(Intent(requireContext(), SetupWizardActivity::class.java))
            requireActivity().finish()
        }
        
        // ---- Developer Tools ----
        binding.btnDebugDiagnostics.setOnClickListener {
            navigateToDebug()
        }
        
        binding.btnKernelVerification.setOnClickListener {
            navigateToKernelVerification()
        }
    }

    private fun confirmPower(title: String, message: String, action: () -> Unit) {
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Yes") { _, _ -> action() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showShellCommandDialog() {
        val input = android.widget.EditText(requireContext()).apply {
            hint = "e.g. ls /system/lib64"
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(32, 16, 32, 16)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Run Shell Command")
            .setView(input)
            .setPositiveButton("Run") { _, _ ->
                val cmd = input.text?.toString()?.trim() ?: return@setPositiveButton
                if (cmd.isEmpty()) return@setPositiveButton
                viewLifecycleOwner.lifecycleScope.launch {
                    val rootManager = (requireActivity().application as GodModeApp).rootManager
                    val result = try {
                        rootManager.execRootCommand(cmd)
                    } catch (e: Throwable) { "Error: ${e.message}" }
                    AlertDialog.Builder(requireContext())
                        .setTitle("Result")
                        .setMessage(result.ifEmpty { "(no output)" })
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadDaemonStatus() {
        viewLifecycleOwner.lifecycleScope.launch {
            val app = requireActivity().application as GodModeApp
            val status = app.rootManager.getRootStatus()
            binding.tvDaemonVersion.text = if (status.daemonVersion.isNotEmpty())
                "v${status.daemonVersion}" else "Not running"
            binding.tvDaemonRunning.text = if (status.isDaemonRunning) "Running" else "Stopped"
        }
    }
    
    private fun navigateToDebug() {
        try {
            findNavController().navigate(com.godmode.app.R.id.debugFragment)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Debug screen error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun navigateToKernelVerification() {
        try {
            findNavController().navigate(com.godmode.app.R.id.kernelVerificationFragment)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Kernel verification error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
