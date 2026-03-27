package com.messagingagent.guardian

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast

class GuardianActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val textView = TextView(this).apply {
            text = "Messaging Guardian is Active.\nVerifying Installation Permissions..."
            textSize = 20f
            setPadding(32, 32, 32, 32)
        }
        setContentView(textView)

        checkAndRequestInstallPermission()
    }

    private fun checkAndRequestInstallPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                Toast.makeText(this, "Please allow Guardian to install apps", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${packageName}")
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "Guardian Permissions Granted!", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
