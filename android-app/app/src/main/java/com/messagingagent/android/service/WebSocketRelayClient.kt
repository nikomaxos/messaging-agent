package com.messagingagent.android.service

import com.messagingagent.android.rcs.RcsSender
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

data class MatrixTracker(val destination: String, val text: String, val expiration: Long)

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
    private val prefs: com.messagingagent.android.data.PreferencesRepository
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(25, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private val json = Json { ignoreUnknownKeys = true }
    private var statusCallback: ((String) -> Unit)? = null

    private val matrixTrackers = java.util.concurrent.ConcurrentHashMap<String, MatrixTracker>()

    /** Monotonically increasing generation — stale retries from old sockets are ignored. */
    @Volatile private var generation = 0
    private var matrixTrackerJob: Job? = null

    /** The active registration state */
    private var activeState: com.messagingagent.android.data.RegistrationState? = null
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
    fun connect(backendUrl: String, regState: com.messagingagent.android.data.RegistrationState, onStatus: (String) -> Unit) {
        statusCallback = onStatus
        activeState = regState
        activeBackendUrl = backendUrl

        if (regState.deviceToken == null || regState.deviceId == null) {
            addLog("WARN", "No registered device Token to connect")
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

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Pre-flight check to prevent "Zombie" state where the backend has forgotten the device token
                val statusUrl = "${backendUrl.trimEnd('/')}/api/devices/register/status/${regState.deviceToken}"
                val statusReq = Request.Builder().url(statusUrl).build()
                val response = client.newCall(statusReq).execute()
                
                if (response.code == 404) {
                    addLog("ERROR", "Token orphaned (HTTP 404)! Attempting headless auto-recovery...")
                    
                    if (regState.groupId != null) {
                        val regUrl = "${backendUrl.trimEnd('/')}/api/devices/register"
                        val hardwareId = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "UNKNOWN_${System.currentTimeMillis()}"
                        
                        // We do not submit new sims during blind recovery. RegistrationController will respect hardwareId without sims payload.
                        val reqBodyStr = """{"deviceName":"${regState.deviceName}","hardwareId":"$hardwareId","groupId":${regState.groupId},"simCards":[]}"""
                        val regReq = Request.Builder().url(regUrl)
                            .post(reqBodyStr.toRequestBody("application/json".toMediaType()))
                            .build()
                            
                        val regResp = client.newCall(regReq).execute()
                        if (regResp.isSuccessful) {
                            val bodyStr = regResp.body?.string() ?: ""
                            val jsonObj = org.json.JSONObject(bodyStr)
                            val newToken = jsonObj.getString("token")
                            val newDeviceId = jsonObj.getLong("deviceId")
                            
                            prefs.setRegistrationResult(newDeviceId, newToken, regState.sims, regState.groupName ?: "", regState.groupId)
                            
                            addLog("INFO", "Auto-recovered! Reconnecting with fresh token...")
                            withContext(Dispatchers.Main) {
                                val newState = prefs.registrationFlow().first()
                                connect(backendUrl, newState, onStatus)
                            }
                            return@launch
                        } else {
                            addLog("ERROR", "Headless auto-recovery failed: HTTP ${regResp.code}")
                        }
                    } else {
                        addLog("ERROR", "Cannot auto-recover: Group ID is null in preferences")
                    }
                }
            } catch (e: Exception) {
                // Ignore transient network errors during pre-flight; let the main WS loop handle retries
            }

            if (generation != myGen) return@launch // stale

            val request = Request.Builder()
                .url(wsUrl)
                .addHeader("deviceToken", regState.deviceToken!!)
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
                val baseToken = regState.deviceToken!!
                webSocket.send("CONNECT\naccept-version:1.2\nheart-beat:0,0\ndeviceToken:$baseToken\n\n\u0000")
                
                // We subscribe exactly ONE TIME to the queues for the device entity.
                val devId = regState.deviceId!!
                webSocket.send("SUBSCRIBE\nid:sub-sms-0\ndestination:/queue/sms.$devId\n\n\u0000")
                webSocket.send("SUBSCRIBE\nid:sub-cmd-0\ndestination:/queue/commands.$devId\n\n\u0000")
                
                pingJob = CoroutineScope(Dispatchers.IO).launch {
                    while (isActive) {
                        delay(20_000)
                        try {
                            if (generation == myGen) webSocket.send("\n")
                        } catch (e: Exception) { /* ignored */ }
                    }
                }
                
                matrixTrackerJob = CoroutineScope(Dispatchers.IO).launch {
                    val baseToken = regState.deviceToken!!
                    var lastExecutionFailed = false
                    while (isActive) {
                        delay(2_000) // Poll every 2 seconds for faster DLR failover
                        try {
                            if (generation != myGen) break
                            val apkPathRes = com.topjohnwu.superuser.Shell.cmd("pm path com.messagingagent.android").exec()
                            val apkPath = apkPathRes.out.firstOrNull()?.substringAfter("package:")?.trim() ?: continue
                            // We look back 30 seconds to capture any recent updates while avoiding overlaps overlapping too far 
                            val timeCutoff = System.currentTimeMillis() - 30_000 
                            val res = com.topjohnwu.superuser.Shell.cmd("CLASSPATH=\$apkPath app_process /system/bin com.messagingagent.android.bot.BugleDbQuery \$timeCutoff").exec()
                            
                            val stdout = res.out.joinToString("\n").trim()
                            if (stdout.startsWith("ERROR|")) {
                                if (!lastExecutionFailed) {
                                    addLog("ERROR", "FastTrack native failure: \$stdout")
                                    lastExecutionFailed = true
                                }
                            } else if (stdout.isNotBlank() && !stdout.startsWith("EMPTY|")) {
                                lastExecutionFailed = false
                                val b64data = android.util.Base64.encodeToString(stdout.toByteArray(), android.util.Base64.NO_WRAP)
                                sendBulkDlrResult(b64data, baseToken)
                                
                                // Local Tracking verification for MATRIX dispatches
                                val lines = stdout.split("\n")
                                val expired = mutableListOf<String>()
                                val delivered = mutableListOf<String>()
                                for ((corrId, tracker) in matrixTrackers) {
                                    if (System.currentTimeMillis() > tracker.expiration) {
                                        expired.add(corrId)
                                        continue
                                    }
                                    for (line in lines) {
                                        if (line.isBlank()) continue
                                        val parts = line.split("|", limit = 3)
                                        if (parts.size < 3) continue
                                        val dest = parts[0].replace("[^0-9+]".toRegex(), "")
                                        val statusId = parts[1].toIntOrNull() ?: continue
                                        val text = String(android.util.Base64.decode(parts[2], android.util.Base64.NO_WRAP), Charsets.UTF_8)
                                        
                                        if (dest.endsWith(tracker.destination) || tracker.destination.endsWith(dest)) {
                                            if (text.contains(tracker.text)) {
                                                if (statusId == 11 || statusId == 12 || statusId == 13) {
                                                    sendDeliveryResult(DeliveryResult(corrId, "DELIVERED"), baseToken)
                                                    delivered.add(corrId)
                                                    break
                                                } else if (statusId == 8 || statusId == 9) {
                                                    sendDeliveryResult(DeliveryResult(corrId, "ERROR", "Native Bugle Status: \$statusId"), baseToken)
                                                    delivered.add(corrId)
                                                    break
                                                }
                                            }
                                        }
                                    }
                                }
                                expired.forEach { matrixTrackers.remove(it) }
                                delivered.forEach { matrixTrackers.remove(it) }
                            }
                        } catch (e: Exception) {
                            if (!lastExecutionFailed) {
                                addLog("ERROR", "FastTrack loop exception: \${e.message}")
                                lastExecutionFailed = true
                            }
                        }
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
                matrixTrackerJob?.cancel()
                addLog("WARN", "WebSocket closing: code=$code reason=$reason")
                webSocket.close(1000, null)
                onStatus("Disconnecting…")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (generation != myGen) return
                pingJob?.cancel()
                matrixTrackerJob?.cancel()
                connectionStartTime.value = null
                addLog("WARN", "WebSocket closed: code=$code reason=$reason")
                onStatus("Offline")
                scheduleRetry(backendUrl, regState, onStatus, myGen)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (generation != myGen) {
                    addLog("DEBUG", "Stale onFailure ignored (gen=$myGen != current=$generation): ${t.message}")
                    return
                }
                pingJob?.cancel()
                matrixTrackerJob?.cancel()
                connectionStartTime.value = null
                val httpCode = response?.code?.toString() ?: "no-response"
                addLog("ERROR", "WebSocket failure [HTTP $httpCode]: ${t.javaClass.simpleName}: ${t.message}")
                onStatus("Disconnected — retrying…")
            }
        })
        }
    }

    private fun scheduleRetry(backendUrl: String, regState: com.messagingagent.android.data.RegistrationState, onStatus: (String) -> Unit, myGen: Int) {
        retryJob = CoroutineScope(Dispatchers.IO).launch {
            addLog("INFO", "Will retry in 8s…")
            delay(8_000)
            if (generation == myGen) {          // still the active generation
                addLog("INFO", "Retrying connection…")
                connect(backendUrl, regState, onStatus)
            } else {
                addLog("DEBUG", "Retry cancelled — superseded by newer connect()")
            }
        }
    }

    private fun handleMessage(text: String) {
        if (!text.startsWith("MESSAGE")) return
        val destMatch = Regex("""destination:(.+)""").find(text)?.groupValues?.get(1)
        val bodyMatch = Regex("""\r?\n\r?\n([\s\S]*)""").find(text)
        val body = (bodyMatch?.groupValues?.get(1) ?: text).trimEnd('\u0000').trim()
        if (destMatch?.startsWith("/queue/commands.") == true) {
            handleSysCommand(body, activeState?.deviceToken)
            return
        }

        try {
            val dispatch = json.decodeFromString<SmsDispatch>(body)
            // Fire robust Intent to the isolated :bot execution process
            val botIntent = android.content.Intent(context, com.messagingagent.android.bot.BotService::class.java).apply {
                action = "com.messagingagent.android.bot.ACTION_SEND"
                putExtra("correlationId", dispatch.correlationId)
                putExtra("deviceToken", activeState?.deviceToken)
                putExtra("to", dispatch.destinationAddress)
                putExtra("text", dispatch.messageText)
                
                // Which subId to route to depends on the backend finding out during route phase via SimCard settings.
                // We default to -1 (default SMS sim) unless specified. Future improvement: pass slot from backend payload.
                putExtra("subscriptionId", -1)
                
                putExtra("dlrDelayMinSec", dispatch.dlrDelayMinSec)
                putExtra("dlrDelayMaxSec", dispatch.dlrDelayMaxSec)
            }
            context.startService(botIntent)
            addLog("INFO", "Forwarded request ${dispatch.correlationId} to BotService (:bot) via IPC")
        } catch (e: Exception) {
            addLog("ERROR", "Failed to parse dispatch: ${e.message}")
        }
    }

    private fun handleSysCommand(body: String, commandTargetToken: String?) {
        addLog("INFO", "Received system command: $body")
        when {
            body == "REFRESH_LOCATION" -> {
                MessagingAgentService.forceLocationRefresh = true
                addLog("INFO", "Location refresh requested by admin, next heartbeat will include GPS")
            }
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
                        if (commandTargetToken != null) sendApkUpdateStatus("Handing off to Guardian...", commandTargetToken)
                        addLog("INFO", "Delegating APK update to Guardian App...")
                        
                        val intent = android.content.Intent("com.messagingagent.guardian.ACTION_TRIGGER_OTA").apply {
                            putExtra("url", "${url.trimEnd('/')}/api/public/apk/download")
                            addFlags(android.content.Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                            setPackage("com.messagingagent.guardian")
                        }
                        context.sendBroadcast(intent)
                        addLog("INFO", "Update Broadcast successfully queued to Guardian")
                    } catch (e: Exception) {
                        addLog("ERROR", "Guardian handover failed: ${e.message}")
                    }
                }
            }
            body == "UPDATE_GUARDIAN" -> {
                val urlPrefix = activeBackendUrl ?: return
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        addLog("INFO", "Self-Healing: Downloading latest Guardian App...")
                        
                        val client = okhttp3.OkHttpClient()
                        val request = okhttp3.Request.Builder().url("${urlPrefix.trimEnd('/')}/api/public/guardian/download").build()
                        val response = client.newCall(request).execute()
                        
                        if (response.isSuccessful) {
                            val apkBytes = response.body?.bytes()
                            if (apkBytes != null) {
                                val apkFile = java.io.File(context.cacheDir, "guardian_update.apk")
                                java.io.FileOutputStream(apkFile).use { it.write(apkBytes) }
                                
                                addLog("INFO", "Installing Guardian securely via su...")
                                val res = com.topjohnwu.superuser.Shell.cmd("pm install -r ${apkFile.absolutePath}").exec()
                                
                                if (res.isSuccess || res.out.joinToString("\n").contains("Success")) {
                                    addLog("INFO", "Guardian App Successfully Updated!")
                                } else {
                                    addLog("ERROR", "Guardian App Install Failed: ${res.err} | ${res.out}")
                                }
                            }
                        } else {
                            addLog("ERROR", "Failed to download Guardian: HTTP ${response.code}")
                        }
                    } catch (e: Exception) {
                        addLog("ERROR", "Self-Healing Exception: ${e.message}")
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
                val correlationId = body.substringAfter("=").trim()
                addLog("INFO", "🔄 DLR re-check forwarded to BotService for $correlationId")
                val botIntent = android.content.Intent(context, com.messagingagent.android.bot.BotService::class.java).apply {
                    action = "com.messagingagent.android.bot.ACTION_RECHECK_DLR"
                    putExtra("correlationId", correlationId)
                    putExtra("deviceToken", commandTargetToken ?: "")
                }
                context.startService(botIntent)
            }
            body.startsWith("SYNC_MATRIX_BULK_DLR=") -> {
                val mins = body.substringAfter("=").trim().toIntOrNull() ?: 180
                // Don't log this to the UI to avoid flooding
                val botIntent = android.content.Intent(context, com.messagingagent.android.bot.BotService::class.java).apply {
                    action = "com.messagingagent.android.bot.ACTION_SYNC_MATRIX_BULK"
                    putExtra("minutes", mins)
                    putExtra("deviceToken", commandTargetToken ?: "")
                }
                context.startService(botIntent)
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

                        com.topjohnwu.superuser.Shell.cmd(
                            "dumpsys deviceidle whitelist +com.messagingagent.android",
                            "cmd appops set com.messagingagent.android AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore 2>/dev/null",
                            "cmd appops set com.messagingagent.android 10008 ignore 2>/dev/null", // MIUI Autostart
                            "cmd appops set com.messagingagent.android AUTO_START ignore 2>/dev/null",
                            "cmd appops set com.messagingagent.android 10020 ignore 2>/dev/null", // MIUI Background Start
                            "cmd appops set com.messagingagent.android RUN_IN_BACKGROUND allow 2>/dev/null"
                        ).exec()
                        addLog("INFO", "🛡️ Enforced AppOps policies: Autostart, Battery No-Restrictions, and Disabled Permission Revocation")

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
            body.startsWith("TRACK_DLR_ONLY=") -> {
                try {
                    val parts = body.substringAfter("=").split("|", limit = 3)
                    if (parts.size == 3) {
                        val corrId = parts[0]
                        val dest = parts[1].replace("[^0-9+]".toRegex(), "")
                        val textStr = String(android.util.Base64.decode(parts[2], android.util.Base64.NO_WRAP), Charsets.UTF_8)
                        addLog("INFO", "Tracking MATRIX delivery locally for \$corrId")
                        matrixTrackers[corrId] = MatrixTracker(dest, textStr, System.currentTimeMillis() + 4 * 3600_000L) // 4 hr expiration
                    }
                } catch (e: Exception) {
                    addLog("ERROR", "Failed to parse TRACK_DLR_ONLY: \${e.message}")
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
                    // Persistent HTTP client with connection keep-alive for faster uploads
                    val httpClient = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                        .writeTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                        .connectionPool(okhttp3.ConnectionPool(1, 30, java.util.concurrent.TimeUnit.SECONDS))
                        .build()
                    // Reusable JPEG buffer to reduce GC pressure
                    val jpegStream = java.io.ByteArrayOutputStream(65536)
                    var framesSent = 0
                    while (isActive) {
                        try {
                            // 1. Capture screen as RAW (no PNG encoding = 2.6x faster)
                            val rawFile = "/data/local/tmp/sc_raw"
                            com.topjohnwu.superuser.Shell.cmd("screencap $rawFile").exec()

                            // 2. Read raw RGBA pixels directly into Bitmap (skip PNG encode+decode)
                            val file = java.io.File(rawFile)
                            if (!file.exists() || file.length() < 16) { delay(100); continue }

                            val raf = java.io.RandomAccessFile(file, "r")
                            try {
                                // Raw header: width(4) + height(4) + format(4) = 12 bytes
                                val header = ByteArray(12)
                                raf.readFully(header)
                                val buf = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                val rawW = buf.getInt()
                                val rawH = buf.getInt()
                                // Skip format (4 bytes already read)

                                val pixelCount = rawW * rawH
                                val expectedSize = (pixelCount * 4).toLong()
                                val filePixelSize = file.length() - 12

                                if (filePixelSize < expectedSize || rawW <= 0 || rawH <= 0 || rawW > 4096 || rawH > 8192) {
                                    // Invalid raw data — fallback to PNG
                                    raf.close()
                                    com.topjohnwu.superuser.Shell.cmd("screencap -p $tmpFile").exec()
                                    val pngFile = java.io.File(tmpFile)
                                    if (!pngFile.exists() || pngFile.length() == 0L) { delay(100); continue }
                                    val pngBytes = pngFile.readBytes()
                                    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 3 }
                                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size, opts) ?: continue
                                    jpegStream.reset()
                                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 40, jpegStream)
                                    bitmap.recycle()
                                } else {
                                    // Read raw RGBA pixels
                                    val pixelBytes = ByteArray(expectedSize.toInt())
                                    raf.readFully(pixelBytes)
                                    raf.close()

                                    // Create full-size Bitmap from raw pixels
                                    val fullBitmap = android.graphics.Bitmap.createBitmap(rawW, rawH, android.graphics.Bitmap.Config.ARGB_8888)
                                    fullBitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(pixelBytes))

                                    // Scale down to 1/3 for fast JPEG compression
                                    val scaledW = rawW / 3
                                    val scaledH = rawH / 3
                                    val scaledBitmap = android.graphics.Bitmap.createScaledBitmap(fullBitmap, scaledW, scaledH, false)
                                    fullBitmap.recycle()

                                    jpegStream.reset()
                                    scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 40, jpegStream)
                                    scaledBitmap.recycle()
                                }
                            } catch (rawEx: Exception) {
                                try { raf.close() } catch (_: Exception) {}
                                addLog("WARN", "Raw capture fallback to PNG: ${rawEx.message}")
                                continue
                            }
                            val jpegBytes = jpegStream.toByteArray()

                            // 3. HTTP POST raw JPEG bytes to backend (no delay — capture is naturally rate-limited)
                            val requestBody = jpegBytes.toRequestBody("application/octet-stream".toMediaType())
                            val request = okhttp3.Request.Builder()
                                .url(uploadUrl)
                                .post(requestBody)
                                .build()
                            val response = httpClient.newCall(request).execute()
                            response.close()
                            framesSent++
                            if (framesSent % 50 == 1) {
                                addLog("INFO", "🖥️ Sent frame #$framesSent (${jpegBytes.size / 1024}KB)")
                            }
                        } catch (e: Exception) {
                            addLog("ERROR", "Screen frame upload failed: ${e.message}")
                            delay(500) // brief back-off on error
                        }
                        // No artificial delay — screencap itself takes ~800ms-1.2s
                    }
                    // Cleanup
                    try { java.io.File(tmpFile).delete() } catch (_: Exception) {}
                    jpegStream.close()
                    httpClient.connectionPool.evictAll()
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
            body.startsWith("INPUT_LONG_PRESS=") -> {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // Format: INPUT_LONG_PRESS=x,y,screenWidth,screenHeight
                        val parts = body.substringAfter("=").split(",")
                        val clickX = parts[0].toFloat()
                        val clickY = parts[1].toFloat()
                        val viewW = parts[2].toFloat()
                        val viewH = parts[3].toFloat()
                        val wmResult = com.topjohnwu.superuser.Shell.cmd("wm size").exec()
                        val wmLine = wmResult.out.lastOrNull { it.contains("x") } ?: "1080x2400"
                        val resParts = wmLine.substringAfter(":").trim().split("x")
                        val devW = resParts[0].trim().toFloat()
                        val devH = resParts[1].trim().toFloat()
                        val tapX = (clickX / viewW * devW).toInt()
                        val tapY = (clickY / viewH * devH).toInt()
                        // Long press = swipe to same point with 1000ms duration
                        com.topjohnwu.superuser.Shell.cmd("input swipe $tapX $tapY $tapX $tapY 1000").exec()
                    } catch (e: Exception) {
                        addLog("ERROR", "Input long press failed: ${e.message}")
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
            body.startsWith("INPUT_DRAG=") -> {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // Format: INPUT_DRAG=x1,y1,x2,y2,screenWidth,screenHeight,duration
                        val parts = body.substringAfter("=").split(",")
                        val x1 = parts[0].toFloat(); val y1 = parts[1].toFloat()
                        val x2 = parts[2].toFloat(); val y2 = parts[3].toFloat()
                        val viewW = parts[4].toFloat(); val viewH = parts[5].toFloat()
                        val duration = parts.getOrNull(6)?.toIntOrNull() ?: 2000
                        val wmResult = com.topjohnwu.superuser.Shell.cmd("wm size").exec()
                        val wmLine = wmResult.out.lastOrNull { it.contains("x") } ?: "1080x2400"
                        val resParts = wmLine.substringAfter(":").trim().split("x")
                        val devW = resParts[0].trim().toFloat()
                        val devH = resParts[1].trim().toFloat()
                        val dx1 = (x1 / viewW * devW).toInt()
                        val dy1 = (y1 / viewH * devH).toInt()
                        val dx2 = (x2 / viewW * devW).toInt()
                        val dy2 = (y2 / viewH * devH).toInt()
                        // Long-duration swipe = drag (triggers Android drag-and-drop on home screen)
                        com.topjohnwu.superuser.Shell.cmd("input swipe $dx1 $dy1 $dx2 $dy2 $duration").exec()
                    } catch (e: Exception) {
                        addLog("ERROR", "Input drag failed: ${e.message}")
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

    internal fun sendDeliveryResult(result: DeliveryResult, token: String) {
        val body = Json.encodeToString(DeliveryResult.serializer(), result)
        ws?.send("SEND\ndestination:/app/delivery.result\ndeviceToken:$token\n\n$body\u0000")
        addLog("INFO", "Delivery result sent: ${result.result} for ${result.correlationId}")
    }
    
    internal fun sendBulkDlrResult(b64data: String, token: String) {
        val body = """{"bulkDataBase64":"$b64data"}"""
        // Broadcast directly to the new bulk endpoint
        ws?.send("SEND\ndestination:/app/delivery.bulk\ndeviceToken:$token\n\n$body\u0000")
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
