package com.godmode.app.data.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import com.godmode.app.daemon.RootManager
import com.godmode.app.data.db.AccessLogDao
import com.godmode.app.data.db.AppConfigDao
import com.godmode.app.data.db.PackageAccessCount
import com.godmode.app.data.db.PropertyAccessCount
import com.godmode.app.data.model.AccessLog
import com.godmode.app.data.model.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Central repository for all GodMode data operations.
 * Coordinates between Room database, native daemon, and PackageManager.
 */
class GodModeRepository(
    private val context: Context,
    private val appConfigDao: AppConfigDao,
    private val accessLogDao: AccessLogDao,
    private val rootManager: RootManager
) {

    companion object {
        private const val TAG = "GodMode_Repo"
    }

    // ========== App Config Operations ==========

    fun getAllConfigs(): Flow<List<AppConfig>> = appConfigDao.getAllConfigs()

    fun getActiveConfigs(): Flow<List<AppConfig>> = appConfigDao.getActiveConfigs()

    suspend fun getConfigForPackage(packageName: String): AppConfig? =
        appConfigDao.getConfigForPackage(packageName)

    fun observeConfigForPackage(packageName: String): Flow<AppConfig?> =
        appConfigDao.observeConfigForPackage(packageName)

    suspend fun saveConfig(config: AppConfig) {
        config.updatedAt = System.currentTimeMillis()
        appConfigDao.insertOrUpdate(config)
        // Push to daemon
        if (rootManager.nativeIsDaemonRunning()) {
            rootManager.sendAppConfig(config)
        }
    }

    suspend fun deleteConfig(packageName: String) {
        appConfigDao.deleteByPackage(packageName)
        // Disable in daemon
        val disabledConfig = AppConfig(packageName = packageName, isActive = false)
        if (rootManager.nativeIsDaemonRunning()) {
            rootManager.sendAppConfig(disabledConfig)
        }
    }

    suspend fun setConfigActive(packageName: String, active: Boolean) {
        appConfigDao.setActive(packageName, active)
        val config = appConfigDao.getConfigForPackage(packageName)
        if (config != null && rootManager.nativeIsDaemonRunning()) {
            rootManager.sendAppConfig(config.copy(isActive = active))
        }
    }

    // ========== Access Log Operations ==========

    fun getRecentLogs(limit: Int = 500): Flow<List<AccessLog>> =
        accessLogDao.getRecentLogs(limit)

    fun getLogsForPackage(packageName: String): Flow<List<AccessLog>> =
        accessLogDao.getLogsForPackage(packageName)

    fun getFilteredLogs(
        packageName: String = "",
        propertyType: String = "",
        onlySpoofed: Boolean = false,
        since: Long = 0L,
        limit: Int = 500
    ): Flow<List<AccessLog>> = accessLogDao.getFilteredLogs(
        packageName, propertyType, onlySpoofed, since, limit
    )

    suspend fun insertLog(log: AccessLog) = accessLogDao.insert(log)

    suspend fun clearLogsForPackage(packageName: String) {
        accessLogDao.deleteForPackage(packageName)
        if (rootManager.nativeIsDaemonRunning()) {
            rootManager.nativeSendConfig("CLEAR_LOGS|$packageName")
        }
    }

    suspend fun clearAllLogs() {
        accessLogDao.deleteAll()
        if (rootManager.nativeIsDaemonRunning()) {
            rootManager.nativeSendConfig("CLEAR_LOGS|*")
        }
    }

    suspend fun clearOldLogs(daysToKeep: Int = 30) {
        val cutoff = System.currentTimeMillis() - (daysToKeep * 24 * 60 * 60 * 1000L)
        accessLogDao.deleteOlderThan(cutoff)
    }

    fun getTotalLogCount(): Flow<Int> = accessLogDao.getTotalCount()

    fun getSpoofedCount(): Flow<Int> = accessLogDao.getSpoofedCount()

    fun getTopAccessingApps(): Flow<List<PackageAccessCount>> =
        accessLogDao.getTopAccessingApps()

    fun getAccessCountByType(): Flow<List<PropertyAccessCount>> =
        accessLogDao.getAccessCountByType()

    // ========== Installed Apps ==========

    data class InstalledApp(
        val packageName: String,
        val appName: String,
        val isSystemApp: Boolean,
        val hasConfig: Boolean = false,
        val isConfigActive: Boolean = false,
        val accessCount: Int = 0
    )

    suspend fun getInstalledApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val configs = appConfigDao.getAllConfigs().let { flow ->
            // Get snapshot
            val list = mutableListOf<AppConfig>()
            // We need a snapshot - use a simple approach
            list
        }

        packages
            .filter { it.packageName != context.packageName }
            .map { appInfo ->
                val appName = try {
                    pm.getApplicationLabel(appInfo).toString()
                } catch (e: Exception) {
                    appInfo.packageName
                }
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                InstalledApp(
                    packageName = appInfo.packageName,
                    appName = appName,
                    isSystemApp = isSystem
                )
            }
            .sortedWith(compareBy({ it.isSystemApp }, { it.appName }))
    }

    // ========== Device Info ==========

    data class DeviceInfo(
        val realImei: String,
        val realAndroidId: String,
        val realSerial: String,
        val realIp: String,
        val realMac: String
    )

    suspend fun getRealDeviceInfo(): DeviceInfo = withContext(Dispatchers.IO) {
        DeviceInfo(
            realImei = rootManager.getRealImei(),
            realAndroidId = rootManager.getRealAndroidId(),
            realSerial = rootManager.getRealSerial(),
            realIp = getLocalIpAddress(),
            realMac = getMacAddress()
        )
    }

    private fun getLocalIpAddress(): String {
        return try {
            val result = rootManager.nativeExecRoot("ip route get 8.8.8.8 | awk '{print $7}' | head -1")
            result.trim()
        } catch (e: Exception) {
            ""
        }
    }

    private fun getMacAddress(): String {
        return try {
            val result = rootManager.nativeExecRoot("cat /sys/class/net/wlan0/address 2>/dev/null || cat /sys/class/net/eth0/address 2>/dev/null")
            result.trim()
        } catch (e: Exception) {
            ""
        }
    }
}
