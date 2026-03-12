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
    private val rcsSender: RcsSender
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

    /** Pending retry job (cancel on new connect). */
    private var retryJob: Job? = null

    /** STOMP periodic ping job - removed since Spring closes invalid frames */

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
    fun connect(backendUrl: String, deviceToken: String, onStatus: (String) -> Unit) {
        statusCallback = onStatus
        activeDeviceToken = deviceToken

        // Close any existing connection cleanly before opening a new one.
        // This prevents the old WebSocket's onFailure from triggering a stale retry.
        retryJob?.cancel()
        retryJob = null
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
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (generation != myGen) return
                val preview = text.take(120).replace('\n', '↵')
                addLog("MSG", preview)
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (generation != myGen) return
                addLog("WARN", "WebSocket closing: code=$code reason=$reason")
                webSocket.close(1000, null)
                onStatus("Disconnecting…")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (generation != myGen) return
                connectionStartTime.value = null
                addLog("WARN", "WebSocket closed: code=$code reason=$reason")
                onStatus("Offline")
                scheduleRetry(backendUrl, deviceToken, onStatus, myGen)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (generation != myGen) {
                    addLog("DEBUG", "Stale onFailure ignored (gen=$myGen != current=$generation): ${t.message}")
                    return
                }
                connectionStartTime.value = null
                val httpCode = response?.code?.toString() ?: "no-response"
                addLog("ERROR", "WebSocket failure [HTTP $httpCode]: ${t.javaClass.simpleName}: ${t.message}")
                onStatus("Disconnected — retrying…")
                scheduleRetry(backendUrl, deviceToken, onStatus, myGen)
            }
        })
    }

    private fun scheduleRetry(backendUrl: String, deviceToken: String, onStatus: (String) -> Unit, myGen: Int) {
        retryJob = CoroutineScope(Dispatchers.IO).launch {
            addLog("INFO", "Will retry in 8s…")
            delay(8_000)
            if (generation == myGen) {          // still the active generation
                addLog("INFO", "Retrying connection…")
                connect(backendUrl, deviceToken, onStatus)
            } else {
                addLog("DEBUG", "Retry cancelled — superseded by newer connect()")
            }
        }
    }

    private fun handleMessage(text: String) {
        if (!text.startsWith("MESSAGE")) return
        val body = text.substringAfter("\n\n").trimEnd('\u0000')
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

    fun sendHeartbeat(heartbeat: String) {
        val token = activeDeviceToken ?: return
        ws?.send("SEND\ndestination:/app/heartbeat\ndeviceToken:$token\n\n$heartbeat\u0000")
            ?: addLog("WARN", "Heartbeat skipped — no active WebSocket")
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
        generation++           // invalidate all pending retries
        connectionStartTime.value = null
        ws?.close(1000, "Service stopped")
        ws = null
        addLog("INFO", "Disconnected by service stop")
        // Do NOT shut down client.dispatcher.executorService — singleton!
    }
}
