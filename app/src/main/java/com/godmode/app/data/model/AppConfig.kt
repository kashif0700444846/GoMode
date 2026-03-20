package com.godmode.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-app spoofing configuration stored in Room database.
 * Controls what data is spoofed for each installed app.
 */
@Entity(tableName = "app_configs")
data class AppConfig(
    @PrimaryKey
    val packageName: String,

    // Whether spoofing is active for this app
    var isActive: Boolean = false,

    // App display info (cached)
    var appName: String = "",
    var appIcon: ByteArray? = null,

    // IMEI / Device ID spoofing
    // 0=disabled, 1=custom, 2=random, 3=empty
    var imeiMode: Int = 0,
    var customImei: String = "",

    // IMSI spoofing
    var imsiMode: Int = 0,
    var customImsi: String = "",

    // Android ID spoofing
    var androidIdMode: Int = 0,
    var customAndroidId: String = "",

    // Device Serial Number
    var serialMode: Int = 0,
    var customSerial: String = "",

    // Location spoofing
    var locationMode: Int = 0,
    var customLat: Double = 0.0,
    var customLon: Double = 0.0,
    var customAlt: Double = 0.0,
    var customLocationAccuracy: Float = 5.0f,
    var locationName: String = "",  // Human-readable location name

    // IP Address spoofing
    var ipMode: Int = 0,
    var customIp: String = "",

    // MAC Address spoofing
    var macMode: Int = 0,
    var customMac: String = "",

    // Phone Number spoofing
    var phoneMode: Int = 0,
    var customPhone: String = "",

    // Build properties spoofing
    var buildMode: Int = 0,
    var customModel: String = "",
    var customBrand: String = "",
    var customFingerprint: String = "",

    // Network operator spoofing
    var operatorMode: Int = 0,
    var customMccMnc: String = "",
    var customOperatorName: String = "",

    // SIM Serial spoofing
    var simSerialMode: Int = 0,
    var customSimSerial: String = "",

    // Advertising ID spoofing
    var adIdMode: Int = 0,
    var customAdId: String = "",

    // Camera access control
    var blockCamera: Boolean = false,

    // Microphone access control
    var blockMicrophone: Boolean = false,

    // Contacts access control
    var blockContacts: Boolean = false,

    // Calendar access control
    var blockCalendar: Boolean = false,

    // Clipboard access control
    var blockClipboard: Boolean = false,

    // Sensor access control
    var blockSensors: Boolean = false,

    // Network access monitoring
    var monitorNetwork: Boolean = true,

    // Timestamps
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val MODE_DISABLED = 0
        const val MODE_CUSTOM = 1
        const val MODE_RANDOM = 2
        const val MODE_EMPTY = 3

        fun modeToString(mode: Int): String = when (mode) {
            MODE_DISABLED -> "Disabled"
            MODE_CUSTOM -> "Custom"
            MODE_RANDOM -> "Random"
            MODE_EMPTY -> "Empty"
            else -> "Unknown"
        }
    }

    /**
     * Converts this config to the pipe-delimited format used by the native daemon.
     */
    fun toDaemonConfigString(): String {
        return buildString {
            append("package=$packageName")
            append("|active=${if (isActive) 1 else 0}")
            append("|imei_mode=$imeiMode")
            if (customImei.isNotEmpty()) append("|custom_imei=$customImei")
            append("|imsi_mode=$imsiMode")
            if (customImsi.isNotEmpty()) append("|custom_imsi=$customImsi")
            append("|android_id_mode=$androidIdMode")
            if (customAndroidId.isNotEmpty()) append("|custom_android_id=$customAndroidId")
            append("|serial_mode=$serialMode")
            if (customSerial.isNotEmpty()) append("|custom_serial=$customSerial")
            append("|location_mode=$locationMode")
            append("|custom_lat=$customLat")
            append("|custom_lon=$customLon")
            append("|custom_alt=$customAlt")
            append("|ip_mode=$ipMode")
            if (customIp.isNotEmpty()) append("|custom_ip=$customIp")
            append("|mac_mode=$macMode")
            if (customMac.isNotEmpty()) append("|custom_mac=$customMac")
            append("|phone_mode=$phoneMode")
            if (customPhone.isNotEmpty()) append("|custom_phone=$customPhone")
            append("|build_mode=$buildMode")
            if (customModel.isNotEmpty()) append("|custom_model=$customModel")
            if (customBrand.isNotEmpty()) append("|custom_brand=$customBrand")
            append("|operator_mode=$operatorMode")
            if (customMccMnc.isNotEmpty()) append("|custom_mcc_mnc=$customMccMnc")
            append("|adid_mode=$adIdMode")
            if (customAdId.isNotEmpty()) append("|custom_adid=$customAdId")
        }
    }
}
