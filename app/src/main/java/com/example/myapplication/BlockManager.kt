package com.example.myapplication

import android.content.Context
import android.content.SharedPreferences

object BlockManager {
    private const val PREFS_NAME = "block_prefs"
    private const val KEY_BLOCKED_APPS = "blocked_apps"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getBlockedApps(context: Context): Set<String> {
        return getPrefs(context).getStringSet(KEY_BLOCKED_APPS, emptySet()) ?: emptySet()
    }

    fun blockApp(context: Context, packageName: String) {
        val apps = getBlockedApps(context).toMutableSet()
        apps.add(packageName)
        getPrefs(context).edit().putStringSet(KEY_BLOCKED_APPS, apps).apply()
    }

    fun unblockApp(context: Context, packageName: String) {
        val apps = getBlockedApps(context).toMutableSet()
        apps.remove(packageName)
        getPrefs(context).edit().putStringSet(KEY_BLOCKED_APPS, apps).apply()
    }

    fun isAppBlocked(context: Context, packageName: String): Boolean {
        return getBlockedApps(context).contains(packageName)
    }

    fun getPackageFromName(name: String): String? {
        val appMap = mapOf(
            "whatsapp" to "com.whatsapp",
            "instagram" to "com.instagram.android",
            "facebook" to "com.facebook.katana",
            "messenger" to "com.facebook.orca",
            "youtube" to "com.google.android.youtube",
            "chrome" to "com.android.chrome",
            "gmail" to "com.google.android.gm",
            "playstore" to "com.android.vending",
            "snapchat" to "com.snapchat.android",
            "telegram" to "org.telegram.messenger",
            "tiktok" to "com.zhiliaoapp.musically",
            "chatgpt" to "com.openai.chatgpt",
            "gemini" to "com.google.android.apps.bard"
        )
        return appMap[name.lowercase()] ?: if (name.contains(".")) name else null
    }
}