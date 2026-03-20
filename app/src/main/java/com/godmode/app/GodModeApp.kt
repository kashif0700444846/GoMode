package com.godmode.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.godmode.app.data.db.GodModeDatabase
import com.godmode.app.daemon.RootManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class GodModeApp : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database by lazy { GodModeDatabase.getDatabase(this) }
    val rootManager by lazy { RootManager.getInstance(this) }

    companion object {
        const val CHANNEL_ID_DAEMON = "godmode_daemon"
        const val CHANNEL_ID_ALERTS = "godmode_alerts"
        const val NOTIFICATION_ID_DAEMON = 1001

        lateinit var instance: GodModeApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
        initializeApp()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val daemonChannel = NotificationChannel(
                CHANNEL_ID_DAEMON,
                "GoMode Daemon",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "GoMode root daemon monitoring service"
                setShowBadge(false)
            }

            val alertsChannel = NotificationChannel(
                CHANNEL_ID_ALERTS,
                "Privacy Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Alerts when apps access sensitive data"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannels(listOf(daemonChannel, alertsChannel))
        }
    }

    private fun initializeApp() {
        applicationScope.launch {
            // Pre-initialize database
            database.appConfigDao()
            database.accessLogDao()
        }
    }
}
