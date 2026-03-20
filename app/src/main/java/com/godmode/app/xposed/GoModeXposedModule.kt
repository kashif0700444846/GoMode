package com.godmode.app.xposed

import android.location.Location
import android.util.Log
import de.robv.android.xposed.*
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File

/**
 * GoMode Xposed Module - hooks into every app process via LSPosed to intercept
 * hardware identifier reads and apply spoofed values from GoMode's configuration.
 *
 * IMPORTANT: This class is loaded by LSPosed into the target app's process.
 * Configuration is read via XSharedPreferences from GoMode's shared_prefs directory.
 * LSPosed grants the SELinux access needed for cross-process prefs reading.
 *
 * To activate: Enable GoMode in LSPosed manager, then reboot.
 */
class GoModeXposedModule : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "GoMode_Xposed"
        private const val MODULE_PKG = "com.godmode.app"
        private const val LOG_DIR_PRIMARY = "/data/local/tmp/gomode/logs"
        private const val LOG_DIR_LEGACY = "/data/data/com.godmode.app/files/xposed_logs"
        private const val CONFIG_DIR_FALLBACK = "/data/local/tmp/gomode/config"

        // Modes: 0=disabled, 1=custom value, 2=random
        const val MODE_DISABLED = 0
        const val MODE_CUSTOM = 1
        const val MODE_RANDOM = 2
        const val MODE_EMPTY = 3

        private class ConfigAccessor(
            private val xPrefs: XSharedPreferences?,
            private val fallback: Map<String, String>
        ) {
            fun getBoolean(key: String, default: Boolean): Boolean {
                xPrefs?.let {
                    try {
                        return it.getBoolean(key, fallbackBoolean(key, default))
                    } catch (_: Throwable) {
                    }
                }
                return fallbackBoolean(key, default)
            }

            fun getInt(key: String, default: Int): Int {
                xPrefs?.let {
                    try {
                        return it.getInt(key, fallbackInt(key, default))
                    } catch (_: Throwable) {
                    }
                }
                return fallbackInt(key, default)
            }

            fun getString(key: String, default: String): String {
                xPrefs?.let {
                    try {
                        return it.getString(key, fallback[key] ?: default) ?: (fallback[key] ?: default)
                    } catch (_: Throwable) {
                    }
                }
                return fallback[key] ?: default
            }

            private fun fallbackBoolean(key: String, default: Boolean): Boolean {
                val raw = fallback[key] ?: return default
                return raw == "1" || raw.equals("true", ignoreCase = true)
            }

            private fun fallbackInt(key: String, default: Int): Int {
                return fallback[key]?.toIntOrNull() ?: default
            }
        }

        private fun loadFallbackPrefs(packageName: String): Map<String, String> {
            return try {
                val file = File("$CONFIG_DIR_FALLBACK/$packageName.conf")
                if (!file.exists()) return emptyMap()

                file.readLines()
                    .mapNotNull { line ->
                        if (!line.contains("=")) return@mapNotNull null
                        val parts = line.split("=", limit = 2)
                        if (parts[0].isBlank()) null else parts[0].trim() to parts.getOrElse(1) { "" }.trim()
                    }
                    .toMap()
            } catch (_: Throwable) {
                emptyMap()
            }
        }

        private fun loadPrefs(packageName: String): ConfigAccessor? {
            return try {
                val prefs = XSharedPreferences(MODULE_PKG, "xposed_$packageName")
                try {
                    prefs.makeWorldReadable()
                } catch (_: Throwable) {
                }
                try {
                    prefs.reload()
                } catch (_: Throwable) {
                }

                val fallback = loadFallbackPrefs(packageName)
                val activeFromXposed = try { prefs.getBoolean("active", false) } catch (_: Throwable) { false }
                val fallbackActiveRaw = fallback["active"] ?: ""
                val activeFromFallback = fallbackActiveRaw == "1" || fallbackActiveRaw.equals("true", ignoreCase = true)

                if (!activeFromXposed && !activeFromFallback) return null
                ConfigAccessor(if (activeFromXposed) prefs else null, fallback)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to load prefs for $packageName: ${e.message}")
                null
            }
        }

        fun randomImei(): String {
            val tac = listOf("35780800", "35780801", "35614800", "86493200")
            val base = tac.random() + (1..6).joinToString("") { (0..9).random().toString() }
            // Luhn check digit
            var sum = 0
            for (i in base.indices) {
                var d = base[i] - '0'
                if ((base.length - i) % 2 == 0) {
                    d *= 2
                    if (d > 9) d -= 9
                }
                sum += d
            }
            val check = (10 - (sum % 10)) % 10
            return base + check.toString()
        }

        fun randomAndroidId(): String =
            (1..16).map { "0123456789abcdef"[(0..15).random()] }.joinToString("")

        fun randomMac(): String {
            val b = (1..6).map { (0..255).random() }.toMutableList()
            b[0] = b[0] and 0xFE // clear multicast bit
            b[0] = b[0] or 0x02  // set locally administered
            return b.joinToString(":") { String.format("%02x", it) }
        }

        fun randomSerial(): String =
            (1..8).map { "0123456789ABCDEF"[(0..15).random()] }.joinToString("")

        fun macToBytes(mac: String): ByteArray =
            mac.split(":").map { it.toInt(16).toByte() }.toByteArray()
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Skip GoMode itself and core system processes
        if (lpparam.packageName == MODULE_PKG ||
            lpparam.packageName == "android" ||
            lpparam.packageName == "com.android.settings") return

        val prefs = loadPrefs(lpparam.packageName) ?: return
        Log.d(TAG, "Applying hooks to ${lpparam.packageName}")

        hookTelephony(lpparam, prefs)
        hookAndroidId(lpparam, prefs)
        hookBuildProps(lpparam, prefs)
        hookWifiMac(lpparam, prefs)
        hookMediaDrm(lpparam, prefs)
        hookLocation(lpparam, prefs)
        hookCamera(lpparam, prefs)
        hookMicrophone(lpparam, prefs)
        hookPlayServices(lpparam, prefs)
    }

    // ===== TELEPHONY (IMEI/IMSI/Phone) =====

    private fun hookTelephony(lpparam: XC_LoadPackage.LoadPackageParam, prefs: ConfigAccessor) {
        val imeiMode = prefs.getInt("imei_mode", MODE_DISABLED)
        if (imeiMode == MODE_DISABLED) return

        val cachedImei = when (imeiMode) {
            MODE_CUSTOM -> {
                val custom = prefs.getString("imei_value", "")
                if (custom.matches(Regex("[0-9]{14,17}"))) custom.take(15) else randomImei()
            }
            MODE_RANDOM -> randomImei()
            MODE_EMPTY -> "000000000000000"
            else -> randomImei()
        }

        val imeiHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                param.result = cachedImei
                logAccess(lpparam.packageName, "IMEI", cachedImei)
            }
        }

        // Hook all IMEI-related methods
        for (methodName in listOf("getImei", "getDeviceId", "getMeid")) {
            try {
                XposedHelpers.findAndHookMethod(
                    "android.telephony.TelephonyManager",
                    lpparam.classLoader, methodName, imeiHook
                )
            } catch (_: Throwable) {}
            // Also hook with int param (slot index for dual SIM)
            try {
                XposedHelpers.findAndHookMethod(
                    "android.telephony.TelephonyManager",
                    lpparam.classLoader, methodName, Int::class.java, imeiHook
                )
            } catch (_: Throwable) {}
        }

        // IMSI (getSubscriberId)
        val imsiMode = prefs.getInt("imsi_mode", MODE_DISABLED)
        if (imsiMode != MODE_DISABLED) {
            val imsiVal = if (imsiMode == MODE_CUSTOM) {
                val custom = prefs.getString("imsi_value", "")
                if (custom.matches(Regex("[0-9]{14,17}"))) custom.take(15) else "000000000000000"
            } else "000000000000000"
            try {
                XposedHelpers.findAndHookMethod(
                    "android.telephony.TelephonyManager",
                    lpparam.classLoader, "getSubscriberId",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            param.result = imsiVal
                            logAccess(lpparam.packageName, "IMSI", imsiVal)
                        }
                    }
                )
            } catch (_: Throwable) {}
        }

        // Phone number
        val phoneMode = prefs.getInt("phone_mode", MODE_DISABLED)
        if (phoneMode != MODE_DISABLED) {
            val phoneVal = if (phoneMode == MODE_CUSTOM) prefs.getString("phone_value", "") else ""
            try {
                XposedHelpers.findAndHookMethod(
                    "android.telephony.TelephonyManager",
                    lpparam.classLoader, "getLine1Number",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            param.result = phoneVal
                        }
                    }
                )
            } catch (_: Throwable) {}
        }
    }

    // ===== ANDROID ID =====

    private fun hookAndroidId(lpparam: XC_LoadPackage.LoadPackageParam, prefs: ConfigAccessor) {
        val mode = prefs.getInt("android_id_mode", MODE_DISABLED)
        if (mode == MODE_DISABLED) return

        val idValue = when (mode) {
            MODE_CUSTOM -> prefs.getString("android_id_value", "")
            MODE_RANDOM -> randomAndroidId()
            else -> "0000000000000000"
        }

        try {
            XposedHelpers.findAndHookMethod(
                "android.provider.Settings\$Secure",
                lpparam.classLoader,
                "getString",
                android.content.ContentResolver::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.args[1] == android.provider.Settings.Secure.ANDROID_ID) {
                            param.result = idValue
                            logAccess(lpparam.packageName, "ANDROID_ID", idValue)
                        }
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    // ===== BUILD PROPS (Serial, Model, Brand, Fingerprint) =====

    private fun hookBuildProps(lpparam: XC_LoadPackage.LoadPackageParam, prefs: ConfigAccessor) {
        val buildMode = prefs.getInt("build_mode", MODE_DISABLED)
        val serialMode = prefs.getInt("serial_mode", MODE_DISABLED)

        if (buildMode == MODE_DISABLED && serialMode == MODE_DISABLED) return

        try {
            if (serialMode != MODE_DISABLED) {
                val serial = when (serialMode) {
                    MODE_CUSTOM -> prefs.getString("serial_value", "unknown")
                    MODE_RANDOM -> randomSerial()
                    else -> "unknown"
                }
                XposedHelpers.setStaticObjectField(android.os.Build::class.java, "SERIAL", serial)
                logAccess(lpparam.packageName, "SERIAL", serial)
            }

            if (buildMode != MODE_DISABLED) {
                val model = prefs.getString("build_model", "")
                val brand = prefs.getString("build_brand", "")
                val fingerprint = prefs.getString("build_fingerprint", "")
                val manufacturer = prefs.getString("build_manufacturer", "")

                if (model.isNotEmpty()) XposedHelpers.setStaticObjectField(android.os.Build::class.java, "MODEL", model)
                if (brand.isNotEmpty()) {
                    XposedHelpers.setStaticObjectField(android.os.Build::class.java, "BRAND", brand)
                    XposedHelpers.setStaticObjectField(android.os.Build::class.java, "MANUFACTURER", manufacturer.ifEmpty { brand })
                }
                if (fingerprint.isNotEmpty()) XposedHelpers.setStaticObjectField(android.os.Build::class.java, "FINGERPRINT", fingerprint)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Build prop hook failed: ${e.message}")
        }
    }

    // ===== MAC ADDRESS =====

    private fun hookWifiMac(lpparam: XC_LoadPackage.LoadPackageParam, prefs: ConfigAccessor) {
        val mode = prefs.getInt("mac_mode", MODE_DISABLED)
        if (mode == MODE_DISABLED) return

        val mac = when (mode) {
            MODE_CUSTOM -> prefs.getString("mac_value", "")
            MODE_RANDOM -> randomMac()
            else -> "02:00:00:00:00:00"
        }

        try {
            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiInfo", lpparam.classLoader, "getMacAddress",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.result = mac
                        logAccess(lpparam.packageName, "MAC_ADDRESS", mac)
                    }
                }
            )
        } catch (_: Throwable) {}

        try {
            XposedHelpers.findAndHookMethod(
                "java.net.NetworkInterface", lpparam.classLoader, "getHardwareAddress",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (mac.isNotEmpty()) param.result = macToBytes(mac)
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    // ===== WIDEVINE DRM ID =====

    private fun hookMediaDrm(lpparam: XC_LoadPackage.LoadPackageParam, prefs: ConfigAccessor) {
        val mode = prefs.getInt("drm_mode", MODE_DISABLED)
        if (mode == MODE_DISABLED) return

        val drmBytes: ByteArray = if (mode == MODE_CUSTOM) {
            val hex = prefs.getString("drm_value", "")
            if (hex.length >= 32) hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            else ByteArray(16) { (Math.random() * 256).toInt().toByte() }
        } else {
            ByteArray(16) { (Math.random() * 256).toInt().toByte() }
        }

        try {
            XposedHelpers.findAndHookMethod(
                "android.media.MediaDrm", lpparam.classLoader,
                "getPropertyByteArray", String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.args[0] == "deviceUniqueId") {
                            param.result = drmBytes
                            logAccess(lpparam.packageName, "DRM_ID", "Widevine device ID spoofed")
                        }
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    // ===== GPS / LOCATION =====

    private fun hookLocation(lpparam: XC_LoadPackage.LoadPackageParam, prefs: ConfigAccessor) {
        val mode = prefs.getInt("location_mode", MODE_DISABLED)
        if (mode == MODE_DISABLED) return

        val lat = prefs.getFloat("location_lat", 37.4219f).toDouble()
        val lon = prefs.getFloat("location_lon", -122.084f).toDouble()

        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val loc = Location("gps")
                loc.latitude = lat
                loc.longitude = lon
                loc.accuracy = 10f
                loc.time = System.currentTimeMillis()
                param.result = loc
                logAccess(lpparam.packageName, "LOCATION", "$lat,$lon")
            }
        }

        try { XposedHelpers.findAndHookMethod("android.location.LocationManager", lpparam.classLoader, "getLastKnownLocation", String::class.java, hook) } catch (_: Throwable) {}
        try { XposedHelpers.findAndHookMethod("android.location.LocationManager", lpparam.classLoader, "getLastKnownLocationForUser", String::class.java, android.os.UserHandle::class.java, hook) } catch (_: Throwable) {}
    }

    // ===== CAMERA BLOCK =====

    private fun hookCamera(lpparam: XC_LoadPackage.LoadPackageParam, prefs: ConfigAccessor) {
        if (!prefs.getBoolean("block_camera", false)) return

        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.throwable = SecurityException("Camera access blocked by GoMode Privacy Engine")
                logAccess(lpparam.packageName, "CAMERA_BLOCKED", "Camera access prevented")
            }
        }

        try { XposedHelpers.findAndHookMethod("android.hardware.camera2.CameraManager", lpparam.classLoader, "openCamera", String::class.java, android.hardware.camera2.CameraDevice.StateCallback::class.java, android.os.Handler::class.java, hook) } catch (_: Throwable) {}
        try { XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "open", hook) } catch (_: Throwable) {}
        try { XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "open", Int::class.java, hook) } catch (_: Throwable) {}
    }

    // ===== MICROPHONE BLOCK =====

    private fun hookMicrophone(lpparam: XC_LoadPackage.LoadPackageParam, prefs: ConfigAccessor) {
        if (!prefs.getBoolean("block_mic", false)) return

        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.throwable = SecurityException("Microphone access blocked by GoMode Privacy Engine")
                logAccess(lpparam.packageName, "MIC_BLOCKED", "Microphone access prevented")
            }
        }

        try { XposedHelpers.findAndHookMethod("android.media.AudioRecord", lpparam.classLoader, "startRecording", hook) } catch (_: Throwable) {}
        try { XposedHelpers.findAndHookMethod("android.media.MediaRecorder", lpparam.classLoader, "start", hook) } catch (_: Throwable) {}
    }

    // ===== GOOGLE PLAY SERVICES ADVERTISING ID =====

    private fun hookPlayServices(lpparam: XC_LoadPackage.LoadPackageParam, prefs: ConfigAccessor) {
        val mode = prefs.getInt("ad_id_mode", MODE_DISABLED)
        if (mode == MODE_DISABLED) return

        val adIdValue = when (mode) {
            MODE_CUSTOM -> prefs.getString("ad_id_value", "")
            MODE_RANDOM -> randomAndroidId()
            else -> "00000000-0000-0000-0000-000000000000"
        }

        // Hook Play Services AdvertisingIdClient via reflection
        try {
            XposedHelpers.findAndHookMethod(
                "com.google.android.gms.ads.identifier.AdvertisingIdClient\$Info",
                lpparam.classLoader, "getId",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.result = adIdValue
                        logAccess(lpparam.packageName, "AD_ID", adIdValue)
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    // ===== LOGGING =====

    private fun appendLogToFile(directoryPath: String, entry: String) {
        try {
            val dir = File(directoryPath)
            if (!dir.exists()) dir.mkdirs()
            val logFile = File(dir, "access.jsonl")
            logFile.appendText(entry)
        } catch (_: Throwable) {
        }
    }

    private fun logAccess(packageName: String, type: String, value: String, spoofed: Boolean = true) {
        try {
            val ts = System.currentTimeMillis()
            val safeValue = value
                .replace("\"", "'")
                .replace("\n", " ")
                .replace("\r", " ")

            val entry = buildString {
                append("{")
                append("\"pkg\":\"$packageName\",")
                append("\"type\":\"$type\",")
                append("\"value\":\"$safeValue\",")
                append("\"spoofed\":${if (spoofed) 1 else 0},")
                append("\"ts\":$ts")
                append("}\n")
            }

            appendLogToFile(LOG_DIR_PRIMARY, entry)
            appendLogToFile(LOG_DIR_LEGACY, entry)

            Log.i(TAG, "GOMODE_LOG|$packageName|$type|$safeValue|$ts")
        } catch (_: Throwable) {
        }
    }
}
