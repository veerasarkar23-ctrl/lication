package com.example.myapplication.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.myapplication.services.PersistentService

class ServiceRestarterReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val serviceIntent = Intent(context, PersistentService::class.java)
        context.startForegroundService(serviceIntent)
    }
}