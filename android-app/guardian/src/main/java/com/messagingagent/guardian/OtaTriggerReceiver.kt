package com.messagingagent.guardian

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class OtaTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.messagingagent.guardian.ACTION_TRIGGER_OTA") {
            val url = intent.getStringExtra("url") ?: return
            
            val serviceIntent = Intent(context, GuardianService::class.java).apply {
                action = "DOWNLOAD_AND_INSTALL"
                putExtra("url", url)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
