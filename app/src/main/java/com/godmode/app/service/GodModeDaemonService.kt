package com.godmode.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.godmode.app.GodModeApp
import com.godmode.app.R
import com.godmode.app.daemon.RootManager
import com.godmode.app.data.db.GodModeDatabase
import com.godmode.app.data.model.AccessLog
import com.godmode.app.ui.MainActivity
import kotlinx.coroutines.*
import java.net.ServerSocket
import java.net.Socket

/**
 * Foreground service that maintains connection to the native daemon
 * and processes incoming access log events.
 */
class GodModeDaemonService : Service() {

    companion object {
        private const val TAG = "GodMode_Service"
        const val ACTION_START = "com.godmode.app.START_DAEMON"
        const val ACTION_STOP = "com.godmode.app.STOP_DAEMON"

        var isRunning = false
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var logListenerJob: Job? = null
    private lateinit var rootManager: RootManager
    private lateinit var database: GodModeDatabase

    override fun onCreate() {
        super.onCreate()
        rootManager = RootManager.getInstance(this)
        database = GodModeDatabase.getDatabase(this)
        Log.i(TAG, "GodMode service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(GodModeApp.NOTIFICATION_ID_DAEMON, createNotification())
                isRunning = true
                startLogListener()
                Log.i(TAG, "GodMode service started")
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        logListenerJob?.cancel()
        serviceScope.cancel()
        Log.i(TAG, "GodMode service destroyed")
    }

    private fun startLogListener() {
        logListenerJob = serviceScope.launch {
            // Poll daemon for new logs every 2 seconds
            while (isActive) {
                try {
                    if (rootManager.nativeIsDaemonRunning()) {
                        val rawLogs = rootManager.nativeGetLogs("*")
                        if (rawLogs.isNotEmpty()) {
                            processRawLogs(rawLogs)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error polling logs", e)
                }
                delay(2000)
            }
        }
    }

    private suspend fun processRawLogs(rawLogs: String) {
        val lines = rawLogs.trim().split("\n")
        val logsToInsert = mutableListOf<AccessLog>()

        for (line in lines) {
            if (line.isBlank()) continue
            val log = parseLogLine(line) ?: continue
            logsToInsert.add(log)
        }

        if (logsToInsert.isNotEmpty()) {
            database.accessLogDao().insertAll(logsToInsert)
            Log.d(TAG, "Inserted ${logsToInsert.size} log entries")
        }
    }

    private fun parseLogLine(line: String): AccessLog? {
        // Format: LOG_ENTRY|package|uid|timestamp|property|original|spoofed|was_spoofed
        // or: package|uid|timestamp|property|original|spoofed|was_spoofed
        val cleanLine = if (line.startsWith("LOG_ENTRY|")) line.substring(10) else line
        val parts = cleanLine.split("|")
        if (parts.size < 4) return null

        return try {
            AccessLog(
                packageName = parts[0],
                uid = parts.getOrNull(1)?.toIntOrNull() ?: -1,
                timestamp = parts.getOrNull(2)?.toLongOrNull() ?: System.currentTimeMillis(),
                propertyType = parts.getOrNull(3) ?: "UNKNOWN",
                propertyCategory = AccessLog.categoryForType(parts.getOrNull(3) ?: ""),
                originalValue = parts.getOrNull(4) ?: "",
                spoofedValue = parts.getOrNull(5) ?: "",
                wasSpoofed = parts.getOrNull(6) == "1"
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, GodModeDaemonService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, GodModeApp.CHANNEL_ID_DAEMON)
            .setContentTitle("GoMode Active")
            .setContentText("Monitoring all app data access")
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(mainPendingIntent)
            .addAction(R.drawable.ic_stop, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
