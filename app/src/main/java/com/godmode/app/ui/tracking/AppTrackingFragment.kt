package com.godmode.app.ui.tracking

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.godmode.app.GodModeApp
import com.godmode.app.data.model.AppAccessSummary
import com.godmode.app.databinding.FragmentAppTrackingBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppTrackingFragment : Fragment() {

    private var _binding: FragmentAppTrackingBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var adapter: AppTrackingAdapter
    private var allApps = listOf<AppAccessSummary>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAppTrackingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupRecyclerView()
        setupSearchView()
        loadTrackedApps()
    }

    private fun setupRecyclerView() {
        adapter = AppTrackingAdapter(
            onAppClick = { app ->
                val intent = Intent(requireContext(), AppAccessDetailActivity::class.java)
                intent.putExtra("package_name", app.packageName)
                intent.putExtra("app_name", app.appName)
                startActivity(intent)
            }
        )
        
        binding.rvTrackedApps.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTrackedApps.adapter = adapter
    }

    private fun setupSearchView() {
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                filterApps(query)
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                filterApps(newText)
                return true
            }
        })
    }

    private fun filterApps(query: String?) {
        val filtered = if (query.isNullOrBlank()) {
            allApps
        } else {
            allApps.filter {
                it.appName.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
            }
        }
        adapter.submitList(filtered)
    }

    private fun loadTrackedApps() {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvEmpty.visibility = View.GONE
        
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                getTrackedApps()
            }
            
            allApps = apps
            adapter.submitList(apps)
            
            binding.progressBar.visibility = View.GONE
            if (apps.isEmpty()) {
                binding.tvEmpty.visibility = View.VISIBLE
            }
        }
    }

    private suspend fun getTrackedApps(): List<AppAccessSummary> {
        val database = (requireActivity().application as GodModeApp).database
        val accessLogDao = database.accessLogDao()
        
        // Get all unique packages from access logs
        val logs = accessLogDao.getAllLogs()
        val grouped = logs.groupBy { it.packageName }
        
        val packageManager = requireContext().packageManager
        
        return grouped.map { (packageName, logs) ->
            val appName = try {
                val appInfo = packageManager.getApplicationInfo(packageName, 0)
                packageManager.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                packageName
            }
            
            AppAccessSummary(
                packageName = packageName,
                appName = appName,
                totalAccesses = logs.size,
                lastAccessTime = logs.maxOf { it.timestamp },
                dataTypes = logs.map { it.property }.distinct()
            )
        }.sortedByDescending { it.lastAccessTime }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
