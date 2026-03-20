package com.godmode.app.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.godmode.app.GodModeApp
import com.godmode.app.data.db.GodModeDatabase
import com.godmode.app.data.repository.GodModeRepository
import com.godmode.app.databinding.FragmentSettingsBinding
import com.godmode.app.ui.SpoofSettingsActivity
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
            // Save auto-start preference
            requireContext().getSharedPreferences("godmode_prefs", 0)
                .edit().putBoolean("auto_start", checked).apply()
        }

        binding.switchNotifications.setOnCheckedChangeListener { _, checked ->
            requireContext().getSharedPreferences("godmode_prefs", 0)
                .edit().putBoolean("notifications", checked).apply()
        }
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
