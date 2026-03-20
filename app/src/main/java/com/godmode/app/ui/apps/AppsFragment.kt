package com.godmode.app.ui.apps

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Switch
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
import com.godmode.app.data.model.AppConfig
import com.godmode.app.data.repository.GodModeRepository
import com.godmode.app.databinding.FragmentAppsBinding
import com.godmode.app.ui.AppDetailActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AppsFragment : Fragment() {

    private var _binding: FragmentAppsBinding? = null
    private val binding get() = _binding!!
    private lateinit var repository: GodModeRepository
    private lateinit var adapter: AppListAdapter
    private var allApps: List<AppListItem> = emptyList()
    private var showSystemApps = false

    data class AppListItem(
        val packageName: String,
        val appName: String,
        val isSystemApp: Boolean,
        val isConfigActive: Boolean,
        val accessCount: Int
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAppsBinding.inflate(inflater, container, false)
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
        setupSearch()
        setupFilters()
        loadApps()
    }

    private fun setupRecyclerView() {
        adapter = AppListAdapter { item ->
            val intent = Intent(requireContext(), AppDetailActivity::class.java).apply {
                putExtra(AppDetailActivity.EXTRA_PACKAGE_NAME, item.packageName)
                putExtra(AppDetailActivity.EXTRA_APP_NAME, item.appName)
            }
            startActivity(intent)
        }
        binding.rvApps.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@AppsFragment.adapter
        }
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterApps(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupFilters() {
        binding.chipAllApps.setOnCheckedChangeListener { _, checked ->
            if (checked) { showSystemApps = true; filterApps(binding.etSearch.text.toString()) }
        }
        binding.chipUserApps.setOnCheckedChangeListener { _, checked ->
            if (checked) { showSystemApps = false; filterApps(binding.etSearch.text.toString()) }
        }
        binding.chipProtected.setOnCheckedChangeListener { _, checked ->
            filterApps(binding.etSearch.text.toString(), onlyProtected = checked)
        }
    }

    private fun loadApps() {
        viewLifecycleOwner.lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE

            val installedApps = repository.getInstalledApps()

            // Observe configs
            repository.getAllConfigs().collectLatest { configs ->
                val configMap = configs.associateBy { it.packageName }

                allApps = installedApps.map { app ->
                    val config = configMap[app.packageName]
                    AppListItem(
                        packageName = app.packageName,
                        appName = app.appName,
                        isSystemApp = app.isSystemApp,
                        isConfigActive = config?.isActive ?: false,
                        accessCount = 0
                    )
                }

                binding.progressBar.visibility = View.GONE
                binding.tvAppCount.text = "${allApps.size} apps"
                filterApps(binding.etSearch.text.toString())
            }
        }
    }

    private fun filterApps(query: String, onlyProtected: Boolean = false) {
        val filtered = allApps.filter { app ->
            val matchesQuery = query.isEmpty() ||
                app.appName.contains(query, ignoreCase = true) ||
                app.packageName.contains(query, ignoreCase = true)
            val matchesSystem = showSystemApps || !app.isSystemApp
            val matchesProtected = !onlyProtected || app.isConfigActive
            matchesQuery && matchesSystem && matchesProtected
        }
        adapter.submitList(filtered)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ========== Adapter ==========

    inner class AppListAdapter(
        private val onClick: (AppListItem) -> Unit
    ) : ListAdapter<AppListItem, AppViewHolder>(AppDiffCallback()) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
            val view = layoutInflater.inflate(R.layout.item_app, parent, false)
            return AppViewHolder(view)
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
            holder.bind(getItem(position), onClick)
        }
    }

    inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvAppName: TextView = itemView.findViewById(R.id.tv_app_name)
        private val tvPackageName: TextView = itemView.findViewById(R.id.tv_package_name)
        private val tvStatus: TextView = itemView.findViewById(R.id.tv_status)
        private val ivAppIcon: ImageView = itemView.findViewById(R.id.iv_app_icon)
        private val switchActive: Switch = itemView.findViewById(R.id.switch_active)

        fun bind(item: AppListItem, onClick: (AppListItem) -> Unit) {
            tvAppName.text = item.appName
            tvPackageName.text = item.packageName
            tvStatus.text = if (item.isConfigActive) "Protected" else if (item.isSystemApp) "System" else "Unprotected"
            tvStatus.setTextColor(
                when {
                    item.isConfigActive -> requireContext().getColor(R.color.green_success)
                    item.isSystemApp -> requireContext().getColor(R.color.text_hint)
                    else -> requireContext().getColor(R.color.text_secondary)
                }
            )
            switchActive.isChecked = item.isConfigActive
            switchActive.setOnCheckedChangeListener { _, checked ->
                viewLifecycleOwner.lifecycleScope.launch {
                    repository.setConfigActive(item.packageName, checked)
                }
            }

            // Load app icon
            try {
                val pm = requireContext().packageManager
                ivAppIcon.setImageDrawable(pm.getApplicationIcon(item.packageName))
            } catch (e: Exception) {
                ivAppIcon.setImageResource(R.drawable.ic_apps)
            }

            itemView.setOnClickListener { onClick(item) }
        }
    }

    class AppDiffCallback : DiffUtil.ItemCallback<AppListItem>() {
        override fun areItemsTheSame(oldItem: AppListItem, newItem: AppListItem) =
            oldItem.packageName == newItem.packageName
        override fun areContentsTheSame(oldItem: AppListItem, newItem: AppListItem) =
            oldItem == newItem
    }
}
