package com.messagingagent.android.service

import com.messagingagent.android.rcs.RcsSender
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class SmsDispatch(
    val correlationId: String,
    val sourceAddress: String,
    val destinationAddress: String,
    val messageText: String,
    val dlrDelayMinSec: Int = 2,
    val dlrDelayMaxSec: Int = 5
)

@Serializable
data class DeliveryResult(
    val correlationId: String,
    val result: String,       // DELIVERED | NO_RCS | TIMEOUT | ERROR
    val errorDetail: String? = null
)

data class LogEntry(val time: String, val level: String, val message: String)

/**
 * WebSocket STOMP relay client.
 *
 * Connects to the backend over a raw (non-SockJS) STOMP WebSocket.
 * Designed to be called from MessagingAgentService.onStartCommand() which
 * may fire multiple times — connect() is idempotent: it closes any existing
 * connection before opening a new one, and each WebSocket instance is tagged
 * with a unique generation ID so stale retry callbacks are silently ignored.
 */
@Singleton
class WebSocketRelayClient @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val rcsSender: RcsSender,
    private val prefs: com.messagingagent.android.data.PreferencesRepository
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(25, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private val json = Json { ignoreUnknownKeys = true }
    private var statusCallback: ((String) -> Unit)? = null

    /** Monotonically increasing generation — stale retries from old sockets are ignored. */
    @Volatile private var generation = 0

    /** The active list of SIM sessions to route commands and SMS payloads. */
    private var activeSims: List<com.messagingagent.android.data.SimRegistration> = emptyList()
    private var activeBackendUrl: String? = null

    /** Pending retry job (cancel on new connect). */
    private var retryJob: Job? = null

    /** Call blocker coroutine */
    @Volatile private var callBlockEnabled = false
    private var callBlockJob: Job? = null

    /** Screen streaming coroutine for remote desktop */
    private var screenStreamJob: Job? = null
    @Volatile private var screenStreamDeviceToken: String? = null

    /** STOMP periodic ping job to prevent backend server from forcibly closing idle TCP connection */
    private var pingJob: Job? = null

    /** Live connection log for display in DoneStep UI. */
    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> = _log.asStateFlow()

    /** Precise connection start time to drive UI uptime counter */
    val connectionStartTime = MutableStateFlow<Long?>(null)

    /** Track which log entries have been sent to backend already */
    @Volatile private var lastSentLogIndex = 0

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    internal fun addLog(level: String, message: String) {
        val entry = LogEntry(timeFmt.format(Date()), level, message)
        val current = _log.value.takeLast(199)   // keep last 200 entries
        _log.value = current + entry
        Timber.tag("WSRelay").d("[$level] $message")
    }

    /**
     * Connect (or reconnect) to the backend WebSocket.
     * Safe to call multiple times — closes any active connection first.
     */
    fun connect(backendUrl: String, sims: List<com.messagingagent.android.data.SimRegistration>, onStatus: (String) -> Unit) {
        statusCallback = onStatus
        activeSims = sims
        activeBackendUrl = backendUrl

        if (sims.isEmpty()) {
            addLog("WARN", "No registered SIMs to connect")
            return
        }

        // Close any existing connection cleanly before opening a new one.
        retryJob?.cancel()
        retryJob = null
        pingJob?.cancel()
        pingJob = null
        val prev = ws
        ws = null
        prev?.close(1000, "Reconnecting")

        val myGen = ++generation     // capture this generation for the callback closures
        val wsUrl = backendUrl.trimEnd('/').replace("http://", "ws://").replace("https://", "wss://") + "/ws"

        addLog("INFO", "Connecting to $wsUrl (gen=$myGen)")
        onStatus("Connecting…")

        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("deviceToken", sims.first().deviceToken)
            .build()
        
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (generation != myGen) return   // stale — a newer connect() replaced this socket
                retryJob?.cancel()
                connectionStartTime.value = System.currentTimeMillis()
                addLog("INFO", "WebSocket open to $wsUrl — sending STOMP CONNECT")
                onStatus("Connected to backend")
                // Allow OkHttp's underlying `pingInterval(25, SECONDS)` to keep the network layer alive
                // Tell server: STOMP heartbeats are disabled (0,0) so it doesn't drop us due to missing STOMP frames
                val baseToken = activeSims.first().deviceToken
                webSocket.send("CONNECT\naccept-version:1.2\nheart-beat:0,0\ndeviceToken:$baseToken\n\n\u0000")
                

                activeSims.forEachIndexed { i, sim ->
                    webSocket.send("SUBSCRIBE\nid:sub-sms-$i\ndestination:/queue/sms.${sim.deviceId}\n\n\u0000")
                    webSocket.send("SUBSCRIBE\nid:sub-cmd-$i\ndestination:/queue/commands.${sim.deviceId}\n\n\u0000")
                }
                
                pingJob = CoroutineScope(Dispatchers.IO).launch {
                    while (isActive) {
                        delay(20_000)
                        try {
                            if (generation == myGen) webSocket.send("\n")
                        } catch (e: Exception) { /* ignored */ }
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (generation != myGen) return
                val preview = text.take(120).replace('\n', '↵')
                addLog("MSG", preview)
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (generation != myGen) return
                pingJob?.cancel()
                addLog("WARN", "WebSocket closing: code=$code reason=$reason")
                webSocket.close(1000, null)
                onStatus("Disconnecting…")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (generation != myGen) return
                pingJob?.cancel()
                connectionStartTime.value = null
                addLog("WARN", "WebSocket closed: code=$code reason=$reason")
                onStatus("Offline")
                scheduleRetry(backendUrl, sims, onStatus, myGen)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (generation != myGen) {
                    addLog("DEBUG", "Stale onFailure ignored (gen=$myGen != current=$generation): ${t.message}")
                    return
                }
                pingJob?.cancel()
                connectionStartTime.value = null
                val httpCode = response?.code?.toString() ?: "no-response"
                addLog("ERROR", "WebSocket failure [HTTP $httpCode]: ${t.javaClass.simpleName}: ${t.message}")
                onStatus("Disconnected — retrying…")
                scheduleRetry(backendUrl, sims, onStatus, myGen)
            }
        })
    }

    private fun scheduleRetry(backendUrl: String, sims: List<com.messagingagent.android.data.SimRegistration>, onStatus: (String) -> Unit, myGen: Int) {
        retryJob = CoroutineScope(Dispatchers.IO).launch {
            addLog("INFO", "Will retry in 8s…")
            delay(8_000)
            if (generation == myGen) {          // still the active generation
                addLog("INFO", "Retrying connection…")
                connect(backendUrl, sims, onStatus)
            } else {
                addLog("DEBUG", "Retry cancelled — superseded by newer connect()")
            }
        }
    }

    private fun handleMessage(text: String) {
        if (!text.startsWith("MESSAGE")) return
        val destMatch = Regex("""destination:(.+)""").find(text)?.groupValues?.get(1)
        val body = text.substringAfter("\n\n").trimEnd('\u0000')

        if (destMatch?.startsWith("/queue/commands.") == true) {
            val deviceIdStr = destMatch.substringAfterLast(".")
            val sim = activeSims.find { it.deviceId.toString() == deviceIdStr }
            handleSysCommand(body, sim?.deviceToken)
            return
        }

        try {
            val deviceIdStr = destMatch?.substringAfterLast(".")
            val sim = activeSims.find { it.deviceId.toString() == deviceIdStr } ?: return

            val dispatch = json.decodeFromString<SmsDispatch>(body)
            CoroutineScope(Dispatchers.IO).launch {
                val result = rcsSender.sendRcs(
                    correlationId = dispatch.correlationId,
                    deviceToken = sim.deviceToken,
                    to = dispatch.destinationAddress,
                    text = dispatch.messageText,
                    subscriptionId = sim.subscriptionId,
                    dlrDelayMinSec = dispatch.dlrDelayMinSec,
                    dlrDelayMaxSec = dispatch.dlrDelayMaxSec
                )
                
                // If the bot successfully tapped the physical Send button,
                // the device stays BUSY. The DLR watchdog will detect status=2
                // (submitted to network) in bugle_db and send SENT to unlock the device.
                if (!result.success) {
                    sendDeliveryResult(DeliveryResult(
                        correlationId = dispatch.correlationId,
                        result = if (result.noRcs) "NO_RCS" else "ERROR",
                        errorDetail = result.error
                    ), sim.deviceToken)
                } else if (result.sentEarly) {
                    // Fast-path: DB verify confirmed message entered bugle_db.
                    // Send SENT immediately to unlock device — skip DLR watchdog latency.
                    Timber.i("Fast-path SENT: sending immediately for ${dispatch.correlationId}")
                    sendDeliveryResult(DeliveryResult(
                        correlationId = dispatch.correlationId,
                        result = "SENT"
                    ), sim.deviceToken)
                }
            }
        } catch (e: Exception) {
            addLog("ERROR", "Failed to parse dispatch: ${e.message}")
        }
    }

    private fun handleSysCommand(body: String, commandTargetToken: String?) {
        addLog("INFO", "Received system command: $body")
        when {
            body == "REBOOT" -> {
                com.topjohnwu.superuser.Shell.cmd("su -c reboot").exec()
            }
            body == "RECONNECT" -> {
                // By closing the socket cleanly WITHOUT incrementing generation,
                // the existing onClosed listener will automatically schedule a retry.
                ws?.close(1000, "Reconnect requested")
            }
            body == "UPDATE_APK" -> {
                val url = activeBackendUrl ?: return
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        if (commandTargetToken != null) sendApkUpdateStatus("Downloading…", commandTargetToken)
                        addLog("INFO", "Downloading APK update...")
                        val request = Request.Builder().url("${url.trimEnd('/')}/api/public/apk/download").build()
                        val response = client.newCall(request).execute()
                        if (response.isSuccessful) {
                            val apkFile = java.io.File(context.cacheDir, "update.apk")
                            response.body?.byteStream()?.use { input ->
                                apkFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            
                            if (commandTargetToken != null) sendApkUpdateStatus("Installing…", commandTargetToken)
                            addLog("INFO", "APK downloaded. Preparing install...")
                            
                            // Always try root install first — on MIUI, isAppGrantedRoot()
                            // can return false even when `su` actually works (state desync).
                            val scriptFile = java.io.File(context.cacheDir, "installer.sh")
                            scriptFile.writeText("""
                                sleep 2
                                echo "Starting install" > /data/local/tmp/ota.log
                                cp "${apkFile.absolutePath}" /data/local/tmp/update.apk
                                echo "Copied APK" >> /data/local/tmp/ota.log
                                pm install -r -d -g /data/local/tmp/update.apk >> /data/local/tmp/ota.log 2>&1
                                echo "Install exit: ${'$'}?" >> /data/local/tmp/ota.log
                                rm /data/local/tmp/update.apk
                                am start -n com.messagingagent.android/com.messagingagent.android.ui.SetupActivity >> /data/local/tmp/ota.log 2>&1
                            """.trimIndent())
                            
                            val installResult = com.topjohnwu.superuser.Shell.cmd(
                                "nohup sh \"${scriptFile.absolutePath}\" >> /data/local/tmp/ota.log 2>&1 &"
                            ).exec()
                            
                            if (installResult.isSuccess) {
                                addLog("INFO", "Root update script deployed. App will restart momentarily.")
                                ws?.close(1000, "Applying OTA Update")
                            } else {
                                // Root truly not available — fall back to Package Installer UI
                                addLog("WARN", "Root install failed (su not available). Opening Package Installer...")
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context, "${context.packageName}.provider", apkFile
                                )
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, "application/vnd.android.package-archive")
                                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                }
                                context.startActivity(intent)
                                if (commandTargetToken != null) sendApkUpdateStatus("Waiting for user to install", commandTargetToken)
                            }
                        } else {
                            if (commandTargetToken != null) sendApkUpdateStatus("Failed: download error", commandTargetToken)
                            addLog("ERROR", "APK download failed: HTTP ${response.code}")
                        }
                    } catch (e: Exception) {
                        if (commandTargetToken != null) sendApkUpdateStatus("Failed: error", commandTargetToken)
                        addLog("ERROR", "APK update error: ${e.message}")
                    }
                }
            }
            body.startsWith("CANCEL_RCS=") -> {
                val dest = body.substringAfter("=")
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        addLog("INFO", "Cancelling pending RCS to $dest")
                        
                        // First kill the Google Messages app to halt immediate retries
                        com.topjohnwu.superuser.Shell.cmd("am force-stop com.google.android.apps.messaging").exec()
                        
                        val cleanDest = dest.replace("[^0-9+]".toRegex(), "")
                        
                        // Delete from Android telephony provider (outbox = 4, queued = 6, failed = 5)
                        val cr = context.contentResolver
                        val uri = android.net.Uri.parse("content://sms")
                        val deleted = cr.delete(uri, "address LIKE ? AND type IN (4, 5, 6)", arrayOf("%$cleanDest%"))
                        
                        addLog("INFO", "Deleted $deleted pending SMS/RCS jobs from telephony provider for $cleanDest")
                        
                        // Delete pending/queued/sent messages from bugle_db using on-device sqlite3
                        // Status codes from live DB analysis: 1=SENDING, 2=QUEUED, 100=SENT
                        val bugleDb = "/data/data/com.google.android.apps.messaging/databases/bugle_db"
                        val sqlite3 = "/data/local/tmp/sqlite3"
                        val sql = "DELETE FROM messages WHERE message_status IN (1,2,100) AND conversation_id IN (SELECT _id FROM conversations WHERE participant_normalized_destination LIKE '%$cleanDest%');"
                        val result = com.topjohnwu.superuser.Shell.cmd("$sqlite3 '$bugleDb' \"$sql\"").exec()
                        
                        if (result.isSuccess) {
                            addLog("INFO", "Purged pending messages from bugle_db for $cleanDest")
                        } else {
                            addLog("ERROR", "sqlite3 cancel failed: ${result.err}")
                        }
                    } catch (e: Exception) {
                        addLog("ERROR", "Failed to cancel RCS for $dest: ${e.message}")
                    }
                }
            }
            body.startsWith("SET_AUTO_REBOOT=") -> {
                CoroutineScope(Dispatchers.IO).launch {
                    val enabled = body.substringAfter("=").toBooleanStrictOrNull() ?: false
                    prefs.setAutoRebootEnabled(enabled)
                    addLog("INFO", "Auto-reboot set to $enabled")
                }
            }
            body.startsWith("SET_SELF_HEALING=") -> {
                CoroutineScope(Dispatchers.IO).launch {
                    val enabled = body.substringAfter("=").toBooleanStrictOrNull() ?: false
                    prefs.setSelfHealingEnabled(enabled)
                    addLog("INFO", "Self-healing set to $enabled")
                }
            }
            body.startsWith("SET_AUTO_PURGE=") -> {
                CoroutineScope(Dispatchers.IO).launch {
                    val mode = body.substringAfter("=").uppercase()
                    prefs.setAutoPurgeMode(mode)
                    addLog("INFO", "Auto-Purge mode set to $mode")
                }
            }
            body.startsWith("RECHECK_DLR=") -> {
                CoroutineScope(Dispatchers.IO).launch {
                    val correlationId = body.substringAfter("=").trim()
                    addLog("INFO", "🔄 DLR re-check requested for $correlationId")
                    try {
                        // Look up the pending DLR entry for this correlationId
                        val pending = rcsSender.dlrTracker.getPendingDlrs()
                            .firstOrNull { it.correlationId == correlationId }
                        
                        if (pending != null && pending.bugleMessageId > 0) {
                            // We have the exact bugle_db message ID — query it directly
                            val tmpDb = "${context.filesDir.absolutePath}/dlr_recheck.db"
                            val srcDb = "/data/data/com.google.android.apps.messaging/databases/bugle_db"
                            val uid = android.os.Process.myUid()
                            com.topjohnwu.superuser.Shell.cmd(
                                "cp '$srcDb' '$tmpDb' && rm -f '$tmpDb-wal' '$tmpDb-shm' && chown $uid:$uid '$tmpDb' && chmod 600 '$tmpDb'"
                            ).exec()

                            var deliveryResult: String? = null
                            var errorDetail: String? = null
                            try {
                                android.database.sqlite.SQLiteDatabase.openDatabase(
                                    tmpDb, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                                ).use { db ->
                                    db.rawQuery(
                                        "SELECT message_status FROM messages WHERE _id = ?",
                                        arrayOf(pending.bugleMessageId.toString())
                                    ).use { cursor ->
                                        if (cursor.moveToFirst()) {
                                            when (cursor.getInt(0)) {
                                                2, 13, 14 -> { deliveryResult = "DELIVERED"; errorDetail = null }
                                                11 -> { deliveryResult = "DELIVERED"; errorDetail = "SEEN/READ" }
                                                8, 5, 9 -> { deliveryResult = "ERROR"; errorDetail = "RCS send failed (bugle status=${cursor.getInt(0)})" }
                                                // 1 = still sending, don't report yet
                                            }
                                        }
                                    }
                                }
                            } finally {
                                com.topjohnwu.superuser.Shell.cmd("rm -f '$tmpDb' '$tmpDb-wal' '$tmpDb-shm' '$tmpDb-journal'").exec()
                            }

                            if (deliveryResult != null) {
                                addLog("INFO", "🔄 DLR re-check: $correlationId → $deliveryResult (bugleId=${pending.bugleMessageId})")
                                sendDeliveryResultAsync(
                                    DeliveryResult(correlationId, deliveryResult!!, errorDetail),
                                    pending.deviceToken
                                )
                                rcsSender.dlrTracker.removeResolved(listOf(pending))
                            } else {
                                addLog("INFO", "🔄 DLR re-check: $correlationId — still pending in bugle_db")
                            }
                        } else if (pending != null) {
                            // We know about it but don't have the bugle_db ID — try fallback query
                            val tmpDb = "${context.filesDir.absolutePath}/dlr_recheck.db"
                            val srcDb = "/data/data/com.google.android.apps.messaging/databases/bugle_db"
                            val uid = android.os.Process.myUid()
                            com.topjohnwu.superuser.Shell.cmd(
                                "cp '$srcDb' '$tmpDb' && rm -f '$tmpDb-wal' '$tmpDb-shm' && chown $uid:$uid '$tmpDb' && chmod 600 '$tmpDb'"
                            ).exec()

                            var deliveryResult: String? = null
                            var errorDetail: String? = null
                            try {
                                android.database.sqlite.SQLiteDatabase.openDatabase(
                                    tmpDb, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                                ).use { db ->
                                    db.rawQuery(
                                        "SELECT _id, message_status FROM messages WHERE _id > ? ORDER BY _id ASC LIMIT 1",
                                        arrayOf(pending.initialMaxId.toString())
                                    ).use { cursor ->
                                        if (cursor.moveToFirst()) {
                                            when (cursor.getInt(1)) {
                                                2, 13, 14 -> { deliveryResult = "DELIVERED" }
                                                11 -> { deliveryResult = "DELIVERED"; errorDetail = "SEEN/READ" }
                                                8, 5, 9 -> { deliveryResult = "ERROR"; errorDetail = "RCS send failed" }
                                            }
                                        }
                                    }
                                }
                            } finally {
                                com.topjohnwu.superuser.Shell.cmd("rm -f '$tmpDb' '$tmpDb-wal' '$tmpDb-shm' '$tmpDb-journal'").exec()
                            }

                            if (deliveryResult != null) {
                                addLog("INFO", "🔄 DLR re-check fallback: $correlationId → $deliveryResult")
                                sendDeliveryResultAsync(
                                    DeliveryResult(correlationId, deliveryResult!!, errorDetail),
                                    pending.deviceToken
                                )
                                rcsSender.dlrTracker.removeResolved(listOf(pending))
                            } else {
                                addLog("INFO", "🔄 DLR re-check fallback: $correlationId — still pending")
                            }
                        } else {
                            addLog("WARN", "🔄 DLR re-check: $correlationId — not found in pending tracker (already GC'd or resolved)")
                        }
                    } catch (e: Exception) {
                        addLog("ERROR", "🔄 DLR re-check failed for $correlationId: ${e.message}")
                    }
                }
            }
            body == "PIN_AUTOSTART" -> {
                CoroutineScope(Dispatchers.IO).launch {
                    addLog("INFO", "🛡️ PIN_AUTOSTART command received — re-applying device hardening")
                    try {
                        com.topjohnwu.superuser.Shell.cmd(
                            "settings put system volume_ring 0",
                            "settings put system volume_notification 0",
                            "cmd audio set-volume STREAM_RING 0 2>/dev/null",
                            "cmd audio set-volume STREAM_NOTIFICATION 0 2>/dev/null"
                        ).exec()
                        addLog("INFO", "🔇 Ringer set to SILENT via root")

                        com.topjohnwu.superuser.Shell.cmd(
                            "pm disable-user --user 0 com.miui.securitycenter/com.miui.securitycenter.update.AutoUpdateReceiver 2>/dev/null",
                            "pm disable-user --user 0 com.miui.securitycenter/com.miui.securitycenter.update.UpdateCheckJob 2>/dev/null"
                        ).exec()
                        addLog("INFO", "🛡️ MIUI Security Center auto-update disabled")

                        addLog("INFO", "✅ Device hardening re-applied successfully")
                    } catch (e: Exception) {
                        addLog("ERROR", "Failed to re-apply device hardening: ${e.message}")
                    }
                }
            }
            body.startsWith("SET_SILENT=") -> {
                CoroutineScope(Dispatchers.IO).launch {
                    val enabled = body.substringAfter("=").toBooleanStrictOrNull() ?: false
                    addLog("INFO", "🔇 Silent mode set to $enabled")
                    try {
                        if (enabled) {
                            // Use AudioManager API via the app context for reliable ringer mode change
                            try {
                                val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                                audioManager.ringerMode = android.media.AudioManager.RINGER_MODE_SILENT
                                audioManager.setStreamVolume(android.media.AudioManager.STREAM_RING, 0, 0)
                                audioManager.setStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION, 0, 0)
                                audioManager.setStreamVolume(android.media.AudioManager.STREAM_ALARM, 0, 0)
                            } catch (e: Exception) {
                                addLog("WARN", "AudioManager failed: ${e.message}, using root fallback")
                            }
                            // Root fallback + DND + disable vibration
                            com.topjohnwu.superuser.Shell.cmd(
                                "settings put system volume_ring 0",
                                "settings put system volume_notification 0",
                                "settings put system volume_alarm 0",
                                "settings put global zen_mode 2",
                                "settings put system vibrate_when_ringing 0",
                                "settings put system haptic_feedback_enabled 0",
                                "cmd notification set_dnd on 2>/dev/null"
                            ).exec()
                            addLog("INFO", "🔇 Phone set to SILENT + DND + vibration off")
                        } else {
                            try {
                                val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                                audioManager.ringerMode = android.media.AudioManager.RINGER_MODE_NORMAL
                            } catch (_: Exception) {}
                            com.topjohnwu.superuser.Shell.cmd(
                                "settings put global zen_mode 0",
                                "settings put system vibrate_when_ringing 1",
                                "settings put system haptic_feedback_enabled 1",
                                "cmd notification set_dnd off 2>/dev/null"
                            ).exec()
                            addLog("INFO", "🔔 Phone ringer + vibration restored to NORMAL")
                        }
                    } catch (e: Exception) {
                        addLog("ERROR", "Failed to set silent mode: ${e.message}")
                    }
                }
            }
            body.startsWith("SET_CALL_BLOCK=") -> {
                CoroutineScope(Dispatchers.IO).launch {
                    val enabled = body.substringAfter("=").toBooleanStrictOrNull() ?: false
                    addLog("INFO", "📵 Call blocking set to $enabled")
                    callBlockEnabled = enabled
                    if (enabled && callBlockJob == null) {
                        callBlockJob = CoroutineScope(Dispatchers.IO).launch {
                            addLog("INFO", "📵 Call blocker started")
                            while (isActive) {
                                try {
                                    val result = com.topjohnwu.superuser.Shell.cmd(
                                        "dumpsys telephony.registry | grep -i 'mCallState=1' | head -1"
                                    ).exec()
                                    val output = result.out.joinToString("")
                                    if (output.contains("mCallState=1")) {
                                        addLog("INFO", "📵 Incoming call detected — rejecting!")
                                        com.topjohnwu.superuser.Shell.cmd(
                                            "input keyevent KEYCODE_ENDCALL"
                                        ).exec()
                                        delay(1000)
                                    }
                                } catch (e: Exception) {
                                    addLog("WARN", "Call blocker check failed: ${e.message}")
                                }
                                delay(2000)
                            }
                        }
                    } else if (!enabled) {
                        callBlockJob?.cancel()
                        callBlockJob = null
                        addLog("INFO", "📵 Call blocker stopped")
                    }
                }
            }

            // ── Remote Desktop ─────────────────────────────────────────────────
            body == "START_SCREEN_STREAM" -> {
                screenStreamDeviceToken = commandTargetToken
                if (screenStreamJob?.isActive == true) {
                    addLog("INFO", "🖥️ Screen stream already running")
                    return
                }
                addLog("INFO", "🖥️ Starting screen stream (HTTP)")
                screenStreamJob = CoroutineScope(Dispatchers.IO).launch {
                    val tmpFile = "/data/local/tmp/sc_frame.png"
                    // Build the upload URL from the backend URL stored in prefs
                    val baseUrl = kotlinx.coroutines.runBlocking { prefs.getBackendUrl() } ?: ""
                    val token = screenStreamDeviceToken ?: commandTargetToken ?: ""
                    val uploadUrl = baseUrl.trimEnd('/') + "/api/screen-frame?token=" + java.net.URLEncoder.encode(token, "UTF-8")
                    addLog("INFO", "🖥️ Frame upload URL: $uploadUrl")
                    val httpClient = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                        .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    var framesSent = 0
                    while (isActive) {
                        try {
                            // 1. Capture screen to PNG file
                            com.topjohnwu.superuser.Shell.cmd("screencap -p $tmpFile").exec()

                            // 2. Read PNG, decode at half resolution, compress to JPEG
                            val file = java.io.File(tmpFile)
                            if (!file.exists() || file.length() == 0L) {
                                delay(200)
                                continue
                            }
                            val pngBytes = file.readBytes()
                            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
                            val bitmap = android.graphics.BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size, opts) ?: continue
                            val jpegStream = java.io.ByteArrayOutputStream(32768)
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 35, jpegStream)
                            bitmap.recycle()
                            val jpegBytes = jpegStream.toByteArray()
                            jpegStream.close()

                            // 3. HTTP POST raw JPEG bytes to backend
                            val requestBody = jpegBytes.toRequestBody("application/octet-stream".toMediaType())
                            val request = okhttp3.Request.Builder()
                                .url(uploadUrl)
                                .post(requestBody)
                                .build()
                            val response = httpClient.newCall(request).execute()
                            response.close()
                            framesSent++
                            if (framesSent % 30 == 1) {
                                addLog("INFO", "🖥️ Sent frame #$framesSent (${jpegBytes.size / 1024}KB)")
                            }
                        } catch (e: Exception) {
                            addLog("ERROR", "Screen frame upload failed: ${e.message}")
                            delay(1000) // back off on error
                        }
                        delay(300) // ~3 FPS
                    }
                    // Cleanup
                    try { java.io.File(tmpFile).delete() } catch (_: Exception) {}
                    addLog("INFO", "🖥️ Stream stopped after $framesSent frames")
                }
            }
            body == "STOP_SCREEN_STREAM" -> {
                screenStreamJob?.cancel()
                screenStreamJob = null
                addLog("INFO", "🖥️ Screen stream stopped")
            }
            body.startsWith("INPUT_TAP=") -> {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // Format: INPUT_TAP=x,y,screenWidth,screenHeight
                        val parts = body.substringAfter("=").split(",")
                        val clickX = parts[0].toFloat()
                        val clickY = parts[1].toFloat()
                        val viewW = parts[2].toFloat()
                        val viewH = parts[3].toFloat()
                        // Get actual device resolution
                        val wmResult = com.topjohnwu.superuser.Shell.cmd("wm size").exec()
                        val wmLine = wmResult.out.lastOrNull { it.contains("x") } ?: "1080x2400"
                        val resParts = wmLine.substringAfter(":").trim().split("x")
                        val devW = resParts[0].trim().toFloat()
                        val devH = resParts[1].trim().toFloat()
                        val tapX = (clickX / viewW * devW).toInt()
                        val tapY = (clickY / viewH * devH).toInt()
                        com.topjohnwu.superuser.Shell.cmd("input tap $tapX $tapY").exec()
                    } catch (e: Exception) {
                        addLog("ERROR", "Input tap failed: ${e.message}")
                    }
                }
            }
            body.startsWith("INPUT_SWIPE=") -> {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // Format: INPUT_SWIPE=x1,y1,x2,y2,screenWidth,screenHeight
                        val parts = body.substringAfter("=").split(",")
                        val x1 = parts[0].toFloat(); val y1 = parts[1].toFloat()
                        val x2 = parts[2].toFloat(); val y2 = parts[3].toFloat()
                        val viewW = parts[4].toFloat(); val viewH = parts[5].toFloat()
                        val wmResult = com.topjohnwu.superuser.Shell.cmd("wm size").exec()
                        val wmLine = wmResult.out.lastOrNull { it.contains("x") } ?: "1080x2400"
                        val resParts = wmLine.substringAfter(":").trim().split("x")
                        val devW = resParts[0].trim().toFloat()
                        val devH = resParts[1].trim().toFloat()
                        val sx1 = (x1 / viewW * devW).toInt()
                        val sy1 = (y1 / viewH * devH).toInt()
                        val sx2 = (x2 / viewW * devW).toInt()
                        val sy2 = (y2 / viewH * devH).toInt()
                        com.topjohnwu.superuser.Shell.cmd("input swipe $sx1 $sy1 $sx2 $sy2 300").exec()
                    } catch (e: Exception) {
                        addLog("ERROR", "Input swipe failed: ${e.message}")
                    }
                }
            }
            body.startsWith("INPUT_KEY=") -> {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val keycode = body.substringAfter("=").trim()
                        com.topjohnwu.superuser.Shell.cmd("input keyevent $keycode").exec()
                    } catch (e: Exception) {
                        addLog("ERROR", "Input keyevent failed: ${e.message}")
                    }
                }
            }
            body == "WAKE_SCREEN" -> {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        addLog("INFO", "☀️ Wake screen requested")
                        com.topjohnwu.superuser.Shell.cmd(
                            "svc power stayon true",
                            "input keyevent 26"
                        ).exec()
                        delay(800)
                        // Check if awake
                        val pwr = com.topjohnwu.superuser.Shell.cmd(
                            "dumpsys power | grep mWakefulness"
                        ).exec().out.joinToString("")
                        if (!pwr.contains("Awake")) {
                            // Second press if it toggled off
                            com.topjohnwu.superuser.Shell.cmd("input keyevent 26").exec()
                            delay(800)
                        }
                        // Swipe to dismiss lock screen
                        val wmResult = com.topjohnwu.superuser.Shell.cmd("wm size").exec()
                        val wmLine = wmResult.out.lastOrNull { it.contains("x") } ?: "1080x2400"
                        val resParts = wmLine.substringAfter(":").trim().split("x")
                        val devW = resParts[0].trim().toInt()
                        val devH = resParts[1].trim().toInt()
                        val cx = devW / 2; val by = (devH * 0.85).toInt(); val ty = (devH * 0.25).toInt()
                        com.topjohnwu.superuser.Shell.cmd("input swipe $cx $by $cx $ty 300").exec()
                        addLog("INFO", "☀️ Wake + unlock sent")
                    } catch (e: Exception) {
                        addLog("ERROR", "Wake screen failed: ${e.message}")
                    }
                }
            }

            // ── Remote Shell ───────────────────────────────────────────────────
            body.startsWith("SHELL_EXEC=") -> {
                CoroutineScope(Dispatchers.IO).launch {
                    val cmd = body.substringAfter("SHELL_EXEC=")
                    addLog("INFO", "🔧 Shell exec: $cmd")
                    try {
                        val result = com.topjohnwu.superuser.Shell.cmd(cmd).exec()
                        val stdout = result.out.joinToString("\n")
                        val stderr = result.err.joinToString("\n")
                        val output = if (stderr.isNotBlank()) "$stdout\n[stderr] $stderr" else stdout
                        val token = commandTargetToken ?: ""
                        // Escape for STOMP/JSON transport
                        val safeOutput = output
                            .replace("\\", "\\\\")
                            .replace("\"", "\\\"")
                            .replace("\n", "\\n")
                            .replace("\r", "")
                            .replace("\t", "    ")
                            .replace("\u0000", "")
                        val exitCode = if (result.isSuccess) 0 else 1
                        ws?.send("SEND\ndestination:/app/shell-result\ndeviceToken:$token\ncontent-type:application/json\n\n{\"output\":\"$safeOutput\",\"exitCode\":$exitCode,\"cmd\":\"${cmd.replace("\"", "'").take(200)}\"}\u0000")
                    } catch (e: Exception) {
                        addLog("ERROR", "Shell exec failed: ${e.message}")
                        val token = commandTargetToken ?: ""
                        ws?.send("SEND\ndestination:/app/shell-result\ndeviceToken:$token\ncontent-type:application/json\n\n{\"output\":\"Error: ${e.message?.replace("\"", "'")}\",\"exitCode\":1,\"cmd\":\"${cmd.replace("\"", "'").take(200)}\"}\u0000")
                    }
                }
            }
        }
    }

    fun sendPing(token: String) {
        ws?.send("SEND\ndestination:/app/ping\ndeviceToken:$token\n\n\u0000")
    }

    fun sendHeartbeat(heartbeat: String, token: String) {
        ws?.send("SEND\ndestination:/app/heartbeat\ndeviceToken:$token\n\n$heartbeat\u0000")
            ?: addLog("WARN", "Heartbeat skipped — no active WebSocket for Multi-SIM")
    }

    private fun sendApkUpdateStatus(status: String, token: String) {
        val body = """{"status":"$status"}"""
        ws?.send("SEND\ndestination:/app/apk.status\ndeviceToken:$token\n\n$body\u0000")
        addLog("INFO", "APK update progress broadcast: $status")
    }

    fun sendDeliveryResultAsync(result: DeliveryResult, token: String) {
        sendDeliveryResult(result, token)
    }

    private fun sendDeliveryResult(result: DeliveryResult, token: String) {
        val body = Json.encodeToString(DeliveryResult.serializer(), result)
        ws?.send("SEND\ndestination:/app/delivery.result\ndeviceToken:$token\n\n$body\u0000")
        addLog("INFO", "Delivery result sent: ${result.result} for ${result.correlationId}")
    }

    /**
     * Drain new log entries since last send. Returns entries that haven't been sent yet.
     * Filters out DLR-related logs (delivery results have their own pipeline).
     */
    fun drainNewLogs(): List<LogEntry> {
        val currentLogs = _log.value
        val newEntries = if (lastSentLogIndex < currentLogs.size) {
            currentLogs.subList(lastSentLogIndex, currentLogs.size)
        } else {
            emptyList()
        }
        lastSentLogIndex = currentLogs.size
        // Filter out DLR-related logs (they go via delivery.result channel)
        val filtered = newEntries.filter { !it.message.contains("Delivery result sent") }
        // Also drain logs from DeviceLogBus (written by RcsSender etc.)
        val busLogs = DeviceLogBus.drain().map { LogEntry(it.time, it.level, it.message) }
        return filtered + busLogs
    }

    /** Send batched device logs to backend via STOMP. */
    fun sendDeviceLogs(logs: List<LogEntry>, token: String) {
        if (logs.isEmpty()) return
        val payload = logs.map { entry ->
            val level = when (entry.level) {
                "ERROR" -> "ERROR"
                "WARN" -> "WARN"
                else -> "INFO"
            }
            val safeEvent = entry.message
                .replace("\\", "\\\\")
                .replace("\"", "'")
                .replace("\n", " ")
                .replace("\r", "")
                .replace("\t", " ")
                .replace("\u0000", "")
                .take(250)
            """{"level":"$level","event":"$safeEvent","detail":"${entry.time}"}"""
        }.joinToString(",", "[", "]")
        ws?.send("SEND\ndestination:/app/device.logs\ndeviceToken:$token\ncontent-type:application/json\n\n$payload\u0000")
    }

    fun disconnect() {
        retryJob?.cancel()
        retryJob = null
        pingJob?.cancel()
        pingJob = null
        generation++           // invalidate all pending retries
        connectionStartTime.value = null
        ws?.close(1000, "Service stopped")
        ws = null
        addLog("INFO", "Disconnected by service stop")
        // Do NOT shut down client.dispatcher.executorService — singleton!
    }
}
