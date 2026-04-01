package com.messagingagent.android.bot

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.messagingagent.android.R
import com.messagingagent.android.rcs.PendingDlrTracker
import com.messagingagent.android.rcs.RcsSender
import com.messagingagent.android.service.DeviceLogBus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import timber.log.Timber
import javax.inject.Inject

/**
 * Executes high-CPU UI Automator sequences and bugle_db polling.
 * Runs in an isolated `android:process=":bot"` to ensure arbitrary MIUI
 * Phantom Process quotas do not kill the primary MessagingAgent WebSockets.
 */
@AndroidEntryPoint
class BotService : Service() {

    @Inject lateinit var rcsSender: RcsSender
    // Each process instantiated gets its own Dagger Singleton graph.
    // Thus, this :bot process maintains the exclusive memory array of PendingDlrs.
    @Inject lateinit var dlrTracker: PendingDlrTracker

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        val notification = NotificationCompat.Builder(this, "messaging_agent_service")
            .setContentTitle("Messaging Bot Framework")
            .setContentText("UI Automator actively bound")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        // Run as foreground to prevent Android 11+ App Standby drops while querying queue
        // startForeground(1002, notification)
        Timber.i("BotService (:bot Process) initialized natively.")

        // Background loop to poll for pending Delivery Receipts
        scope.launch {
            Timber.i("BotService: DLR Watchdog coroutine started")
            dlrWatchdog()
        }
    }

    private suspend fun CoroutineScope.dlrWatchdog() {
        var lastGcTime = System.currentTimeMillis()
        val sentNotified = mutableSetOf<String>()
        val deliveredAt = mutableMapOf<String, Long>()

        val bugleDbDir = "/data/data/com.google.android.apps.messaging/databases"
        val srcDb = "$bugleDbDir/bugle_db"
        val tmpDb = "${applicationContext.filesDir.absolutePath}/dlr_check.db"
        val uid = android.os.Process.myUid()

        val changeChannel = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

        val observer = object : android.os.FileObserver(
            java.io.File(bugleDbDir),
            CLOSE_WRITE or MODIFY
        ) {
            override fun onEvent(event: Int, path: String?) {
                if (path != null && (path.contains("bugle_db"))) {
                    changeChannel.trySend(Unit)
                }
            }
        }

        com.topjohnwu.superuser.Shell.cmd("chmod 755 '$bugleDbDir'").exec()
        observer.startWatching()
        Timber.i("BotService: DLR FileObserver started on $bugleDbDir")

        try {
            while (isActive) {
                withTimeoutOrNull(2000) {
                    changeChannel.receive()
                }

                val now = System.currentTimeMillis()
                if (now - lastGcTime > 60_000) {
                    lastGcTime = now
                    val pending = dlrTracker.getPendingDlrs()
                    val stale = pending.filter { now - it.addedAt > 2 * 60 * 60 * 1000L }
                    if (stale.isNotEmpty()) {
                        stale.forEach { Timber.w("DLR GC: removing stale tracking ${it.correlationId}") }
                        dlrTracker.removeResolved(stale)
                    }
                }

                val currentPending = dlrTracker.getPendingDlrs()
                if (currentPending.isEmpty()) continue

                val resolved = mutableListOf<com.messagingagent.android.rcs.PendingDlr>()
                val claimedBugleIds = mutableSetOf<Long>()
                currentPending.filter { it.bugleMessageId > 0 }.forEach { claimedBugleIds.add(it.bugleMessageId) }

                val processStatus = { p: com.messagingagent.android.rcs.PendingDlr, matchedBugleId: Long, status: Int ->
                    val ageMs = now - p.addedAt
                    claimedBugleIds.add(matchedBugleId)
                    val alreadyDelivered = deliveredAt.containsKey(p.correlationId)

                    when (status) {
                        11 -> {
                            sendCommResult(p.correlationId, p.deviceToken, "DELIVERED", "SEEN/READ")
                            resolved.add(p)
                            deliveredAt.remove(p.correlationId)
                        }
                        12 -> {
                            sendCommResult(p.correlationId, p.deviceToken, "DELIVERED", "SEEN/READ")
                            resolved.add(p)
                            deliveredAt.remove(p.correlationId)
                        }
                        8, 5, 9 -> {
                            if (ageMs > 8_000) {
                                sendCommResult(p.correlationId, p.deviceToken, "ERROR", "bugle_status=$status")
                                resolved.add(p)
                                deliveredAt.remove(p.correlationId)
                            }
                        }
                        else -> {
                            if (!sentNotified.contains(p.correlationId)) {
                                sentNotified.add(p.correlationId)
                                sendCommResult(p.correlationId, p.deviceToken, "SENT", "status=$status")
                            }
                            if (ageMs > 300_000) {
                                sendCommResult(p.correlationId, p.deviceToken, "ERROR", "bugle_status=$status (timeout after 5m)")
                                resolved.add(p)
                                deliveredAt.remove(p.correlationId)
                            }
                        }
                    }
                }

                try {
                    val sqliteCmd = "/data/local/tmp/sqlite3 '$srcDb'"

                    for (p in currentPending) {
                        try {
                            var matchedBugleId = 0L
                            var status = -1

                            if (p.bugleMessageId > 0) {
                                val sql = "SELECT _id, message_status FROM messages WHERE _id = ${p.bugleMessageId};"
                                val res = com.topjohnwu.superuser.Shell.cmd("$sqliteCmd \"$sql\" 2>/dev/null").exec()
                                val matchLine = res.out.firstOrNull { it.contains("|") }?.trim()
                                if (matchLine != null) {
                                    val parts = matchLine.split("|")
                                    matchedBugleId = parts[0].toLongOrNull() ?: 0L
                                    status = parts[1].toIntOrNull() ?: -1
                                }
                            } else {
                                val excludeClause = if (claimedBugleIds.isNotEmpty()) " AND _id NOT IN (${claimedBugleIds.joinToString(",")})" else ""
                                val sql = "SELECT _id, message_status FROM messages WHERE _id > ${p.initialMaxId}$excludeClause ORDER BY _id ASC LIMIT 1;"
                                
                                val res = com.topjohnwu.superuser.Shell.cmd("$sqliteCmd \"$sql\" 2>/dev/null").exec()
                                val matchLine = res.out.firstOrNull { it.contains("|") }?.trim()
                                if (matchLine != null) {
                                    val parts = matchLine.split("|")
                                    matchedBugleId = parts[0].toLongOrNull() ?: 0L
                                    status = parts[1].toIntOrNull() ?: -1
                                }
                            }

                            if (matchedBugleId > 0 && status != -1) {
                                if (p.bugleMessageId == 0L) {
                                    dlrTracker.updateBugleMessageId(p.correlationId, matchedBugleId)
                                }
                                processStatus(p, matchedBugleId, status)
                            }
                        } catch (e: Exception) { Timber.e(e, "BotService: rawQuery exception for ${p.correlationId}") }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "BotService: failed to execute sqlite binary")
                }


                if (resolved.isNotEmpty()) {
                    dlrTracker.removeResolved(resolved)
                    resolved.forEach { sentNotified.remove(it.correlationId) }
                }
            }
        } finally {
            observer.stopWatching()
            Timber.i("BotService: DLR FileObserver stopped")
        }
    }

    private fun sendCommResult(correlationId: String, deviceToken: String, resultStr: String, errorDetail: String?) {
        val resultIntent = Intent("com.messagingagent.android.comm.ACTION_RECHECK_RESULT").apply {
            setPackage(applicationContext.packageName)
            putExtra("correlationId", correlationId)
            putExtra("deviceToken", deviceToken)
            putExtra("result", resultStr)
            putExtra("errorDetail", errorDetail)
        }
        sendBroadcast(resultIntent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_STICKY
        
        when (action) {
            "com.messagingagent.android.bot.ACTION_SEND" -> {
                val to = intent.getStringExtra("to") ?: return START_STICKY
                val text = intent.getStringExtra("text") ?: return START_STICKY
                val corrId = intent.getStringExtra("correlationId") ?: return START_STICKY
                val deviceToken = intent.getStringExtra("deviceToken") ?: return START_STICKY
                val subId = intent.getIntExtra("subscriptionId", -1)
                val dlrDelayMinSec = intent.getIntExtra("dlrDelayMinSec", 2)
                val dlrDelayMaxSec = intent.getIntExtra("dlrDelayMaxSec", 5)

                Timber.i("BotService: Received SEND request for $corrId")
                scope.launch {
                    val result = rcsSender.sendRcs(
                        corrId, deviceToken, to, text, subId, dlrDelayMinSec, dlrDelayMaxSec
                    )
                    val resultIntent = Intent("com.messagingagent.android.comm.ACTION_DISPATCH_RESULT").apply {
                        setPackage(applicationContext.packageName)
                        putExtra("correlationId", corrId)
                        putExtra("deviceToken", deviceToken)
                        putExtra("success", result.success)
                        putExtra("noRcs", result.noRcs)
                        putExtra("error", result.error)
                        putExtra("sentEarly", result.sentEarly)
                    }
                    sendBroadcast(resultIntent)
                }
            }
            "com.messagingagent.android.bot.ACTION_RECHECK_DLR" -> {
                val corrId = intent.getStringExtra("correlationId") ?: return START_STICKY
                val deviceToken = intent.getStringExtra("deviceToken") ?: return START_STICKY
                Timber.i("BotService: Received RECHECK_DLR request for $corrId")
                
                scope.launch {
                    try {
                        val pending = dlrTracker.getPendingDlrs().firstOrNull { it.correlationId == corrId }
                        if (pending != null && pending.bugleMessageId > 0) {
                            val srcDb = "/data/data/com.google.android.apps.messaging/databases/bugle_db"
                            val sqliteCmd = "/data/local/tmp/sqlite3 '$srcDb'"

                            var deliveryResult: String? = null
                            var errorDetail: String? = null
                            
                            val sql = "SELECT message_status FROM messages WHERE _id = ${pending.bugleMessageId};"
                            val res = com.topjohnwu.superuser.Shell.cmd("$sqliteCmd \"$sql\" 2>/dev/null").exec()
                            val msStr = res.out.firstOrNull { it.trim().isNotEmpty() }?.trim()
                            
                            if (msStr != null) {
                                val ms = msStr.toIntOrNull() ?: -1
                                if (ms == 12 || ms == 11) {
                                    deliveryResult = "DELIVERED"
                                    dlrTracker.removeResolved(listOf(pending))
                                } else if (ms == 8 || ms == 5 || ms == 9) {
                                    deliveryResult = "ERROR"
                                    errorDetail = "Failed Status=$ms"
                                    dlrTracker.removeResolved(listOf(pending))
                                } else if (ms == 2) {
                                    deliveryResult = "SENT"
                                } else {
                                    deliveryResult = "SENT"
                                }
                            } else {
                                deliveryResult = "ERROR"
                                errorDetail = "Message _id ${pending.bugleMessageId} deleted/missing from SQL payload"
                            }

                            if (deliveryResult != null) {
                                val resultIntent = Intent("com.messagingagent.android.comm.ACTION_RECHECK_RESULT").apply {
                                    setPackage(applicationContext.packageName)
                                    putExtra("correlationId", corrId)
                                    putExtra("deviceToken", deviceToken)
                                    putExtra("result", deliveryResult)
                                    putExtra("errorDetail", errorDetail)
                                }
                                sendBroadcast(resultIntent)
                            }
                        } else {
                            // If pending missing or id=0, use correlation fallback logic (requires message text querying, etc)
                            // We can just rely on the existing DLR recheck queue in the main scan
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "BotService: Exception during manual RECHECK_DLR SQLite execution")
                    }
                }
            }
            "com.messagingagent.android.bot.ACTION_TRACK_MATRIX_DLR" -> {
                val corrId = intent.getStringExtra("correlationId") ?: return START_STICKY
                val messageTextBase64 = intent.getStringExtra("messageTextBase64") ?: ""
                val deviceToken = intent.getStringExtra("deviceToken") ?: return START_STICKY
                
                Timber.i("BotService: Received TRACK_MATRIX_DLR for $corrId")
                
                scope.launch {
                    try {
                        val decodedText = String(android.util.Base64.decode(messageTextBase64, android.util.Base64.DEFAULT))
                        val safeText = decodedText.replace("\"", "\"\"").replace("'", "''")
                        
                        val srcDb = "/data/data/com.google.android.apps.messaging/databases/bugle_db"
                        val sqliteCmd = "/data/local/tmp/sqlite3 '$srcDb'"

                        // Query for text match on Parts table, and join to get Message Status
                        val sql = "SELECT m.message_status FROM messages m INNER JOIN parts p ON p.message_id = m._id WHERE p.text LIKE '%$safeText%' ORDER BY m._id DESC LIMIT 1;"
                        val res = com.topjohnwu.superuser.Shell.cmd("$sqliteCmd \"$sql\" 2>/dev/null").exec()
                        val msStr = res.out.firstOrNull { it.trim().isNotEmpty() }?.trim()
                        
                        var deliveryResult: String? = null
                        var errorDetail: String? = null
                        
                        if (msStr != null) {
                            val ms = msStr.toIntOrNull() ?: -1
                            if (ms == 12 || ms == 11) {
                                deliveryResult = "DELIVERED"
                            } else if (ms == 8 || ms == 5 || ms == 9) {
                                deliveryResult = "ERROR"
                                errorDetail = "Failed Status=$ms"
                            }
                        }
                        
                        if (deliveryResult == "DELIVERED" || deliveryResult == "ERROR") {
                            val resultIntent = Intent("com.messagingagent.android.comm.ACTION_RECHECK_RESULT").apply {
                                setPackage(applicationContext.packageName)
                                putExtra("correlationId", corrId)
                                putExtra("deviceToken", deviceToken)
                                putExtra("result", deliveryResult)
                                putExtra("errorDetail", errorDetail)
                            }
                            sendBroadcast(resultIntent)
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "BotService: Exception during TRACK_MATRIX_DLR execution")
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
