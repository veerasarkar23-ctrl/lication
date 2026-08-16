package com.example.myapplication.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.myapplication.MainActivity

class SecretCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.provider.Telephony.SECRET_CODE") {
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(launchIntent)
        }
    }
}