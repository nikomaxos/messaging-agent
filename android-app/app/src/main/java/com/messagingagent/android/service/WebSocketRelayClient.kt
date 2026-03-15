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
    val messageText: String
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

    /** The token for the currently connecting or active session. */
    private var activeDeviceToken: String? = null
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
    fun connect(backendUrl: String, deviceToken: String, deviceId: Long, onStatus: (String) -> Unit) {
        statusCallback = onStatus
        activeDeviceToken = deviceToken
        activeBackendUrl = backendUrl

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
            .addHeader("deviceToken", deviceToken)
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
                webSocket.send("CONNECT\naccept-version:1.2\nheart-beat:0,0\ndeviceToken:$deviceToken\n\n\u0000")
                webSocket.send("SUBSCRIBE\nid:sub-0\ndestination:/queue/sms.$deviceId\n\n\u0000")
                webSocket.send("SUBSCRIBE\nid:sub-1\ndestination:/queue/commands.$deviceId\n\n\u0000")
                
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
                scheduleRetry(backendUrl, deviceToken, deviceId, onStatus, myGen)
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
                scheduleRetry(backendUrl, deviceToken, deviceId, onStatus, myGen)
            }
        })
    }

    private fun scheduleRetry(backendUrl: String, deviceToken: String, deviceId: Long, onStatus: (String) -> Unit, myGen: Int) {
        retryJob = CoroutineScope(Dispatchers.IO).launch {
            addLog("INFO", "Will retry in 8s…")
            delay(8_000)
            if (generation == myGen) {          // still the active generation
                addLog("INFO", "Retrying connection…")
                connect(backendUrl, deviceToken, deviceId, onStatus)
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
            handleSysCommand(body)
            return
        }

        try {
            val dispatch = json.decodeFromString<SmsDispatch>(body)
            CoroutineScope(Dispatchers.IO).launch {
                val result = rcsSender.sendRcs(
                    to = dispatch.destinationAddress,
                    text = dispatch.messageText
                )
                sendDeliveryResult(DeliveryResult(
                    correlationId = dispatch.correlationId,
                    result = if (result.success) "DELIVERED" else if (result.noRcs) "NO_RCS" else "ERROR",
                    errorDetail = result.error
                ))
            }
        } catch (e: Exception) {
            addLog("ERROR", "Failed to parse dispatch: ${e.message}")
        }
    }

    private fun handleSysCommand(body: String) {
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
                        sendApkUpdateStatus("Downloading…")
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
                            
                            sendApkUpdateStatus("Installing…")
                            addLog("INFO", "APK downloaded. Preparing install...")
                            // pm install often drops root privileges so it can't read from app cache directly.
                            // We move it to /data/local/tmp which is readable by the shell user.
                            com.topjohnwu.superuser.Shell.cmd(
                                "cp ${apkFile.absolutePath} /data/local/tmp/update.apk",
                                "chmod 666 /data/local/tmp/update.apk"
                            ).exec()

                            val result = com.topjohnwu.superuser.Shell.cmd("pm install -r /data/local/tmp/update.apk").exec()
                            com.topjohnwu.superuser.Shell.cmd("rm /data/local/tmp/update.apk").exec()
                            
                            if (result.isSuccess) {
                                addLog("INFO", "APK installed. Launching app and rebooting service...")
                                // Forcefully launch the app's main activity in the background so it starts the service
                                com.topjohnwu.superuser.Shell.cmd("am start -n com.messagingagent.android/.ui.SetupActivity").exec()
                                ws?.close(1000, "APK Update complete, restarting")
                            } else {
                                sendApkUpdateStatus("Failed: install error")
                                addLog("ERROR", "Install failed: ${result.err.joinToString()}")
                            }
                        } else {
                            sendApkUpdateStatus("Failed: download error")
                            addLog("ERROR", "APK download failed: HTTP ${response.code}")
                        }
                    } catch (e: Exception) {
                        sendApkUpdateStatus("Failed: error")
                        addLog("ERROR", "APK update error: ${e.message}")
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
        }
    }

    fun sendHeartbeat(heartbeat: String) {
        val token = activeDeviceToken ?: return
        ws?.send("SEND\ndestination:/app/heartbeat\ndeviceToken:$token\n\n$heartbeat\u0000")
            ?: addLog("WARN", "Heartbeat skipped — no active WebSocket")
    }

    private fun sendApkUpdateStatus(status: String) {
        val token = activeDeviceToken ?: return
        val body = """{"status":"$status"}"""
        ws?.send("SEND\ndestination:/app/apk.status\ndeviceToken:$token\n\n$body\u0000")
        addLog("INFO", "APK update progress broadcast: $status")
    }

    private fun sendDeliveryResult(result: DeliveryResult) {
        val token = activeDeviceToken ?: return
        val body = Json.encodeToString(DeliveryResult.serializer(), result)
        ws?.send("SEND\ndestination:/app/delivery.result\ndeviceToken:$token\n\n$body\u0000")
        addLog("INFO", "Delivery result sent: ${result.result} for ${result.correlationId}")
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
