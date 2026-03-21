package com.godmode.app.ui.tracking

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.godmode.app.R
import com.godmode.app.data.model.DataAccessDetail
import com.godmode.app.databinding.ItemAccessDetailBinding
import java.text.SimpleDateFormat
import java.util.*

class AccessDetailAdapter : ListAdapter<DataAccessDetail, AccessDetailAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAccessDetailBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemAccessDetailBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(detail: DataAccessDetail) {
            // Set data type with icon
            binding.tvDataType.text = formatDataType(detail.dataType)
            binding.ivDataTypeIcon.setImageResource(getDataTypeIcon(detail.dataType))
            
            // Access count
            binding.tvAccessCount.text = "${detail.count}x accessed"
            
            // Last access time
            val dateFormat = SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault())
            binding.tvLastAccess.text = dateFormat.format(Date(detail.lastAccessTime))
            
            // Status indicator
            if (detail.wasBlocked) {
                binding.tvStatus.text = "Spoofed"
                binding.tvStatus.setBackgroundResource(R.color.green_success_dim)
                binding.tvStatus.setTextColor(binding.root.context.getColor(R.color.green_success))
            } else {
                binding.tvStatus.text = "Allowed"
                binding.tvStatus.setBackgroundResource(R.color.bg_card_elevated)
                binding.tvStatus.setTextColor(binding.root.context.getColor(R.color.text_secondary))
            }
        }

        private fun formatDataType(dataType: String): String {
            return when (dataType.uppercase()) {
                "TELEPHONY_IMEI", "TELEPHONY_DEVICE_ID" -> "IMEI / Device ID"
                "TELEPHONY_IMSI" -> "IMSI / Subscriber ID"
                "TELEPHONY_LINE1_NUMBER" -> "Phone Number"
                "TELEPHONY_SIM_SERIAL" -> "SIM Serial"
                "LOCATION_ACCESS" -> "Location Data"
                "WIFI_MAC_SSID" -> "WiFi MAC / SSID"
                "SETTINGS_ANDROID_ID" -> "Android ID"
                "NETWORK_IP_ACCESS" -> "IP Address"
                "CAMERA_ACCESS" -> "Camera Access"
                "AUDIO_ACCESS" -> "Audio / Microphone"
                "CLIPBOARD_ACCESS" -> "Clipboard"
                "SENSOR_ACCESS" -> "Sensors"
                "PACKAGE_LIST_ACCESS" -> "Installed Apps"
                "ACTIVITY_ACCESS" -> "Activity Data"
                else -> dataType.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }
            }
        }

        private fun getDataTypeIcon(dataType: String): Int {
            return when (dataType.uppercase()) {
                "TELEPHONY_IMEI", "TELEPHONY_DEVICE_ID", "TELEPHONY_IMSI", 
                "TELEPHONY_LINE1_NUMBER", "TELEPHONY_SIM_SERIAL" -> android.R.drawable.stat_sys_phone_call
                "LOCATION_ACCESS" -> android.R.drawable.ic_dialog_map
                "WIFI_MAC_SSID", "NETWORK_IP_ACCESS" -> android.R.drawable.stat_sys_data_bluetooth
                "SETTINGS_ANDROID_ID" -> android.R.drawable.ic_menu_info_details
                "CAMERA_ACCESS" -> android.R.drawable.ic_menu_camera
                "AUDIO_ACCESS" -> android.R.drawable.ic_btn_speak_now
                "CLIPBOARD_ACCESS" -> android.R.drawable.ic_menu_edit
                "SENSOR_ACCESS" -> android.R.drawable.ic_menu_compass
                "PACKAGE_LIST_ACCESS" -> android.R.drawable.ic_menu_view
                else -> android.R.drawable.ic_menu_info_details
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<DataAccessDetail>() {
        override fun areItemsTheSame(oldItem: DataAccessDetail, newItem: DataAccessDetail): Boolean {
            return oldItem.dataType == newItem.dataType
        }

        override fun areContentsTheSame(oldItem: DataAccessDetail, newItem: DataAccessDetail): Boolean {
            return oldItem == newItem
        }
    }
}
