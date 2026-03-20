package com.godmode.app.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.godmode.app.GodModeApp
import com.godmode.app.R
import com.godmode.app.daemon.RootManager
import com.godmode.app.data.db.GodModeDatabase
import com.godmode.app.data.repository.GodModeRepository
import com.godmode.app.databinding.FragmentDashboardBinding
import com.godmode.app.ui.AppDetailActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DashboardViewModel by activityViewModels {
        val app = requireActivity().application as GodModeApp
        val repository = GodModeRepository(
            requireContext(),
            app.database.appConfigDao(),
            app.database.accessLogDao(),
            app.rootManager
        )
        DashboardViewModelFactory(repository, app.rootManager)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupObservers()
        setupClickListeners()
    }

    private fun setupObservers() {
        viewModel.rootStatus.observe(viewLifecycleOwner) { status ->
            updateRootStatusUI(status)
        }

        viewModel.deviceInfo.observe(viewLifecycleOwner) { info ->
            updateDeviceInfoUI(info)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.totalLogCount.collectLatest { count ->
                binding.tvTotalAccessCount.text = count.toString()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.spoofedCount.collectLatest { count ->
                binding.tvSpoofedCount.text = count.toString()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.activeConfigCount.collectLatest { configs ->
                binding.tvProtectedApps.text = configs.size.toString()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.recentLogs.collectLatest { logs ->
                updateRecentLogsUI(logs)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.topAccessingApps.collectLatest { apps ->
                updateTopAppsUI(apps)
            }
        }
    }

    private fun setupClickListeners() {
        binding.btnRefresh.setOnClickListener {
            viewModel.refreshStatus()
        }

        binding.btnToggleDaemon.setOnClickListener {
            val status = viewModel.rootStatus.value
            if (status?.isDaemonRunning == true) {
                viewModel.stopDaemon()
            } else {
                viewModel.startDaemon()
            }
        }

        binding.btnClearLogs.setOnClickListener {
            viewModel.clearAllLogs()
        }

        binding.cardRecentLogs.setOnClickListener {
            // Navigate to logs fragment
        }
    }

    private fun updateRootStatusUI(status: RootManager.RootStatus) {
        // Root status
        binding.tvRootStatus.text = if (status.isRooted) "Rooted" else "Not Rooted"
        binding.tvRootStatus.setTextColor(
            if (status.isRooted) resources.getColor(R.color.green_success, null)
            else resources.getColor(R.color.red_danger, null)
        )

        // Daemon status
        binding.tvDaemonStatus.text = if (status.isDaemonRunning) "Running" else "Stopped"
        binding.tvDaemonStatus.setTextColor(
            if (status.isDaemonRunning) resources.getColor(R.color.green_success, null)
            else resources.getColor(R.color.orange_warning, null)
        )
        binding.indicatorDaemon.setBackgroundResource(
            if (status.isDaemonRunning) R.drawable.indicator_green else R.drawable.indicator_red
        )

        binding.btnToggleDaemon.text = if (status.isDaemonRunning) "Stop Daemon" else "Start Daemon"

        // Tool detection
        binding.tvMagiskStatus.text = if (status.hasMagisk) "Detected" else "Not Found"
        binding.tvKernelsuStatus.text = if (status.hasKernelSU) "Detected" else "Not Found"
        binding.tvLsposedStatus.text = if (status.hasLSPosed) "Detected" else "Not Found"

        // Daemon version
        if (status.daemonVersion.isNotEmpty()) {
            binding.tvDaemonVersion.text = "v${status.daemonVersion}"
            binding.tvDaemonVersion.visibility = View.VISIBLE
        } else {
            binding.tvDaemonVersion.visibility = View.GONE
        }
    }

    private fun updateDeviceInfoUI(info: GodModeRepository.DeviceInfo) {
        binding.tvRealImei.text = if (info.realImei.isNotEmpty()) info.realImei else "Unknown"
        binding.tvRealAndroidId.text = if (info.realAndroidId.isNotEmpty()) info.realAndroidId else "Unknown"
        binding.tvRealSerial.text = if (info.realSerial.isNotEmpty()) info.realSerial else "Unknown"
        binding.tvRealIp.text = if (info.realIp.isNotEmpty()) info.realIp else "Unknown"
        binding.tvRealMac.text = if (info.realMac.isNotEmpty()) info.realMac else "Unknown"
    }

    private fun updateRecentLogsUI(logs: List<com.godmode.app.data.model.AccessLog>) {
        if (logs.isEmpty()) {
            binding.tvNoLogs.visibility = View.VISIBLE
            binding.rvRecentLogs.visibility = View.GONE
            return
        }
        binding.tvNoLogs.visibility = View.GONE
        binding.rvRecentLogs.visibility = View.VISIBLE

        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val logText = logs.take(5).joinToString("\n") { log ->
            val time = sdf.format(Date(log.timestamp))
            val spoofIndicator = if (log.wasSpoofed) " [SPOOFED]" else ""
            "[$time] ${log.packageName.substringAfterLast('.')}: ${log.propertyType}$spoofIndicator"
        }
        binding.tvRecentLogsSummary.text = logText
    }

    private fun updateTopAppsUI(apps: List<com.godmode.app.data.db.PackageAccessCount>) {
        if (apps.isEmpty()) {
            binding.tvNoTopApps.visibility = View.VISIBLE
            return
        }
        binding.tvNoTopApps.visibility = View.GONE

        val text = apps.take(5).joinToString("\n") { app ->
            "${app.packageName.substringAfterLast('.')}: ${app.count} accesses"
        }
        binding.tvTopApps.text = text
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
