package com.messagingagent.guardian

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast

class GuardianActivity : Activity() {

    private var isDownloading = false
    private lateinit var statusTextView: TextView

    private val statusReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            val status = intent.getStringExtra("status") ?: return
            statusTextView.text = status
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        statusTextView = TextView(this).apply {
            text = "Messaging Guardian is Active.\nVerifying Installation Permissions..."
            textSize = 20f
            setPadding(32, 32, 32, 32)
        }
        setContentView(statusTextView)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                Toast.makeText(this, "Please allow Guardian to install apps", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${packageName}")
                }
                startActivity(intent)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, android.content.IntentFilter("com.messagingagent.guardian.UPDATE_STATUS"), RECEIVER_EXPORTED)
        } else {
            registerReceiver(statusReceiver, android.content.IntentFilter("com.messagingagent.guardian.UPDATE_STATUS"))
        }
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            if (packageManager.canRequestPackageInstalls()) {
                if (!isDownloading) {
                    isDownloading = true
                    Toast.makeText(this, "Guardian Permissions Granted!", Toast.LENGTH_SHORT).show()
                    startAutoDownload()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
    }

    private fun startAutoDownload() {
        // Automatically start the GuardianService to download the latest Messaging Agent
        val intent = Intent(this, GuardianService::class.java).apply {
            action = "DOWNLOAD_AND_INSTALL"
            // We use BuildConfig.API_BASE_URL + "/api/public/apk/download"
            putExtra("url", "${BuildConfig.API_BASE_URL}/api/public/apk/download")
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        statusTextView.text = "Guardian is Active.\nConnecting to download the Latest Messaging Agent..."
    }
}
