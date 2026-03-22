package com.godmode.app.daemon

import android.content.Context
import android.os.Build
import android.util.Log
import com.godmode.app.data.model.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Manages root operations, daemon installation, and daemon communication.
 * Bridges between the Kotlin app and the native C++ daemon.
 */
class RootManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "GodMode_RootMgr"
        private const val DAEMON_NAME = "godmoded"
        private const val HOOK_LIB_NAME = "libgodmode_hook.so"
        private const val DAEMON_DATA_PATH = "/data/local/tmp/godmoded"
        private const val HOOK_LIB_SYSTEM_PATH = "/system/lib64/libgodmode_hook.so"
        private const val HOOK_LIB_DATA_PATH = "/data/local/tmp/libgodmode_hook.so"

        @Volatile
        private var INSTANCE: RootManager? = null

        // Track if native library loaded successfully
        var nativeLibLoaded = false
            private set

        fun getInstance(context: Context): RootManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: RootManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        // Load native library
        init {
            nativeLibLoaded = try {
                System.loadLibrary("godmode_jni")
                Log.i(TAG, "Native JNI library loaded")
                true
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to load native library: ${e.message}")
                false
            }
        }
    }

    // Native method declarations
    external fun nativeIsRooted(): Boolean
    external fun nativeIsMagiskInstalled(): Boolean
    external fun nativeIsKernelSUInstalled(): Boolean
    external fun nativeIsLSPosedInstalled(): Boolean
    external fun nativeIsDaemonRunning(): Boolean
    external fun nativeGetDaemonStatus(): String
    external fun nativeSendConfig(config: String): Boolean
    external fun nativeGetLogs(packageName: String): String
    external fun nativeInstallDaemon(binaryPath: String): Boolean
    external fun nativeExecRoot(command: String): String

    data class RootStatus(
        val isRooted: Boolean,
        val hasMagisk: Boolean,
        val hasKernelSU: Boolean,
        val hasLSPosed: Boolean,
        val isDaemonRunning: Boolean,
        val daemonVersion: String = ""
    )

    data class InstallResult(
        val success: Boolean,
        val message: String
    )

    data class Module(
        val id: String,
        val name: String,
        val version: String,
        val author: String,
        val description: String,
        val isEnabled: Boolean,
        val isZygisk: Boolean = false,
        val type: String = "magisk"
    )

    data class SuperuserApp(
        val packageName: String,
        val uid: String,
        val policy: String
    )

    /**
     * Execute a root command safely.
     * IMPORTANT: nativeExecRoot wraps in single quotes (su -c '...') which breaks
     * commands containing single quotes. Route all complex commands to Kotlin fallback.
     */
    fun execRootCommand(command: String): String {
        // Only use native for simple commands without shell operators or single quotes
        val isSimple = !command.contains("'") && !command.contains("|") &&
                !command.contains("&&") && !command.contains(">") &&
                !command.contains(";") && !command.contains("$")
        if (nativeLibLoaded && isSimple) {
            try { return nativeExecRoot(command) } catch (e: Throwable) {
                Log.w(TAG, "Native exec failed: ${e.message}")
            }
        }
        return execRootCommandKotlin(command)
    }

    private fun execRootCommandKotlin(command: String): String {
        return try {
            // Array form passes command directly to su without additional quoting
            // su receives "command" as a single argument and passes it to sh -c
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(15, TimeUnit.SECONDS)
            output.trim()
        } catch (e: Throwable) {
            Log.e(TAG, "Kotlin root exec failed: ${e.message}")
            "Error: ${e.message}"
        }
    }

    /**
     * Check root status and detect installed root tools.
     * Uses execRootCommand for reliable detection (File.exists fails on /data/adb without root).
     */
    suspend fun getRootStatus(): RootStatus = withContext(Dispatchers.IO) {
        try {
            // Request root first so subsequent checks work
            val isRooted = try {
                val id = execRootCommand("id").trim()
                id.contains("uid=0")
            } catch (e: Throwable) { checkRootedKotlin() }

            val hasMagisk = try {
                val r = execRootCommand("magisk --version 2>/dev/null || ls /data/adb/magisk 2>/dev/null || ls /data/adb/magisk.db 2>/dev/null").trim()
                r.isNotEmpty() && !r.startsWith("Error")
            } catch (e: Throwable) { false }

            val hasKernelSU = try {
                val r = execRootCommand("ksud --version 2>/dev/null || cat /proc/sys/kernel/ksud_version 2>/dev/null || ls /data/adb/ksu 2>/dev/null").trim()
                r.isNotEmpty() && !r.startsWith("Error") && !r.contains("No such file")
            } catch (e: Throwable) { false }

            val hasLSPosed = try {
                val r = execRootCommand("ls /data/misc/lspd 2>/dev/null || ls /data/adb/modules/zygisk_lsposed 2>/dev/null || ls /data/adb/modules/lsposed 2>/dev/null").trim()
                r.isNotEmpty() && !r.startsWith("Error") && !r.contains("No such file")
            } catch (e: Throwable) { false }

            val isDaemonRunning = if (nativeLibLoaded) {
                try { nativeIsDaemonRunning() } catch (e: Throwable) { false }
            } else false

            var daemonVersion = ""
            if (isDaemonRunning && nativeLibLoaded) {
                try {
                    val status = nativeGetDaemonStatus()
                    daemonVersion = status.substringAfter("version=", "").substringBefore("|")
                } catch (e: Throwable) { }
            }

            RootStatus(isRooted, hasMagisk, hasKernelSU, hasLSPosed, isDaemonRunning, daemonVersion)
        } catch (e: Throwable) {
            Log.e(TAG, "Error getting root status", e)
            RootStatus(false, false, false, false, false)
        }
    }

    private fun checkRootedKotlin(): Boolean {
        val suPaths = listOf("/sbin/su", "/system/bin/su", "/system/xbin/su",
            "/data/local/xbin/su", "/data/local/bin/su", "/su/bin/su",
            "/magisk/.core/bin/su", "/data/adb/magisk", "/data/adb/ksu")
        return suPaths.any { File(it).exists() }
    }

    /**
     * Request root permission by running a test su command.
     */
    suspend fun requestRoot(): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = execRootCommand("id")
            val granted = result.contains("uid=0")
            Log.i(TAG, "Root request result: $result, granted=$granted")
            granted
        } catch (e: Throwable) {
            Log.e(TAG, "Root request failed", e)
            false
        }
    }

    /**
     * Install the GodMode daemon to the device.
     */
    suspend fun installDaemon(): InstallResult = withContext(Dispatchers.IO) {
        try {
            // Try to copy daemon binary from assets (may not exist)
            val daemonFile = File(context.filesDir, DAEMON_NAME)
            try {
                copyAssetToFile(DAEMON_NAME, daemonFile)
            } catch (e: Throwable) {
                Log.w(TAG, "Daemon asset not found (normal): ${e.message}")
            }

            // Copy hook library from native libs
            val hookLibFile = File(context.filesDir, HOOK_LIB_NAME)
            val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
            val sourceLib = File(nativeLibDir, HOOK_LIB_NAME)

            if (sourceLib.exists()) {
                try { sourceLib.copyTo(hookLibFile, overwrite = true) } catch (e: Throwable) { }
            }

            if (daemonFile.exists()) {
                val cmd = "cp '${daemonFile.absolutePath}' '$DAEMON_DATA_PATH' && " +
                        "chmod 755 '$DAEMON_DATA_PATH'"
                execRootCommand(cmd)
            }

            if (hookLibFile.exists()) {
                val cmd = "cp '${hookLibFile.absolutePath}' '$HOOK_LIB_DATA_PATH' && " +
                        "chmod 644 '$HOOK_LIB_DATA_PATH'"
                execRootCommand(cmd)
                tryInstallToSystem(hookLibFile)
            }

            setupBootPersistence()

            InstallResult(true, "Installation complete")
        } catch (e: Throwable) {
            Log.e(TAG, "Daemon installation failed", e)
            InstallResult(false, "Installation failed: ${e.message}")
        }
    }

    /**
     * Start the daemon process.
     */
    suspend fun startDaemon(): Boolean = withContext(Dispatchers.IO) {
        try {
            val running = try { nativeLibLoaded && nativeIsDaemonRunning() } catch (e: Throwable) { false }
            if (running) return@withContext true

            val daemonPath = if (File(DAEMON_DATA_PATH).exists()) DAEMON_DATA_PATH
            else "${context.filesDir}/$DAEMON_NAME"

            if (File(daemonPath).exists()) {
                execRootCommand("$daemonPath &")
                Thread.sleep(1000)
                return@withContext try { nativeLibLoaded && nativeIsDaemonRunning() } catch (e: Throwable) { false }
            }
            false
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start daemon", e)
            false
        }
    }

    /**
     * Stop the daemon process.
     */
    suspend fun stopDaemon(): Boolean = withContext(Dispatchers.IO) {
        try {
            execRootCommand("pkill -f godmoded")
            Thread.sleep(500)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to stop daemon", e)
            false
        }
    }

    /**
     * Send spoofing configuration for an app to the daemon.
     */
    suspend fun sendAppConfig(config: AppConfig): Boolean = withContext(Dispatchers.IO) {
        try {
            val configStr = config.toDaemonConfigString()
            if (nativeLibLoaded) nativeSendConfig(configStr) else false
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to send config", e)
            false
        }
    }

    /**
     * Notify daemon that config for a specific package has been updated.
     */
    suspend fun notifyConfigUpdate(packageName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val running = try { nativeLibLoaded && nativeIsDaemonRunning() } catch (e: Throwable) { false }
            if (running && nativeLibLoaded) nativeSendConfig("RELOAD_CONFIG|$packageName")
            else false
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to notify config update", e)
            false
        }
    }

    /**
     * Manually inject the hook library into a running process.
     */
    suspend fun injectIntoProcess(pid: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = execRootCommand("kill -0 $pid 2>/dev/null && echo OK")
            if (!result.contains("OK")) return@withContext false
            if (nativeLibLoaded) nativeSendConfig("INJECT|$pid") else false
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to inject into process $pid", e)
            false
        }
    }

    /**
     * Get the real device IMEI using multiple fallback methods.
     */
    suspend fun getRealImei(): String = withContext(Dispatchers.IO) {
        try {
            // Method 1: service call iphonesubinfo (most reliable on rooted devices)
            val serviceResult = execRootCommand(
                "service call iphonesubinfo 1 2>/dev/null | grep -oP \"'[0-9.]+'\" | tr -d \"'.\" | tr -d '\\n'"
            ).trim()
            if (serviceResult.matches(Regex("[0-9]{14,15}"))) return@withContext serviceResult

            // Method 2: telephony properties
            for (prop in listOf("ril.imei1", "ril.imei", "gsm.imei", "ro.ril.imei", "persist.radio.device.imei")) {
                val result = execRootCommand("getprop $prop 2>/dev/null").trim()
                if (result.matches(Regex("[0-9]{14,15}"))) return@withContext result
            }

            // Method 3: dumpsys iphonesubinfo
            val dumpsys = execRootCommand("dumpsys iphonesubinfo 2>/dev/null | grep -i IMEI | head -2")
            val imeiMatch = Regex("[0-9]{14,15}").find(dumpsys)
            if (imeiMatch != null) return@withContext imeiMatch.value

            ""
        } catch (e: Throwable) { "" }
    }

    /**
     * Get the real Android ID.
     */
    suspend fun getRealAndroidId(): String = withContext(Dispatchers.IO) {
        try { execRootCommand("settings get secure android_id").trim() }
        catch (e: Throwable) { "" }
    }

    /**
     * Get the real device serial.
     */
    suspend fun getRealSerial(): String = withContext(Dispatchers.IO) {
        try { execRootCommand("getprop ro.serialno").trim() }
        catch (e: Throwable) { "" }
    }

    // ===== Module Management =====

    /**
     * Get installed Magisk/KernelSU modules from /data/adb/modules/
     */
    suspend fun getInstalledModules(): List<Module> = withContext(Dispatchers.IO) {
        try {
            val modules = mutableListOf<Module>()
            val moduleList = execRootCommand("ls /data/adb/modules 2>/dev/null").trim()
            if (moduleList.isEmpty() || moduleList.startsWith("Error")) return@withContext emptyList()

            for (moduleId in moduleList.split("\n").map { it.trim() }.filter { it.isNotBlank() }) {
                val props = execRootCommand("cat /data/adb/modules/$moduleId/module.prop 2>/dev/null").trim()
                val propMap = props.split("\n")
                    .filter { it.contains("=") }
                    .associate {
                        val parts = it.split("=", limit = 2)
                        parts[0].trim() to parts.getOrElse(1) { "" }.trim()
                    }

                val isDisabled = File("/data/adb/modules/$moduleId/disable").exists() ||
                        execRootCommand("test -f /data/adb/modules/$moduleId/disable && echo YES || echo NO")
                            .trim() == "YES"

                val hasZygiskDir = execRootCommand(
                    "test -d /data/adb/modules/$moduleId/zygisk && echo YES || echo NO"
                ).trim() == "YES"

                modules.add(
                    Module(
                        id = moduleId,
                        name = propMap["name"] ?: moduleId,
                        version = propMap["version"] ?: "Unknown",
                        author = propMap["author"] ?: "Unknown",
                        description = propMap["description"] ?: "",
                        isEnabled = !isDisabled,
                        isZygisk = hasZygiskDir
                    )
                )
            }
            modules
        } catch (e: Throwable) {
            Log.e(TAG, "Error loading modules", e)
            emptyList()
        }
    }

    /**
     * Enable a module (remove the 'disable' marker file)
     */
    suspend fun enableModule(moduleId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            execRootCommand("rm -f /data/adb/modules/$moduleId/disable")
            true
        } catch (e: Throwable) { false }
    }

    /**
     * Disable a module (create the 'disable' marker file)
     */
    suspend fun disableModule(moduleId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            execRootCommand("touch /data/adb/modules/$moduleId/disable")
            true
        } catch (e: Throwable) { false }
    }

    /**
     * Schedule a module for deletion (create the 'remove' marker file)
     */
    suspend fun scheduleDeleteModule(moduleId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            execRootCommand("touch /data/adb/modules/$moduleId/remove")
            true
        } catch (e: Throwable) { false }
    }

    /**
     * Get Magisk version string
     */
    suspend fun getMagiskVersion(): String = withContext(Dispatchers.IO) {
        try {
            val result = execRootCommand("magisk --version 2>/dev/null").trim()
            result.ifEmpty { "Unknown" }
        } catch (e: Throwable) { "" }
    }

    /**
     * Get KernelSU version string
     */
    suspend fun getKSUVersion(): String = withContext(Dispatchers.IO) {
        try {
            val ksud = execRootCommand("ksud --version 2>/dev/null").trim()
            if (ksud.isNotEmpty() && !ksud.startsWith("Error")) return@withContext ksud
            val kernelVersion = execRootCommand(
                "cat /proc/version 2>/dev/null | grep -o 'KernelSU [0-9.]*'"
            ).trim()
            kernelVersion
        } catch (e: Throwable) { "" }
    }

    /**
     * Get LSPosed version string
     */
    suspend fun getLSPosedVersion(): String = withContext(Dispatchers.IO) {
        try {
            var result = execRootCommand(
                "cat /data/adb/modules/zygisk_lsposed/module.prop 2>/dev/null | grep '^version='"
            ).trim()
            if (result.isNotEmpty()) return@withContext result.removePrefix("version=")

            result = execRootCommand(
                "cat /data/adb/modules/lsposed/module.prop 2>/dev/null | grep '^version='"
            ).trim()
            result.removePrefix("version=").ifEmpty { "" }
        } catch (e: Throwable) { "" }
    }

    /**
     * Get apps that have been granted superuser access
     */
    suspend fun getSuperuserApps(): List<SuperuserApp> = withContext(Dispatchers.IO) {
        try {
            // Try Magisk database first
            val magiskResult = execRootCommand(
                "sqlite3 /data/adb/magisk.db \"SELECT uid,package_name,policy FROM policies WHERE policy=1;\" 2>/dev/null"
            ).trim()
            if (magiskResult.isNotEmpty() && !magiskResult.startsWith("Error")) {
                return@withContext magiskResult.split("\n").mapNotNull { line ->
                    val parts = line.split("|")
                    if (parts.size >= 2) SuperuserApp(
                        packageName = parts.getOrElse(1) { "Unknown" },
                        uid = parts.getOrElse(0) { "" },
                        policy = "Granted"
                    ) else null
                }
            }
            // Try KernelSU
            val ksuResult = execRootCommand("ksud su list 2>/dev/null").trim()
            if (ksuResult.isNotEmpty() && !ksuResult.startsWith("Error")) {
                return@withContext ksuResult.split("\n")
                    .filter { it.isNotBlank() }
                    .map { SuperuserApp(packageName = it.trim(), uid = "", policy = "Granted") }
            }
            emptyList()
        } catch (e: Throwable) {
            Log.e(TAG, "Error getting superuser apps", e)
            emptyList()
        }
    }

    /**
     * Check if Zygisk is enabled
     */
    suspend fun isZygiskEnabled(): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = execRootCommand(
                "magisk --sqlite \"SELECT value FROM settings WHERE key='zygisk';\" 2>/dev/null"
            ).trim()
            result == "1" || File("/data/adb/modules/.zygisk_enabled").exists()
        } catch (e: Throwable) { false }
    }

    /**
     * Get process injection targets (Zygisk companion processes)
     */
    suspend fun getZygiskModules(): List<Module> = withContext(Dispatchers.IO) {
        try {
            val all = getInstalledModules()
            all.filter { it.isZygisk }
        } catch (e: Throwable) { emptyList() }
    }

    /**
     * Get LSPosed scope configuration for a module
     */
    suspend fun getLSPosedScope(moduleApk: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val result = execRootCommand(
                "cat /data/misc/lspd/modules/*.conf 2>/dev/null | grep -v '^#'"
            ).trim()
            result.split("\n").filter { it.isNotBlank() }
        } catch (e: Throwable) { emptyList() }
    }

    /**
     * Get system property values
     */
    suspend fun getSystemProperty(prop: String): String = withContext(Dispatchers.IO) {
        try { execRootCommand("getprop $prop").trim() }
        catch (e: Throwable) { "" }
    }

    private fun copyAssetToFile(assetName: String, destFile: File) {
        context.assets.open(assetName).use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
        destFile.setExecutable(true)
    }

    private fun tryInstallToSystem(hookLibFile: File): Boolean {
        // Skip /system installation on Android 14+ (dm-verity/AVB protection)
        if (Build.VERSION.SDK_INT >= 34) {
            Log.i(TAG, "Android 14+: Skipping /system installation (verified boot active)")
            return false
        }
        
        return try {
            val cmd = "mount -o rw,remount /system 2>/dev/null; " +
                    "cp '${hookLibFile.absolutePath}' '$HOOK_LIB_SYSTEM_PATH' && " +
                    "chmod 644 '$HOOK_LIB_SYSTEM_PATH' && " +
                    "chown root:root '$HOOK_LIB_SYSTEM_PATH' && echo SUCCESS"
            val result = execRootCommand(cmd)
            if (result.contains("SUCCESS")) {
                Log.i(TAG, "Successfully installed hook lib to /system")
                true
            } else {
                Log.w(TAG, "Failed to install to /system (normal on modern Android)")
                false
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Cannot install to /system: ${e.message}")
            false
        }
    }

    private fun setupBootPersistence() {
        try {
            val magiskServiceDir = "/data/adb/service.d"
            val bootScript = "#!/system/bin/sh\n# GodMode boot script\nsleep 5\n$DAEMON_DATA_PATH &\n"
            val scriptPath = "$magiskServiceDir/godmode.sh"
            val cmd = "mkdir -p '$magiskServiceDir' 2>/dev/null; " +
                    "printf '%s' '${bootScript.replace("'", "'\\''")}' > '$scriptPath'; " +
                    "chmod 755 '$scriptPath'"
            execRootCommand(cmd)
            execRootCommand("cp '$scriptPath' /data/adb/post-fs-data.d/godmode.sh 2>/dev/null; chmod 755 /data/adb/post-fs-data.d/godmode.sh 2>/dev/null")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to set up boot persistence", e)
        }
    }

    // ===== App Management =====

    /** Force stop an app */
    suspend fun forceStopApp(packageName: String): Boolean = withContext(Dispatchers.IO) {
        try { execRootCommand("am force-stop $packageName 2>/dev/null && echo OK").contains("OK") }
        catch (e: Throwable) { false }
    }

    /** Clear app cache only */
    suspend fun clearAppCache(packageName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            execRootCommand("rm -rf /data/data/$packageName/cache/* 2>/dev/null; " +
                    "rm -rf /data/data/$packageName/code_cache/* 2>/dev/null; " +
                    "rm -rf /sdcard/Android/data/$packageName/cache/* 2>/dev/null; echo DONE")
            true
        } catch (e: Throwable) { false }
    }

    /** Clear app data (full reset) */
    suspend fun clearAppData(packageName: String): Boolean = withContext(Dispatchers.IO) {
        try { execRootCommand("pm clear $packageName 2>/dev/null").contains("Success") }
        catch (e: Throwable) { false }
    }

    /** Grant a permission to an app */
    suspend fun grantPermission(packageName: String, permission: String): String = withContext(Dispatchers.IO) {
        try { execRootCommand("pm grant $packageName $permission 2>&1").trim() }
        catch (e: Throwable) { "Error: ${e.message}" }
    }

    /** Revoke a permission from an app */
    suspend fun revokePermission(packageName: String, permission: String): String = withContext(Dispatchers.IO) {
        try { execRootCommand("pm revoke $packageName $permission 2>&1").trim() }
        catch (e: Throwable) { "Error: ${e.message}" }
    }

    /** Get all permissions for an app and their states */
    suspend fun getAppPermissions(packageName: String): String = withContext(Dispatchers.IO) {
        try { execRootCommand("pm dump $packageName 2>/dev/null | grep -A1 'granted=true\\|granted=false' | grep 'android.permission' | head -50").trim() }
        catch (e: Throwable) { "" }
    }

    /** Get appops for an app (tracks what permissions were accessed) */
    suspend fun getAppOps(packageName: String): String = withContext(Dispatchers.IO) {
        try { execRootCommand("appops get $packageName 2>/dev/null").trim() }
        catch (e: Throwable) { "" }
    }

    /** Get all app appops - for permission logging */
    suspend fun getAllAppOps(): String = withContext(Dispatchers.IO) {
        try { execRootCommand("appops dump 2>/dev/null | head -500").trim() }
        catch (e: Throwable) { "" }
    }

    /** Enable/disable an app */
    suspend fun setAppEnabled(packageName: String, enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            val cmd = if (enabled) "pm enable $packageName 2>/dev/null" else "pm disable-user $packageName 2>/dev/null"
            execRootCommand(cmd).isNotEmpty()
        } catch (e: Throwable) { false }
    }

    /** Uninstall an app */
    suspend fun uninstallApp(packageName: String, keepData: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        try {
            val flags = if (keepData) "-k" else ""
            execRootCommand("pm uninstall $flags $packageName 2>/dev/null").contains("Success")
        } catch (e: Throwable) { false }
    }

    /** Get app size info */
    suspend fun getAppSizeInfo(packageName: String): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            val apkSize = execRootCommand("ls -lh /data/app/ 2>/dev/null | grep $packageName | awk '{print \$5}'").trim()
            val dataSize = execRootCommand("du -sh /data/data/$packageName 2>/dev/null | awk '{print \$1}'").trim()
            val cacheSize = execRootCommand("du -sh /data/data/$packageName/cache 2>/dev/null | awk '{print \$1}'").trim()
            mapOf("apk" to apkSize, "data" to dataSize, "cache" to cacheSize)
        } catch (e: Throwable) { emptyMap() }
    }

    /** Get all apps with their info (both user and system) */
    suspend fun getAllAppsWithInfo(): List<Map<String, String>> = withContext(Dispatchers.IO) {
        try {
            val result = execRootCommand("pm list packages -f 2>/dev/null").trim()
            result.split("\n")
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val pkg = line.substringAfterLast("=").trim()
                    if (pkg.isNotBlank()) mapOf("package" to pkg) else null
                }
        } catch (e: Throwable) { emptyList() }
    }

    // ===== WiFi & Network Management =====

    suspend fun getWifiNetworks(): String = withContext(Dispatchers.IO) {
        try {
            val result = execRootCommand("wpa_cli -i wlan0 scan_results 2>/dev/null || iw wlan0 scan 2>/dev/null | grep SSID | head -20").trim()
            result
        } catch (e: Throwable) { "" }
    }

    suspend fun setWifiEnabled(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            val cmd = if (enabled) "svc wifi enable" else "svc wifi disable"
            execRootCommand(cmd)
            true
        } catch (e: Throwable) { false }
    }

    suspend fun setMobileDataEnabled(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            val cmd = if (enabled) "svc data enable" else "svc data disable"
            execRootCommand(cmd)
            true
        } catch (e: Throwable) { false }
    }

    suspend fun setHotspotEnabled(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            val cmd = if (enabled) {
                "settings put global tether_entitlement_check_state 0 2>/dev/null; " +
                        "svc wifi hotspot enable 2>/dev/null || service call wifi 36 2>/dev/null"
            } else {
                "svc wifi hotspot disable 2>/dev/null"
            }
            execRootCommand(cmd)
            true
        } catch (e: Throwable) { false }
    }

    suspend fun setBluetoothEnabled(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            val cmd = if (enabled) "service call bluetooth_manager 6 2>/dev/null" else "service call bluetooth_manager 8 2>/dev/null"
            execRootCommand(cmd)
            true
        } catch (e: Throwable) { false }
    }

    suspend fun setAirplaneMode(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            val v = if (enabled) "1" else "0"
            execRootCommand("settings put global airplane_mode_on $v && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state ${enabled}")
            true
        } catch (e: Throwable) { false }
    }

    suspend fun getNetworkStats(): String = withContext(Dispatchers.IO) {
        try {
            val connections = execRootCommand("ss -tunap 2>/dev/null | head -30").trim()
            val wifi = execRootCommand("dumpsys wifi 2>/dev/null | grep -E 'SSID|signal|frequency|BSSIDStr|connected' | head -10").trim()
            "=== Connections ===\n$connections\n\n=== WiFi ===\n$wifi"
        } catch (e: Throwable) { "" }
    }

    suspend fun getSystemPropertyFull(): String = withContext(Dispatchers.IO) {
        try { execRootCommand("getprop 2>/dev/null | head -100").trim() }
        catch (e: Throwable) { "" }
    }

    /** Block internet for a specific app via iptables */
    suspend fun setAppFirewall(packageName: String, blocked: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            val uid = execRootCommand("cat /data/system/packages.xml 2>/dev/null | grep 'name=\"$packageName\"' | grep -oP 'userId=\"\\K[0-9]+'").trim()
            if (uid.isEmpty()) return@withContext false
            if (blocked) {
                execRootCommand("iptables -I OUTPUT -m owner --uid-owner $uid -j REJECT 2>/dev/null && " +
                        "iptables -I INPUT -m owner --uid-owner $uid -j REJECT 2>/dev/null")
            } else {
                execRootCommand("iptables -D OUTPUT -m owner --uid-owner $uid -j REJECT 2>/dev/null; " +
                        "iptables -D INPUT -m owner --uid-owner $uid -j REJECT 2>/dev/null")
            }
            true
        } catch (e: Throwable) { false }
    }

    /** Read Xposed access logs written by GoModeXposedModule */
    suspend fun getXposedAccessLogs(): List<Map<String, String>> = withContext(Dispatchers.IO) {
        try {
            val logFile = java.io.File("/data/data/com.godmode.app/files/xposed_logs/access.jsonl")
            if (!logFile.exists()) return@withContext emptyList()
            val gson = com.google.gson.Gson()
            logFile.readLines()
                .takeLast(500)
                .mapNotNull { line ->
                    try {
                        @Suppress("UNCHECKED_CAST")
                        gson.fromJson(line, Map::class.java) as Map<String, String>
                    } catch (_: Throwable) { null }
                }
                .reversed()
        } catch (e: Throwable) { emptyList() }
    }

    // ===== Power Controls =====

    suspend fun reboot(): Unit = withContext(Dispatchers.IO) {
        try { execRootCommand("reboot") } catch (e: Throwable) { }
    }

    suspend fun powerOff(): Unit = withContext(Dispatchers.IO) {
        try { execRootCommand("poweroff") } catch (e: Throwable) { }
    }

    suspend fun rebootRecovery(): Unit = withContext(Dispatchers.IO) {
        try { execRootCommand("reboot recovery") } catch (e: Throwable) { }
    }

    suspend fun rebootBootloader(): Unit = withContext(Dispatchers.IO) {
        try { execRootCommand("reboot bootloader") } catch (e: Throwable) { }
    }

    suspend fun rebootEdl(): Unit = withContext(Dispatchers.IO) {
        try { execRootCommand("reboot edl 2>/dev/null || reboot download 2>/dev/null") }
        catch (e: Throwable) { }
    }

    suspend fun rebootSafeMode(): Unit = withContext(Dispatchers.IO) {
        try { execRootCommand("setprop persist.sys.safemode 1 && reboot") }
        catch (e: Throwable) { }
    }
}
