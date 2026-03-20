package com.godmode.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.godmode.app.GodModeApp
import com.godmode.app.data.model.AccessLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives broadcast messages from the native daemon relayed via the daemon service.
 * Parses access log entries and inserts them into the Room database.
 */
class DaemonMessageReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "GodMode_MsgReceiver"
        const val ACTION_LOG_ENTRY = "com.godmode.app.LOG_ENTRY"
        const val EXTRA_LOG_JSON = "log_json"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_LOG_ENTRY) return

        val logJson = intent.getStringExtra(EXTRA_LOG_JSON) ?: return
        Log.d(TAG, "Received log entry: $logJson")

        val app = context.applicationContext as GodModeApp
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val log = parseLogEntry(logJson)
                if (log != null) {
                    app.database.accessLogDao().insert(log)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to store log entry", e)
            }
        }
    }

    /**
     * Parse a pipe-delimited log entry from the daemon.
     * Format: packageName|uid|timestamp|propertyType|originalValue|spoofedValue|wasSpoofed
     */
    private fun parseLogEntry(entry: String): AccessLog? {
        return try {
            val parts = entry.split("|")
            if (parts.size < 7) return null

            AccessLog(
                packageName = parts[0],
                uid = parts[1].toIntOrNull() ?: -1,
                timestamp = parts[2].toLongOrNull() ?: System.currentTimeMillis(),
                propertyType = parts[3],
                propertyCategory = AccessLog.categoryForType(parts[3]),
                originalValue = parts[4],
                spoofedValue = parts[5],
                wasSpoofed = parts[6] == "1",
                callerMethod = if (parts.size > 7) parts[7] else "",
                networkDestination = if (parts.size > 8) parts[8] else "",
                networkPort = if (parts.size > 9) parts[9].toIntOrNull() ?: 0 else 0,
                networkProtocol = if (parts.size > 10) parts[10] else "",
                permissionUsed = if (parts.size > 11) parts[11] else ""
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse log entry: $entry", e)
            null
        }
    }
}
