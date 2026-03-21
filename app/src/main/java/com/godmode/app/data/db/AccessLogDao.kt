package com.godmode.app.data.db

import androidx.room.*
import com.godmode.app.data.model.AccessLog
import kotlinx.coroutines.flow.Flow

@Dao
interface AccessLogDao {

    @Query("SELECT * FROM access_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentLogs(limit: Int = 500): Flow<List<AccessLog>>

    @Query("SELECT * FROM access_logs WHERE packageName = :packageName ORDER BY timestamp DESC LIMIT :limit")
    fun getLogsForPackage(packageName: String, limit: Int = 200): Flow<List<AccessLog>>
    
    @Query("SELECT * FROM access_logs WHERE packageName = :packageName ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getLogsForPackageSync(packageName: String, limit: Int = 200): List<AccessLog>
    
    @Query("SELECT * FROM access_logs ORDER BY timestamp DESC")
    suspend fun getAllLogs(): List<AccessLog>

    @Query("SELECT * FROM access_logs WHERE propertyType = :type ORDER BY timestamp DESC LIMIT :limit")
    fun getLogsByType(type: String, limit: Int = 200): Flow<List<AccessLog>>

    @Query("SELECT * FROM access_logs WHERE timestamp >= :since ORDER BY timestamp DESC")
    fun getLogsSince(since: Long): Flow<List<AccessLog>>

    @Query("""
        SELECT * FROM access_logs 
        WHERE (:packageName = '' OR packageName = :packageName)
        AND (:propertyType = '' OR propertyType = :propertyType)
        AND (:onlySpoofed = 0 OR wasSpoofed = 1)
        AND timestamp >= :since
        ORDER BY timestamp DESC 
        LIMIT :limit
    """)
    fun getFilteredLogs(
        packageName: String = "",
        propertyType: String = "",
        onlySpoofed: Boolean = false,
        since: Long = 0L,
        limit: Int = 500
    ): Flow<List<AccessLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: AccessLog): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<AccessLog>)

    @Delete
    suspend fun delete(log: AccessLog)

    @Query("DELETE FROM access_logs WHERE packageName = :packageName")
    suspend fun deleteForPackage(packageName: String)

    @Query("DELETE FROM access_logs WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM access_logs")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM access_logs")
    fun getTotalCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM access_logs WHERE packageName = :packageName")
    fun getCountForPackage(packageName: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM access_logs WHERE wasSpoofed = 1")
    fun getSpoofedCount(): Flow<Int>

    @Query("""
        SELECT packageName, COUNT(*) as count 
        FROM access_logs 
        GROUP BY packageName 
        ORDER BY count DESC 
        LIMIT 10
    """)
    fun getTopAccessingApps(): Flow<List<PackageAccessCount>>

    @Query("""
        SELECT propertyType, COUNT(*) as count 
        FROM access_logs 
        GROUP BY propertyType 
        ORDER BY count DESC
    """)
    fun getAccessCountByType(): Flow<List<PropertyAccessCount>>
}

data class PackageAccessCount(
    val packageName: String,
    val count: Int
)

data class PropertyAccessCount(
    val propertyType: String,
    val count: Int
)
