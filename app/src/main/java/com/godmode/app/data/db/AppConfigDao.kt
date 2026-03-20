package com.godmode.app.data.db

import androidx.lifecycle.LiveData
import androidx.room.*
import com.godmode.app.data.model.AppConfig
import kotlinx.coroutines.flow.Flow

@Dao
interface AppConfigDao {

    @Query("SELECT * FROM app_configs ORDER BY appName ASC")
    fun getAllConfigs(): Flow<List<AppConfig>>

    @Query("SELECT * FROM app_configs WHERE isActive = 1 ORDER BY appName ASC")
    fun getActiveConfigs(): Flow<List<AppConfig>>

    @Query("SELECT * FROM app_configs WHERE packageName = :packageName")
    suspend fun getConfigForPackage(packageName: String): AppConfig?

    @Query("SELECT * FROM app_configs WHERE packageName = :packageName")
    fun observeConfigForPackage(packageName: String): Flow<AppConfig?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(config: AppConfig)

    @Update
    suspend fun update(config: AppConfig)

    @Delete
    suspend fun delete(config: AppConfig)

    @Query("DELETE FROM app_configs WHERE packageName = :packageName")
    suspend fun deleteByPackage(packageName: String)

    @Query("SELECT COUNT(*) FROM app_configs WHERE isActive = 1")
    fun getActiveCount(): Flow<Int>

    @Query("UPDATE app_configs SET isActive = :active WHERE packageName = :packageName")
    suspend fun setActive(packageName: String, active: Boolean)

    @Query("UPDATE app_configs SET updatedAt = :timestamp WHERE packageName = :packageName")
    suspend fun updateTimestamp(packageName: String, timestamp: Long)
}
