package com.messagingagent.android.service

import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.messagingagent.android.rcs.PendingDlr
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File

class BugleRootService : RootService() {
    private var clientMessenger: Messenger? = null
    private val pendingDlrs = mutableListOf<PendingDlr>()
    private val serviceJob = Job()
    private val scope = CoroutineScope(Dispatchers.IO + serviceJob)

    inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                1 -> {
                    clientMessenger = msg.replyTo
                    val json = msg.data.getString("pending") ?: "[]"
                    updatePending(json)
                }
                2 -> {
                    val json = msg.data.getString("pending") ?: "[]"
                    updatePending(json)
                }
            }
        }
    }

    private fun updatePending(jsonStr: String) {
        pendingDlrs.clear()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val dest = obj.getString("destinationAddress").replace(Regex("[^0-9+]"), "")
                val cleanDest = if (dest.length > 7) dest.takeLast(7) else dest
                pendingDlrs.add(PendingDlr(
                    correlationId = obj.getString("correlationId"),
                    destinationAddress = cleanDest,
                    initialMaxId = obj.getLong("initialMaxId"),
                    deviceToken = obj.optString("deviceToken")
                ))
            }
        } catch (e: Exception) {}
    }

    override fun onBind(intent: Intent): IBinder {
        startWatchdog()
        return Messenger(IncomingHandler()).binder
    }

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun startWatchdog() {
        scope.launch {
            while (isActive) {
                delay(800)
                if (pendingDlrs.isEmpty()) continue

                val dbPath = "/data/data/com.google.android.apps.messaging/databases/bugle_db"
                
                if (!java.io.File(dbPath).exists()) continue
                    
                try {
                    SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                        val resolved = mutableListOf<PendingDlr>()
                        for (p in pendingDlrs.toList()) {
                            val query = """
                                SELECT m.message_status, ms.status AS sub_status 
                                FROM messages m
                                INNER JOIN conversation_participants cp ON m.conversation_id = cp.conversation_id
                                INNER JOIN participants par ON cp.participant_id = par._id
                                LEFT JOIN message_status ms ON ms.message_id = m._id
                                WHERE m._id > ? AND (par.normalized_destination LIKE ? OR par.send_destination LIKE ?) AND m.message_status != 1
                                ORDER BY m._id ASC, ms.timestamp DESC
                            """.trimIndent()
                            
                            var finalResult: String? = null
                            db.rawQuery(query, arrayOf(p.initialMaxId.toString(), "%${p.destinationAddress}%", "%${p.destinationAddress}%")).use { cursor ->
                                while (cursor.moveToNext()) {
                                    val nativeStatus = cursor.getInt(0)
                                    val subStatus = if (cursor.isNull(1)) -1 else cursor.getInt(1)
                                    
                                    // Treat native status 100 as Delivered (RCS direct)
                                    if (nativeStatus in listOf(2, 13, 14, 100)) {
                                        finalResult = "DELIVERED"
                                        break
                                    }
                                    if (nativeStatus in listOf(3, 8, 9) && subStatus == -1) {
                                        // Only error natively if no subStatus confirms delivery
                                        finalResult = "ERROR"
                                    }
                                    
                                    // Treat sub-tracker statuses mapping to Sent/Delivered/Read
                                    if (subStatus in listOf(2, 11, 13, 14, 100, 206, 207, 213, 214)) {
                                        finalResult = "DELIVERED"
                                        break
                                    }
                                    // Sub-status 5 seems to precede 0, 1, 2 sequentially during intent handoff so we ignore it as an error flag
                                    if (subStatus == 8 || subStatus == 9) {
                                        finalResult = "ERROR"
                                    }
                                }
                            }
                            
                            if (finalResult != null) {
                                resolved.add(p)
                                val b = Bundle()
                                b.putString("correlationId", p.correlationId)
                                b.putString("result", finalResult)
                                b.putString("deviceToken", p.deviceToken)
                                val m = Message.obtain(null, 3)
                                m.data = b
                                clientMessenger?.send(m)
                            }
                        }
                        
                        if (resolved.isNotEmpty()) {
                            pendingDlrs.removeAll(resolved)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("BugleRoot", "SQLite Polling Exception: " + e.message, e)
                }
            }
            delay(1000)
        }
    }
}
