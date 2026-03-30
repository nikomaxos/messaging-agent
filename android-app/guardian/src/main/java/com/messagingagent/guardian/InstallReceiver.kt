package com.messagingagent.guardian

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.messagingagent.guardian.ACTION_INSTALL_COMPLETE") {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirmationIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    }
                    
                    if (confirmationIntent != null) {
                        confirmationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        sendProgress(context, "Please confirm installation prompt on screen...")
                        context.startActivity(confirmationIntent)
                    } else {
                        sendProgress(context, "Error: Could not display install prompt.")
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    sendProgress(context, "Installation Success! Starting Agent...")
                    Log.i("Guardian", "Install successful!")
                }
                else -> {
                    val errorMsg = "Install failed: $status - $message"
                    sendProgress(context, errorMsg)
                    Log.e("Guardian", errorMsg)
                }
            }
        }
    }

    private fun sendProgress(context: Context, status: String) {
        val updateIntent = Intent("com.messagingagent.guardian.UPDATE_STATUS")
        updateIntent.putExtra("status", status)
        updateIntent.putExtra("progress", 100)
        updateIntent.setPackage(context.packageName)
        context.sendBroadcast(updateIntent)
    }
}
