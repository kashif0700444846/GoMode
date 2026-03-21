package com.godmode.app.ui.tracking

import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.godmode.app.R
import com.godmode.app.data.model.AppAccessSummary
import com.godmode.app.databinding.ItemAppAccessBinding
import java.text.SimpleDateFormat
import java.util.*

class AppTrackingAdapter(
    private val onAppClick: (AppAccessSummary) -> Unit
) : ListAdapter<AppAccessSummary, AppTrackingAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppAccessBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding, onAppClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemAppAccessBinding,
        private val onAppClick: (AppAccessSummary) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(app: AppAccessSummary) {
            binding.tvAppName.text = app.appName
            binding.tvPackageName.text = app.packageName
            binding.tvAccessCount.text = "${app.totalAccesses} accesses"
            
            // Load app icon
            try {
                val packageManager = binding.root.context.packageManager
                val appIcon = packageManager.getApplicationIcon(app.packageName)
                binding.ivAppIcon.setImageDrawable(appIcon)
            } catch (e: Exception) {
                binding.ivAppIcon.setImageResource(R.drawable.ic_shield)
            }
            
            // Format last access time
            val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
            binding.tvLastAccess.text = "Last: ${dateFormat.format(Date(app.lastAccessTime))}"
            
            // Show data types
            val dataTypesText = app.dataTypes.take(3).joinToString(", ")
            binding.tvDataTypes.text = dataTypesText
            
            binding.root.setOnClickListener {
                onAppClick(app)
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<AppAccessSummary>() {
        override fun areItemsTheSame(oldItem: AppAccessSummary, newItem: AppAccessSummary): Boolean {
            return oldItem.packageName == newItem.packageName
        }

        override fun areContentsTheSame(oldItem: AppAccessSummary, newItem: AppAccessSummary): Boolean {
            return oldItem == newItem
        }
    }
}
