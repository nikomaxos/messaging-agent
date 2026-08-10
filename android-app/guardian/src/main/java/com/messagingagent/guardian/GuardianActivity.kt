package com.messagingagent.guardian

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.content.pm.PackageManager
import android.graphics.Color
import android.view.Gravity

class GuardianActivity : Activity() {

    private var isDownloading = false
    private lateinit var statusTextView: TextView
    private lateinit var container: LinearLayout

    private val statusReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            val status = intent.getStringExtra("status") ?: return
            statusTextView.text = status
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
            setBackgroundColor(Color.parseColor("#0D0D1A"))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val titleText = TextView(this).apply {
            text = "Messaging Guardian"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }

        statusTextView = TextView(this).apply {
            text = "Verifying Installation Permissions..."
            textSize = 16f
            setTextColor(Color.parseColor("#8899AA"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 64)
        }

        container.addView(titleText)
        container.addView(statusTextView)
        setContentView(container)

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
                checkAndSetupBootstrap()
            }
        } else {
            checkAndSetupBootstrap()
        }
    }

    private fun isAgentInstalled(): Boolean {
        return try {
            packageManager.getPackageInfo("com.messagingagent.android", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun checkAndSetupBootstrap() {
        if (isAgentInstalled()) {
            if (container.childCount > 2) {
                container.removeViews(2, container.childCount - 2)
            }
            if (!isDownloading) {
                isDownloading = true
                startAutoDownload()
            }
        } else {
            setupBootstrapUi()
        }
    }

    private fun setupBootstrapUi() {
        if (container.childCount > 2) return // UI already setup
        
        statusTextView.text = "Main Agent not installed.\nPlease enter backend URL to download it."

        val prefs = getSharedPreferences("guardian_prefs", MODE_PRIVATE)
        val savedUrl = prefs.getString("backend_url", "http://192.168.1.5:9090")

        val urlInput = EditText(this).apply {
            setText(savedUrl)
            hint = "192.168.1.10"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#4A5568"))
            textSize = 18f
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.parseColor("#1A1A2E"))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 0, 0, 48)
            layoutParams = params
        }

        val downloadButton = Button(this).apply {
            text = "Download & Install Agent"
            setBackgroundColor(Color.parseColor("#6366F1"))
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, 32, 0, 32)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams = params

            setOnClickListener {
                var inputUrl = urlInput.text.toString().trim()
                if (inputUrl.isNotBlank()) {
                    if (!inputUrl.startsWith("http://") && !inputUrl.startsWith("https://")) {
                        inputUrl = "http://$inputUrl"
                    }
                    if (!inputUrl.matches(Regex(".*:\\d+(/.*)?$"))) {
                        inputUrl = "$inputUrl:9090"
                    }

                    prefs.edit().putString("backend_url", inputUrl).apply()
                    statusTextView.text = "Starting download..."
                    val downloadUrl = "${inputUrl.trimEnd('/')}/api/public/apk/download"
                    
                    val serviceIntent = Intent(this@GuardianActivity, GuardianService::class.java).apply {
                        action = "DOWNLOAD_AND_INSTALL"
                        putExtra("url", downloadUrl)
                    }
                    startService(serviceIntent)
                }
            }
        }

        container.addView(urlInput)
        container.addView(downloadButton)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
    }

    private fun startAutoDownload() {
        val intent = Intent("com.messagingagent.android.REQUEST_OTA").apply {
            setPackage("com.messagingagent.android")
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }
        sendBroadcast(intent)
        
        statusTextView.text = "Guardian is Active.\nWaiting for URL from Messaging Agent..."
    }
}

