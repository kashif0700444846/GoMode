package com.godmode.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "GodMode_Boot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            Log.i(TAG, "Boot completed, starting GodMode service")
            val serviceIntent = Intent(context, GodModeDaemonService::class.java).apply {
                action = GodModeDaemonService.ACTION_START
            }
            context.startForegroundService(serviceIntent)
        }
    }
}
