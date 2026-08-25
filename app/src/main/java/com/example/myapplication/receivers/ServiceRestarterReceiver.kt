package com.example.myapplication.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.myapplication.services.PersistentService

class ServiceRestarterReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            val serviceIntent = Intent(context, PersistentService::class.java)
            context.startForegroundService(serviceIntent)
        } catch (e: Exception) {
            // Log or ignore on Android 12+ FGS restriction
        }
    }
}