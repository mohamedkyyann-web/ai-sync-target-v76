package com.cognitive.ai.syncagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootAutoStartReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val isBootEvent = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.huawei.intent.action.BOOT_COMPLETED" ||
            action == "android.intent.action.REBOOT"

        if (!isBootEvent) return

        try {
            val targetContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !context.isDeviceProtectedStorage) {
                context.createDeviceProtectedStorageContext()
            } else {
                context
            }

            val serviceIntent = Intent(targetContext, BackgroundControlService::class.java).apply {
                putExtra("started_from_boot", true)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                targetContext.startForegroundService(serviceIntent)
            } else {
                targetContext.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BackgroundControlService on boot: ${e.message}")
        }
    }
}
