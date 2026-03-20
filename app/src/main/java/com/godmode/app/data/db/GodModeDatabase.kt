package com.godmode.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.godmode.app.data.model.AccessLog
import com.godmode.app.data.model.AppConfig

@Database(
    entities = [AppConfig::class, AccessLog::class],
    version = 1,
    exportSchema = false
)
abstract class GodModeDatabase : RoomDatabase() {

    abstract fun appConfigDao(): AppConfigDao
    abstract fun accessLogDao(): AccessLogDao

    companion object {
        @Volatile
        private var INSTANCE: GodModeDatabase? = null

        fun getDatabase(context: Context): GodModeDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    GodModeDatabase::class.java,
                    "godmode_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
