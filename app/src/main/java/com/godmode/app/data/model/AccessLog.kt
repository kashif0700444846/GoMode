package com.godmode.app.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Records every data access attempt intercepted by the GodMode hook engine.
 * Stored in Room database for historical review.
 */
@Entity(
    tableName = "access_logs",
    indices = [
        Index(value = ["packageName"]),
        Index(value = ["timestamp"]),
        Index(value = ["propertyType"])
    ]
)
data class AccessLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // Which app made the access
    val packageName: String,
    val appName: String = "",
    val uid: Int = -1,

    // When it happened
    val timestamp: Long = System.currentTimeMillis(),

    // What was accessed
    val propertyType: String,  // e.g., "IMEI", "LOCATION", "ANDROID_ID"
    val propertyCategory: String = "",  // e.g., "TELEPHONY", "LOCATION", "NETWORK"

    // The values
    val originalValue: String = "",
    val spoofedValue: String = "",
    val wasSpoofed: Boolean = false,

    // Additional context
    val callerMethod: String = "",  // Java method that triggered the access
    val networkDestination: String = "",  // For network accesses: IP/hostname
    val networkPort: Int = 0,
    val networkProtocol: String = "",  // TCP/UDP/HTTP/HTTPS

    // Permission that was used
    val permissionUsed: String = ""
) {
    companion object {
        // Property type constants
        const val TYPE_IMEI = "IMEI"
        const val TYPE_IMSI = "IMSI"
        const val TYPE_ANDROID_ID = "ANDROID_ID"
        const val TYPE_SERIAL = "SERIAL_NUMBER"
        const val TYPE_LOCATION_GPS = "LOCATION_GPS"
        const val TYPE_LOCATION_NETWORK = "LOCATION_NETWORK"
        const val TYPE_IP_ADDRESS = "IP_ADDRESS"
        const val TYPE_MAC_ADDRESS = "MAC_ADDRESS"
        const val TYPE_PHONE_NUMBER = "PHONE_NUMBER"
        const val TYPE_SIM_SERIAL = "SIM_SERIAL"
        const val TYPE_OPERATOR = "NETWORK_OPERATOR"
        const val TYPE_CAMERA = "CAMERA"
        const val TYPE_MICROPHONE = "MICROPHONE"
        const val TYPE_CONTACTS = "CONTACTS"
        const val TYPE_CALENDAR = "CALENDAR"
        const val TYPE_CLIPBOARD = "CLIPBOARD"
        const val TYPE_SENSOR = "SENSOR"
        const val TYPE_NETWORK_CONN = "NETWORK_CONNECTION"
        const val TYPE_ADVERTISING_ID = "ADVERTISING_ID"
        const val TYPE_BUILD_PROPS = "BUILD_PROPERTIES"
        const val TYPE_WIFI_INFO = "WIFI_INFO"
        const val TYPE_BLUETOOTH = "BLUETOOTH"
        const val TYPE_ACCOUNTS = "ACCOUNTS"

        // Category constants
        const val CAT_TELEPHONY = "TELEPHONY"
        const val CAT_LOCATION = "LOCATION"
        const val CAT_NETWORK = "NETWORK"
        const val CAT_DEVICE_ID = "DEVICE_ID"
        const val CAT_MEDIA = "MEDIA"
        const val CAT_PERSONAL_DATA = "PERSONAL_DATA"
        const val CAT_SYSTEM = "SYSTEM"

        fun categoryForType(type: String): String = when (type) {
            TYPE_IMEI, TYPE_IMSI, TYPE_PHONE_NUMBER, TYPE_SIM_SERIAL, TYPE_OPERATOR -> CAT_TELEPHONY
            TYPE_LOCATION_GPS, TYPE_LOCATION_NETWORK -> CAT_LOCATION
            TYPE_IP_ADDRESS, TYPE_MAC_ADDRESS, TYPE_WIFI_INFO, TYPE_NETWORK_CONN -> CAT_NETWORK
            TYPE_ANDROID_ID, TYPE_SERIAL, TYPE_ADVERTISING_ID, TYPE_BUILD_PROPS -> CAT_DEVICE_ID
            TYPE_CAMERA, TYPE_MICROPHONE -> CAT_MEDIA
            TYPE_CONTACTS, TYPE_CALENDAR, TYPE_CLIPBOARD, TYPE_ACCOUNTS -> CAT_PERSONAL_DATA
            TYPE_SENSOR, TYPE_BLUETOOTH -> CAT_SYSTEM
            else -> CAT_SYSTEM
        }

        fun iconForType(type: String): String = when (type) {
            TYPE_IMEI, TYPE_IMSI -> "phone_android"
            TYPE_LOCATION_GPS, TYPE_LOCATION_NETWORK -> "location_on"
            TYPE_IP_ADDRESS, TYPE_NETWORK_CONN -> "language"
            TYPE_MAC_ADDRESS, TYPE_WIFI_INFO -> "wifi"
            TYPE_ANDROID_ID, TYPE_SERIAL -> "fingerprint"
            TYPE_CAMERA -> "camera_alt"
            TYPE_MICROPHONE -> "mic"
            TYPE_CONTACTS -> "contacts"
            TYPE_CALENDAR -> "calendar_today"
            TYPE_CLIPBOARD -> "content_paste"
            TYPE_SENSOR -> "sensors"
            TYPE_ADVERTISING_ID -> "ads_click"
            TYPE_BLUETOOTH -> "bluetooth"
            TYPE_ACCOUNTS -> "account_circle"
            else -> "security"
        }
    }
}
