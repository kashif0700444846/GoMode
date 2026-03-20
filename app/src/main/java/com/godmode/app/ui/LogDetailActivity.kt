package com.godmode.app.ui

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.godmode.app.GodModeApp
import com.godmode.app.R
import com.godmode.app.data.db.GodModeDatabase
import com.godmode.app.data.model.AccessLog
import com.godmode.app.data.repository.GodModeRepository
import com.godmode.app.databinding.ActivityLogDetailBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class LogDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_APP_NAME = "app_name"
    }

    private lateinit var binding: ActivityLogDetailBinding
    private lateinit var repository: GodModeRepository
    private lateinit var adapter: LogAdapter
    private var packageName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
        val appName = intent.getStringExtra(EXTRA_APP_NAME) ?: packageName

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Access Logs"
            subtitle = appName
        }

        val app = application as GodModeApp
        repository = GodModeRepository(
            this,
            app.database.appConfigDao(),
            app.database.accessLogDao(),
            app.rootManager
        )

        setupRecyclerView()
        setupFilters()
        loadLogs()
    }

    private fun setupRecyclerView() {
        adapter = LogAdapter()
        binding.rvLogs.apply {
            layoutManager = LinearLayoutManager(this@LogDetailActivity)
            adapter = this@LogDetailActivity.adapter
        }
    }

    private fun setupFilters() {
        binding.chipAll.setOnClickListener { loadLogs() }
        binding.chipSpoofed.setOnClickListener { loadLogs(onlySpoofed = true) }
        binding.chipLocation.setOnClickListener { loadLogs(type = AccessLog.TYPE_LOCATION_GPS) }
        binding.chipImei.setOnClickListener { loadLogs(type = AccessLog.TYPE_IMEI) }
        binding.chipNetwork.setOnClickListener { loadLogs(type = AccessLog.TYPE_NETWORK_CONN) }
        binding.chipCamera.setOnClickListener { loadLogs(type = AccessLog.TYPE_CAMERA) }

        binding.btnExport.setOnClickListener { exportLogs() }
        binding.btnClear.setOnClickListener {
            lifecycleScope.launch {
                repository.clearLogsForPackage(packageName)
            }
        }
    }

    private fun loadLogs(type: String = "", onlySpoofed: Boolean = false) {
        lifecycleScope.launch {
            repository.getFilteredLogs(
                packageName = packageName,
                propertyType = type,
                onlySpoofed = onlySpoofed,
                limit = 500
            ).collectLatest { logs ->
                adapter.submitList(logs)
                binding.tvLogCount.text = "${logs.size} entries"
                binding.emptyView.visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun exportLogs() {
        // Export logs as CSV/JSON
        lifecycleScope.launch {
            val logs = adapter.currentList
            val csv = buildString {
                appendLine("Timestamp,Package,Property,Original,Spoofed,WasSpoofed")
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                logs.forEach { log ->
                    appendLine("${sdf.format(Date(log.timestamp))},${log.packageName},${log.propertyType},\"${log.originalValue}\",\"${log.spoofedValue}\",${log.wasSpoofed}")
                }
            }
            // Share the CSV
            val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(android.content.Intent.EXTRA_TEXT, csv)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "GodMode Access Logs - $packageName")
            }
            startActivity(android.content.Intent.createChooser(shareIntent, "Export Logs"))
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ========== RecyclerView Adapter ==========

    inner class LogAdapter : ListAdapter<AccessLog, LogViewHolder>(LogDiffCallback()) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
            val view = layoutInflater.inflate(R.layout.item_access_log, parent, false)
            return LogViewHolder(view)
        }

        override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
            holder.bind(getItem(position))
        }
    }

    inner class LogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTime: TextView = itemView.findViewById(R.id.tv_time)
        private val tvProperty: TextView = itemView.findViewById(R.id.tv_property)
        private val tvPackage: TextView = itemView.findViewById(R.id.tv_package)
        private val tvOriginal: TextView = itemView.findViewById(R.id.tv_original)
        private val tvSpoofed: TextView = itemView.findViewById(R.id.tv_spoofed)
        private val tvSpoofBadge: TextView = itemView.findViewById(R.id.tv_spoof_badge)

        private val sdf = SimpleDateFormat("MM/dd HH:mm:ss.SSS", Locale.getDefault())

        fun bind(log: AccessLog) {
            tvTime.text = sdf.format(Date(log.timestamp))
            tvProperty.text = log.propertyType
            tvPackage.text = log.packageName.substringAfterLast('.')
            tvOriginal.text = if (log.originalValue.isNotEmpty()) "Real: ${log.originalValue}" else "Real: [hidden]"
            tvSpoofed.text = if (log.spoofedValue.isNotEmpty()) "Sent: ${log.spoofedValue}" else "Sent: [original]"
            tvSpoofBadge.visibility = if (log.wasSpoofed) View.VISIBLE else View.GONE

            // Color coding
            val bgColor = when {
                log.wasSpoofed -> itemView.context.getColor(R.color.log_spoofed_bg)
                log.propertyType in listOf(AccessLog.TYPE_LOCATION_GPS, AccessLog.TYPE_IMEI) ->
                    itemView.context.getColor(R.color.log_sensitive_bg)
                else -> itemView.context.getColor(R.color.log_normal_bg)
            }
            itemView.setBackgroundColor(bgColor)
        }
    }

    class LogDiffCallback : DiffUtil.ItemCallback<AccessLog>() {
        override fun areItemsTheSame(oldItem: AccessLog, newItem: AccessLog) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: AccessLog, newItem: AccessLog) = oldItem == newItem
    }
}
