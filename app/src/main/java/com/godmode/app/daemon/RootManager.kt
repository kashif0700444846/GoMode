package com.godmode.app.daemon

import android.content.Context
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
     * Execute a root command with fallback to pure Kotlin implementation.
     * This is the safe wrapper to use throughout the app.
     */
    fun execRootCommand(command: String): String {
        if (nativeLibLoaded) {
            try {
                return nativeExecRoot(command)
            } catch (e: Throwable) {
                Log.w(TAG, "Native exec failed, using Kotlin fallback: ${e.message}")
            }
        }
        return execRootCommandKotlin(command)
    }

    private fun execRootCommandKotlin(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            process.waitFor(15, TimeUnit.SECONDS)
            (output + error).trim()
        } catch (e: Throwable) {
            Log.e(TAG, "Kotlin root exec failed: ${e.message}")
            "Error: ${e.message}"
        }
    }

    /**
     * Check root status and detect installed root tools.
     */
    suspend fun getRootStatus(): RootStatus = withContext(Dispatchers.IO) {
        try {
            val isRooted = if (nativeLibLoaded) {
                try { nativeIsRooted() } catch (e: Throwable) { checkRootedKotlin() }
            } else checkRootedKotlin()

            val hasMagisk = if (nativeLibLoaded) {
                try { nativeIsMagiskInstalled() } catch (e: Throwable) { checkMagiskKotlin() }
            } else checkMagiskKotlin()

            val hasKernelSU = if (nativeLibLoaded) {
                try { nativeIsKernelSUInstalled() } catch (e: Throwable) { checkKernelSUKotlin() }
            } else checkKernelSUKotlin()

            val hasLSPosed = if (nativeLibLoaded) {
                try { nativeIsLSPosedInstalled() } catch (e: Throwable) { checkLSPosedKotlin() }
            } else checkLSPosedKotlin()

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
        val suPaths = listOf(
            "/sbin/su", "/system/bin/su", "/system/xbin/su",
            "/data/local/xbin/su", "/data/local/bin/su",
            "/data/local/su", "/su/bin/su", "/magisk/.core/bin/su",
            "/data/adb/magisk", "/sbin/.magisk", "/data/adb/ksu"
        )
        return suPaths.any { File(it).exists() }
    }

    private fun checkMagiskKotlin() = File("/data/adb/magisk").exists() ||
            File("/sbin/.magisk").exists() || File("/data/adb/magisk.db").exists()

    private fun checkKernelSUKotlin() = File("/data/adb/ksu").exists() ||
            File("/data/adb/ksud").exists()

    private fun checkLSPosedKotlin() = File("/data/adb/modules/lsposed").exists() ||
            File("/data/adb/modules/zygisk_lsposed").exists() ||
            File("/data/misc/lspd").exists()

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
     * Get the real device IMEI (before spoofing).
     */
    suspend fun getRealImei(): String = withContext(Dispatchers.IO) {
        try {
            var result = execRootCommand(
                "dumpsys iphonesubinfo 2>/dev/null | grep -i Device | tail -1 | cut -d= -f2 | tr -d . | tr -d \" \""
            ).trim()
            if (result.matches(Regex("[0-9]{14,15}"))) return@withContext result

            for (prop in listOf("ril.imei1", "ril.imei", "gsm.imei", "persist.radio.device.imei")) {
                result = execRootCommand("getprop $prop 2>/dev/null").trim()
                if (result.matches(Regex("[0-9]{14,15}"))) return@withContext result
            }
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
        return try {
            val cmd = "mount -o rw,remount /system 2>/dev/null; " +
                    "cp '${hookLibFile.absolutePath}' '$HOOK_LIB_SYSTEM_PATH' && " +
                    "chmod 644 '$HOOK_LIB_SYSTEM_PATH' && " +
                    "chown root:root '$HOOK_LIB_SYSTEM_PATH' && echo SUCCESS"
            execRootCommand(cmd).contains("SUCCESS")
        } catch (e: Throwable) { false }
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
