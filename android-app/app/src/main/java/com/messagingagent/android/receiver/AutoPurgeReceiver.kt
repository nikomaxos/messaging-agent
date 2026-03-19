package com.messagingagent.android.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.messagingagent.android.data.PreferencesRepository
import com.messagingagent.android.service.MessagingAgentService
import com.topjohnwu.superuser.Shell
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AutoPurgeReceiver : BroadcastReceiver() {

    @Inject
    lateinit var prefs: PreferencesRepository

    override fun onReceive(context: Context, intent: Intent) {
        CoroutineScope(Dispatchers.IO).launch {
            val purgeMode = prefs.getAutoPurgeMode()
            if (purgeMode == "OFF") return@launch

            val lastPurgedAt = prefs.getLastPurgedAt()
            val currentTime = System.currentTimeMillis()
            
            // 5 days in milliseconds
            val fiveDaysInMillis = 5L * 24L * 60L * 60L * 1000L

            if (currentTime - lastPurgedAt < fiveDaysInMillis) return@launch

            val shouldPurgeMessages = purgeMode == "MESSAGES" || purgeMode == "ALL"
            val shouldPurgeLogs = purgeMode == "SYSTEM_LOGS" || purgeMode == "ALL"

            // 1. Disconnect the application (Stop Service)
            val stopIntent = Intent(context, MessagingAgentService::class.java)
            context.stopService(stopIntent)

            try {
                if (shouldPurgeMessages) {
                    // Clear out Android's bugle_db local messages database
                    val tmpDb = "/data/local/tmp/bug_purge.db"
                    Shell.cmd(
                        "cp /data/data/com.google.android.apps.messaging/databases/bugle_db $tmpDb",
                        "chown root:root $tmpDb",
                        "chmod 666 $tmpDb"
                    ).exec()
                    
                    try {
                        android.database.sqlite.SQLiteDatabase.openDatabase(tmpDb, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
                            db.execSQL("DELETE FROM messages")
                            db.execSQL("DELETE FROM conversations")
                            db.execSQL("DELETE FROM parts")
                        }
                        Shell.cmd("cat $tmpDb > /data/data/com.google.android.apps.messaging/databases/bugle_db").exec()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        Shell.cmd("rm -f $tmpDb").exec()
                    }
                    
                    // Clear Android telephony provider
                    val cr = context.contentResolver
                    val uri = android.net.Uri.parse("content://sms")
                    cr.delete(uri, null, null)
                }

                if (shouldPurgeLogs) {
                    // Clear Android logcat buffer
                    Shell.cmd("logcat -c").exec()
                }

                // Update lastPurgedAt
                prefs.setLastPurgedAt(currentTime)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                // Reconnect the application (Start Service)
                val startIntent = Intent(context, MessagingAgentService::class.java)
                context.startForegroundService(startIntent)
            }
        }
    }
}
