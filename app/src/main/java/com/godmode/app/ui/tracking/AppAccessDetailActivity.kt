package com.godmode.app.ui.tracking

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.godmode.app.GodModeApp
import com.godmode.app.R
import com.godmode.app.data.model.DataAccessDetail
import com.godmode.app.databinding.ActivityAppAccessDetailBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppAccessDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppAccessDetailBinding
    private lateinit var adapter: AccessDetailAdapter
    private lateinit var packageName: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppAccessDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        packageName = intent.getStringExtra("package_name") ?: return finish()
        val appName = intent.getStringExtra("app_name") ?: packageName

        setupToolbar(appName)
        setupRecyclerView()
        loadAccessDetails()
    }

    private fun setupToolbar(appName: String) {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = appName
        
        binding.tvPackageName.text = packageName
        
        // Load app icon
        try {
            val appIcon = packageManager.getApplicationIcon(packageName)
            binding.ivAppIcon.setImageDrawable(appIcon)
        } catch (e: Exception) {
            binding.ivAppIcon.setImageResource(R.drawable.ic_shield)
        }
    }

    private fun setupRecyclerView() {
        adapter = AccessDetailAdapter()
        binding.rvAccessDetails.layoutManager = LinearLayoutManager(this)
        binding.rvAccessDetails.adapter = adapter
    }

    private fun loadAccessDetails() {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvEmpty.visibility = View.GONE

        lifecycleScope.launch {
            val details = withContext(Dispatchers.IO) {
                getAccessDetails(packageName)
            }

            adapter.submitList(details)
            binding.progressBar.visibility = View.GONE

            if (details.isEmpty()) {
                binding.tvEmpty.visibility = View.VISIBLE
            }
            
            // Update summary stats
            updateSummaryStats(details)
        }
    }

    private fun updateSummaryStats(details: List<DataAccessDetail>) {
        val totalAccesses = details.sumOf { it.count }
        val blockedAccesses = details.filter { it.wasBlocked }.sumOf { it.count }
        
        binding.tvTotalAccesses.text = "$totalAccesses"
        binding.tvBlockedAccesses.text = "$blockedAccesses"
        binding.tvUniqueDataTypes.text = "${details.size}"
    }

    private suspend fun getAccessDetails(packageName: String): List<DataAccessDetail> {
        val database = (application as GodModeApp).database
        val accessLogDao = database.accessLogDao()

        val logs = accessLogDao.getLogsForPackage(packageName)
        val grouped = logs.groupBy { it.property }

        return grouped.map { (dataType, logs) ->
            DataAccessDetail(
                dataType = dataType,
                count = logs.size,
                lastAccessTime = logs.maxOf { it.timestamp },
                wasBlocked = logs.any { it.spoofedValue.isNotEmpty() }
            )
        }.sortedByDescending { it.lastAccessTime }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
