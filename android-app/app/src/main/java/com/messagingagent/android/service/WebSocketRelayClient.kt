package com.messagingagent.android.service

import com.messagingagent.android.rcs.RcsSender
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.*
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

    private fun addLog(level: String, message: String) {
        val entry = LogEntry(timeFmt.format(Date()), level, message)
        val current = _log.value.takeLast(49)   // keep last 50 entries
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

                // Flush any buffered logs accumulated while offline
                val bufferedLogs = drainNewLogs()
                if (bufferedLogs.isNotEmpty()) {
                    addLog("INFO", "Flushing ${bufferedLogs.size} buffered logs from offline period")
                    sendDeviceLogs(bufferedLogs, baseToken)
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
                            
                            val hasRoot = com.topjohnwu.superuser.Shell.isAppGrantedRoot() == true
                            if (hasRoot) {
                                // "pm install -r" FORCE KILLS the app process! 
                                // We MUST run it as a detached background script so it finishes and restarts us.
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
                                
                                com.topjohnwu.superuser.Shell.cmd(
                                    "nohup sh \"${scriptFile.absolutePath}\" >> /data/local/tmp/ota.log 2>&1 &"
                                ).exec()
                                
                                addLog("INFO", "Root update script deployed. App will restart momentarily.")
                                ws?.close(1000, "Applying OTA Update")
                            } else {
                                addLog("INFO", "Root not granted. Opening Android Package Installer...")
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
            body.startsWith("SET_AUTO_PURGE=") -> {
                CoroutineScope(Dispatchers.IO).launch {
                    val mode = body.substringAfter("=").uppercase()
                    prefs.setAutoPurgeMode(mode)
                    addLog("INFO", "Auto-Purge mode set to $mode")
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
        return newEntries.filter { !it.message.contains("Delivery result sent") }
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
            """{"level":"$level","event":"${entry.message.replace("\"", "'").take(250)}","detail":"${entry.time}"}"""
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
