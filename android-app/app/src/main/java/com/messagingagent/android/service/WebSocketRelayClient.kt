package com.messagingagent.android.service

import com.messagingagent.android.rcs.RcsSender
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.*
import timber.log.Timber
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

/**
 * WebSocket STOMP relay client.
 *
 * Connects to the backend WebSocket endpoint using raw OkHttp WebSocket
 * (upgraded to STOMP manually for simplicity; can be swapped to Krossbow).
 * Subscribes to /queue/sms.{deviceId} and sends heartbeats/delivery results.
 */
@Singleton
class WebSocketRelayClient @Inject constructor(
    private val rcsSender: RcsSender
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private val json = Json { ignoreUnknownKeys = true }
    private var statusCallback: ((String) -> Unit)? = null

    private var stopped = false
    private var retryJob: kotlinx.coroutines.Job? = null

    fun connect(backendUrl: String, deviceToken: String, onStatus: (String) -> Unit) {
        stopped = false
        statusCallback = onStatus
        val wsUrl = backendUrl.replace("http", "ws") + "/ws"
        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("deviceToken", deviceToken)
            .build()

        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                retryJob?.cancel()
                Timber.i("WebSocket connected to $wsUrl")
                onStatus("Connected to backend")
                // STOMP CONNECT frame
                webSocket.send("CONNECT\naccept-version:1.2\ndeviceToken:$deviceToken\n\n\u0000")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Timber.d("WS message: $text")
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                onStatus("Disconnecting…")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (stopped) return
                Timber.e(t, "WebSocket failure")
                onStatus("Disconnected — retrying…")
                retryJob = CoroutineScope(Dispatchers.IO).launch {
                    delay(5_000)
                    if (!stopped) connect(backendUrl, deviceToken, onStatus)
                }
            }
        })
    }

    private fun handleMessage(text: String) {
        // Minimal STOMP frame parse — look for JSON body
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
            Timber.e(e, "Failed to parse dispatch")
        }
    }

    fun sendHeartbeat(heartbeat: String) {
        // STOMP SEND to /app/heartbeat
        ws?.send("SEND\ndestination:/app/heartbeat\n\n$heartbeat\u0000")
    }

    private fun sendDeliveryResult(result: DeliveryResult) {
        val body = Json.encodeToString(DeliveryResult.serializer(), result)
        ws?.send("SEND\ndestination:/app/delivery.result\n\n$body\u0000")
        Timber.i("Sent delivery result: ${result.result} for ${result.correlationId}")
    }

    fun disconnect() {
        stopped = true
        retryJob?.cancel()
        ws?.close(1000, "Service stopped")
        ws = null
        // Do NOT shut down client.dispatcher.executorService — this is a singleton
        // and shutting down the executor permanently breaks future connect() calls
        // when the service is restarted by Android (START_STICKY).
    }
}
