companion object {
    private const val TAG = "SyncAgent"
    private const val CHANNEL_ID = "sync_channel"
}

// ضع رمز البوت الجديد هنا بعد إلغاء الرمز القديم
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

    startForeground(1001, createNotification())

    if (!isRunning) {
        isRunning = true
        startPolling()
    }

    return START_STICKY
}

private fun createNotification(): Notification {

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Telegram Sync",
            NotificationManager.IMPORTANCE_LOW
        )

        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ChatGPT AI Sync")
            .setContentText("الخدمة تعمل")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
    }

    @Suppress("DEPRECATION")
    return Notification.Builder(this)
        .setContentTitle("ChatGPT AI Sync")
        .setContentText("الخدمة تعمل")
        .setSmallIcon(android.R.drawable.ic_menu_info_details)
        .build()
}

private fun startPolling() {

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

                connection.connectTimeout = 10000
                connection.readTimeout = 30000

                val response = connection.inputStream
                    .bufferedReader()
                    .use { it.readText() }

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

private fun handleCommand(chatId: String, text: String) {

    when (text.trim()) {

        "/start" -> {

            sendKeyboard(
                chatId,
                "🫡 أهلاً بك في لوحة التحكم.\nاختر أحد الخيارات:"
            )
        }

        "/help" -> {

            sendMessage(
                chatId,
                "الأوامر المتاحة:\n/start - عرض لوحة التحكم"
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

private fun sendKeyboard(chatId: String, text: String) {

    thread {

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
                    JSONObject().put("inline_keyboard", keyboard)
                )
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
                it.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            val result = connection.inputStream
                .bufferedReader()
                .use { it.readText() }

            Log.d(TAG, result)

        } catch (e: Exception) {

            Log.e(TAG, "Keyboard error", e)

        } finally {
            connection?.disconnect()
        }
    }
}

private fun sendMessage(chatId: String, text: String) {

    thread {

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
                it.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            val result = connection.inputStream
                .bufferedReader()
                .use { it.readText() }

            Log.d(TAG, result)

        } catch (e: Exception) {

            Log.e(TAG, "Message error", e)

        } finally {
            connection?.disconnect()
        }
    }
}

override fun onDestroy() {
    isRunning = false
    super.onDestroy()
}
