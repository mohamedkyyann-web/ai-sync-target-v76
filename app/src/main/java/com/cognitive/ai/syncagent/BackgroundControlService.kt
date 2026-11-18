package com.cognitive.ai.syncagent

import android.app.Service
import android.content.Intent
import android.os.IBinder

class BackgroundControlService : Service() {
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val token = intent?.getStringExtra("token") ?: "agent_auto_boot"
        return START_STICKY
    }
}
