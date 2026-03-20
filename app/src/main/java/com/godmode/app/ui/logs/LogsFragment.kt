package com.godmode.app.ui.logs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
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
import com.godmode.app.databinding.FragmentLogsBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class LogsFragment : Fragment() {

    private var _binding: FragmentLogsBinding? = null
    private val binding get() = _binding!!
    private lateinit var repository: GodModeRepository
    private lateinit var adapter: GlobalLogAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLogsBinding.inflate(inflater, container, false)
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

        setupRecyclerView()
        setupFilters()
        loadLogs()
    }

    private fun setupRecyclerView() {
        adapter = GlobalLogAdapter()
        binding.rvLogs.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@LogsFragment.adapter
        }
    }

    private fun setupFilters() {
        binding.chipAll.setOnClickListener { loadLogs() }
        binding.chipSpoofed.setOnClickListener { loadLogs(onlySpoofed = true) }
        binding.chipLocation.setOnClickListener { loadLogs(type = AccessLog.TYPE_LOCATION_GPS) }
        binding.chipImei.setOnClickListener { loadLogs(type = AccessLog.TYPE_IMEI) }
        binding.chipNetwork.setOnClickListener { loadLogs(type = AccessLog.TYPE_NETWORK_CONN) }
        binding.chipCamera.setOnClickListener { loadLogs(type = AccessLog.TYPE_CAMERA) }
        binding.chipAndroidId.setOnClickListener { loadLogs(type = AccessLog.TYPE_ANDROID_ID) }

        binding.btnClearAll.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                repository.clearAllLogs()
            }
        }
    }

    private fun loadLogs(type: String = "", onlySpoofed: Boolean = false) {
        viewLifecycleOwner.lifecycleScope.launch {
            repository.getFilteredLogs(
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    inner class GlobalLogAdapter : ListAdapter<AccessLog, GlobalLogViewHolder>(LogDiffCallback()) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GlobalLogViewHolder {
            val view = layoutInflater.inflate(R.layout.item_access_log_full, parent, false)
            return GlobalLogViewHolder(view)
        }
        override fun onBindViewHolder(holder: GlobalLogViewHolder, position: Int) {
            holder.bind(getItem(position))
        }
    }

    inner class GlobalLogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTime: TextView = itemView.findViewById(R.id.tv_time)
        private val tvApp: TextView = itemView.findViewById(R.id.tv_app)
        private val tvProperty: TextView = itemView.findViewById(R.id.tv_property)
        private val tvOriginal: TextView = itemView.findViewById(R.id.tv_original)
        private val tvSpoofed: TextView = itemView.findViewById(R.id.tv_spoofed)
        private val tvSpoofBadge: TextView = itemView.findViewById(R.id.tv_spoof_badge)
        private val sdf = SimpleDateFormat("MM/dd HH:mm:ss", Locale.getDefault())

        fun bind(log: AccessLog) {
            tvTime.text = sdf.format(Date(log.timestamp))
            tvApp.text = log.packageName.substringAfterLast('.')
            tvProperty.text = log.propertyType
            tvOriginal.text = if (log.originalValue.isNotEmpty()) log.originalValue else "—"
            tvSpoofed.text = if (log.spoofedValue.isNotEmpty()) log.spoofedValue else "—"
            tvSpoofBadge.visibility = if (log.wasSpoofed) View.VISIBLE else View.GONE

            val bgColor = when {
                log.wasSpoofed -> requireContext().getColor(R.color.log_spoofed_bg)
                log.propertyType in listOf(
                    AccessLog.TYPE_LOCATION_GPS, AccessLog.TYPE_IMEI,
                    AccessLog.TYPE_ANDROID_ID, AccessLog.TYPE_CAMERA
                ) -> requireContext().getColor(R.color.log_sensitive_bg)
                else -> requireContext().getColor(R.color.log_normal_bg)
            }
            itemView.setBackgroundColor(bgColor)
        }
    }

    class LogDiffCallback : DiffUtil.ItemCallback<AccessLog>() {
        override fun areItemsTheSame(oldItem: AccessLog, newItem: AccessLog) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: AccessLog, newItem: AccessLog) = oldItem == newItem
    }
}
