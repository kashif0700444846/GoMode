package com.godmode.app.daemon

import android.content.Context
import android.util.Log
import com.godmode.app.data.model.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

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

        fun getInstance(context: Context): RootManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: RootManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        // Load native library
        init {
            try {
                System.loadLibrary("godmode_jni")
                Log.i(TAG, "Native JNI library loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library: ${e.message}")
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

    /**
     * Check root status and detect installed root tools.
     */
    suspend fun getRootStatus(): RootStatus = withContext(Dispatchers.IO) {
        try {
            val isRooted = nativeIsRooted()
            val hasMagisk = nativeIsMagiskInstalled()
            val hasKernelSU = nativeIsKernelSUInstalled()
            val hasLSPosed = nativeIsLSPosedInstalled()
            val isDaemonRunning = nativeIsDaemonRunning()

            var daemonVersion = ""
            if (isDaemonRunning) {
                val status = nativeGetDaemonStatus()
                daemonVersion = status.substringAfter("version=", "").substringBefore("|")
            }

            RootStatus(isRooted, hasMagisk, hasKernelSU, hasLSPosed, isDaemonRunning, daemonVersion)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting root status", e)
            RootStatus(false, false, false, false, false)
        }
    }

    /**
     * Request root permission by running a test su command.
     * Returns true if root was granted.
     */
    suspend fun requestRoot(): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = nativeExecRoot("id")
            val granted = result.contains("uid=0")
            Log.i(TAG, "Root request result: $result, granted=$granted")
            granted
        } catch (e: Exception) {
            Log.e(TAG, "Root request failed", e)
            false
        }
    }

    /**
     * Install the GodMode daemon to the device.
     * Copies binaries from app assets to /data/local/tmp/ and optionally to /system.
     */
    suspend fun installDaemon(): InstallResult = withContext(Dispatchers.IO) {
        try {
            // Copy daemon binary from assets
            val daemonFile = File(context.filesDir, DAEMON_NAME)
            copyAssetToFile(DAEMON_NAME, daemonFile)

            // Copy hook library from native libs
            val hookLibFile = File(context.filesDir, HOOK_LIB_NAME)
            val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
            val sourceLib = File(nativeLibDir, HOOK_LIB_NAME)

            if (!sourceLib.exists()) {
                return@withContext InstallResult(false, "Hook library not found in native libs")
            }

            sourceLib.copyTo(hookLibFile, overwrite = true)

            // Install to /data/local/tmp (always works with root)
            var cmd = "cp '${daemonFile.absolutePath}' '$DAEMON_DATA_PATH' && " +
                      "chmod 755 '$DAEMON_DATA_PATH'"
            var result = nativeExecRoot(cmd)
            Log.i(TAG, "Daemon install result: $result")

            // Copy hook lib to /data/local/tmp
            cmd = "cp '${hookLibFile.absolutePath}' '$HOOK_LIB_DATA_PATH' && " +
                  "chmod 644 '$HOOK_LIB_DATA_PATH'"
            result = nativeExecRoot(cmd)
            Log.i(TAG, "Hook lib install result: $result")

            // Try to install to /system/lib64 for better compatibility
            val systemInstall = tryInstallToSystem(hookLibFile)
            Log.i(TAG, "System install: $systemInstall")

            // Set up boot persistence
            setupBootPersistence()

            InstallResult(true, "Daemon installed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Daemon installation failed", e)
            InstallResult(false, "Installation failed: ${e.message}")
        }
    }

    /**
     * Start the daemon process.
     */
    suspend fun startDaemon(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Check if already running
            if (nativeIsDaemonRunning()) {
                Log.i(TAG, "Daemon already running")
                return@withContext true
            }

            // Start daemon in background
            val daemonPath = if (File(DAEMON_DATA_PATH).exists()) DAEMON_DATA_PATH
                            else "${context.filesDir}/$DAEMON_NAME"

            val result = nativeExecRoot("$daemonPath &")
            Log.i(TAG, "Daemon start result: $result")

            // Wait a moment for daemon to initialize
            Thread.sleep(1000)

            val running = nativeIsDaemonRunning()
            Log.i(TAG, "Daemon running after start: $running")
            running
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start daemon", e)
            false
        }
    }

    /**
     * Stop the daemon process.
     */
    suspend fun stopDaemon(): Boolean = withContext(Dispatchers.IO) {
        try {
            nativeExecRoot("pkill -f godmoded")
            Thread.sleep(500)
            !nativeIsDaemonRunning()
        } catch (e: Exception) {
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
            Log.d(TAG, "Sending config: $configStr")
            nativeSendConfig(configStr)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send config", e)
            false
        }
    }

    /**
     * Notify daemon that config for a specific package has been updated.
     */
    suspend fun notifyConfigUpdate(packageName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (nativeIsDaemonRunning()) {
                nativeSendConfig("RELOAD_CONFIG|$packageName")
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to notify config update", e)
            false
        }
    }

    /**
     * Manually inject the hook library into a running process.
     */
    suspend fun injectIntoProcess(pid: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = nativeExecRoot("kill -0 $pid 2>/dev/null && echo OK")
            if (!result.contains("OK")) return@withContext false

            // Ask daemon to inject
            nativeSendConfig("INJECT|$pid")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject into process $pid", e)
            false
        }
    }

    /**
     * Get the real device IMEI (before spoofing).
     */
    suspend fun getRealImei(): String = withContext(Dispatchers.IO) {
        try {
            val result = nativeExecRoot("service call iphonesubinfo 4 | cut -c 52-66 | tr -d '.[:space:]'")
            result.trim()
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Get the real Android ID.
     */
    suspend fun getRealAndroidId(): String = withContext(Dispatchers.IO) {
        try {
            nativeExecRoot("settings get secure android_id").trim()
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Get the real device serial.
     */
    suspend fun getRealSerial(): String = withContext(Dispatchers.IO) {
        try {
            nativeExecRoot("getprop ro.serialno").trim()
        } catch (e: Exception) {
            ""
        }
    }

    private fun copyAssetToFile(assetName: String, destFile: File) {
        try {
            context.assets.open(assetName).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            destFile.setExecutable(true)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to copy asset $assetName", e)
            throw e
        }
    }

    private fun tryInstallToSystem(hookLibFile: File): Boolean {
        return try {
            val cmd = buildString {
                append("mount -o rw,remount /system 2>/dev/null; ")
                append("cp '${hookLibFile.absolutePath}' '$HOOK_LIB_SYSTEM_PATH' && ")
                append("chmod 644 '$HOOK_LIB_SYSTEM_PATH' && ")
                append("chown root:root '$HOOK_LIB_SYSTEM_PATH' && ")
                append("echo SUCCESS")
            }
            val result = nativeExecRoot(cmd)
            result.contains("SUCCESS")
        } catch (e: Exception) {
            false
        }
    }

    private fun setupBootPersistence() {
        try {
            val magiskServiceDir = "/data/adb/service.d"
            val bootScript = """
                #!/system/bin/sh
                # GodMode boot script
                sleep 5
                $DAEMON_DATA_PATH &
            """.trimIndent()

            val scriptPath = "$magiskServiceDir/godmode.sh"
            val cmd = buildString {
                append("mkdir -p '$magiskServiceDir' 2>/dev/null; ")
                append("printf '%s' '${bootScript.replace("'", "'\\''")}' > '$scriptPath'; ")
                append("chmod 755 '$scriptPath'")
            }
            nativeExecRoot(cmd)

            val initDScript = "/data/adb/post-fs-data.d/godmode.sh"
            nativeExecRoot("cp '$scriptPath' '$initDScript' 2>/dev/null; chmod 755 '$initDScript' 2>/dev/null")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to set up boot persistence", e)
        }
    }

    data class InstallResult(
        val success: Boolean,
        val message: String
    )
}
