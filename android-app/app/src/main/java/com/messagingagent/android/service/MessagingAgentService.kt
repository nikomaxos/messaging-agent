package com.messagingagent.android.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.messagingagent.android.R
import com.messagingagent.android.data.PreferencesRepository
import com.messagingagent.android.ui.SetupActivity
import com.messagingagent.android.receiver.AutoPurgeReceiver
import java.util.Calendar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import com.messagingagent.android.data.SimRegistration
import com.messagingagent.android.data.RegistrationState
import com.messagingagent.android.rcs.PendingDlr
import com.messagingagent.android.rcs.PendingDlrTracker
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject

@Serializable
data class HeartbeatPayload(
    val batteryPercent: Int?,
    val isCharging: Boolean,
    val wifiSignalDbm: Int?,
    val gsmSignalDbm: Int?,
    val gsmSignalAsu: Int?,
    val networkOperator: String?,
    val rcsCapable: Boolean,
    val activeNetworkType: String?,
    val apkVersion: String?
)

/**
 * Foreground service that maintains the persistent WebSocket (STOMP) connection
 * to the backend and relays SMS dispatch commands to the RCS sender.
 *
 * Lifecycle:
 *  - Started on boot / app launch
 *  - Shows a persistent notification with connection status
 *  - Restarts automatically if killed by OS (START_STICKY)
 */
@AndroidEntryPoint
class MessagingAgentService : Service() {

    @Inject lateinit var prefs: PreferencesRepository
    @Inject lateinit var wsClient: WebSocketRelayClient
    @Inject lateinit var dlrTracker: PendingDlrTracker

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val CHANNEL_ID = "messaging_agent_service"
    private val NOTIFICATION_ID = 1001

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification("Connecting to backend…")
        startForeground(NOTIFICATION_ID, notification)
        scheduleAutoPurgeAlarm()
        Timber.i("MessagingAgentService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            val regState = prefs.registrationFlow().first()
            val backendUrl = regState.backendUrl ?: run {
                Timber.w("No backend URL configured — service idle")
                return@launch
            }
            if (!regState.isRegistered) {
                Timber.w("No registered SIMs — service idle")
                return@launch
            }
            wsClient.connect(backendUrl, regState.sims) { status ->
                updateNotification(status)
            }
            var lastConnectedTime = System.currentTimeMillis()

            // Fast ping loop — lightweight, just triggers queue drain on backend (every 5s)
            scope.launch {
                while (isActive) {
                    delay(5_000)
                    try {
                        val isConnected = wsClient.connectionStartTime.value != null
                        if (isConnected) {
                            regState.sims.forEach { sim ->
                                wsClient.sendPing(sim.deviceToken)
                            }
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Ping loop failed")
                    }
                }
            }

            // Full heartbeat loop — sensor data + auto-reboot watchdog (every 20s)
            while (isActive) {
                delay(20_000)
                try {
                    val isConnected = wsClient.connectionStartTime.value != null
                    if (isConnected) {
                        lastConnectedTime = System.currentTimeMillis()
                    } else if (prefs.isAutoRebootEnabled()) {
                        val disconnectedMillis = System.currentTimeMillis() - lastConnectedTime
                        if (disconnectedMillis >= 5 * 60 * 1000) {
                            Timber.w("Auto-reboot watchdog triggered! Not connected for 5 minutes.")
                            com.topjohnwu.superuser.Shell.cmd("su -c reboot").exec()
                        }
                    }

                    if (isConnected) {
                        val battery   = readBattery()
                        val wifiRssi  = readWifiRssi()
                        val gsm       = readGsmSignal()
                        val netOp     = readNetworkOperator()
                        val rcs       = checkRcsCapable()
                        val netType   = getActiveNetworkType()

                        val payload = HeartbeatPayload(
                            batteryPercent    = battery.first,
                            isCharging        = battery.second,
                            wifiSignalDbm     = wifiRssi,
                            gsmSignalDbm      = gsm.first,
                            gsmSignalAsu      = gsm.second,
                            networkOperator   = netOp,
                            rcsCapable        = rcs,
                            activeNetworkType = netType,
                            apkVersion        = com.messagingagent.android.BuildConfig.VERSION_NAME
                        )
                        val payloadJson = Json.encodeToString(HeartbeatPayload.serializer(), payload)
                        
                        regState.sims.forEach { sim ->
                            wsClient.sendHeartbeat(payloadJson, sim.deviceToken)
                        }
                        Timber.d("Heartbeat sent for ${regState.sims.size} SIMs: battery=${battery.first}% charging=${battery.second} network=$netType wifi=$wifiRssi gsm=${gsm.first}")

                        // Drain and send new device logs to backend
                        val newLogs = wsClient.drainNewLogs()
                        if (newLogs.isNotEmpty()) {
                            regState.sims.firstOrNull()?.let { sim ->
                                wsClient.sendDeviceLogs(newLogs, sim.deviceToken)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Heartbeat/Watchdog loop failed")
                }
            }
        }
        // DLR Watchdog runs as a SEPARATE parallel coroutine — cannot be after the infinite while loop!
        scope.launch {
            Timber.i("DLR Watchdog coroutine started")
            dlrWatchdog()
        }
        return START_STICKY
    }
    private suspend fun dlrWatchdog() {
        var lastGcTime = System.currentTimeMillis()
        // Track which correlationIds have already had SENT notification sent (avoids duplicates)
        val sentNotified = mutableSetOf<String>()
        // Track when DELIVERED was sent — keep monitoring for SEEN/READ (status=11)
        val deliveredAt = mutableMapOf<String, Long>()

        val bugleDbDir = "/data/data/com.google.android.apps.messaging/databases"
        val srcDb = "$bugleDbDir/bugle_db"
        val tmpDb = "${applicationContext.filesDir.absolutePath}/dlr_check.db"
        val uid = android.os.Process.myUid()

        // Channel bridges FileObserver callbacks into the coroutine scope
        val changeChannel = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

        // FileObserver watches bugle_db directory for write events (inotify)
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

        // Root: ensure our process can observe the directory
        com.topjohnwu.superuser.Shell.cmd(
            "chmod 755 '$bugleDbDir'"
        ).exec()
        observer.startWatching()
        Timber.i("DLR Watchdog: FileObserver started on $bugleDbDir")

        try {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                // Wait for either:
                // 1. FileObserver triggers a change (instant)
                // 2. 5-second timeout (periodic fallback check for when FileObserver misses events)
                kotlinx.coroutines.withTimeoutOrNull(5000) {
                    changeChannel.receive()
                }

                val now = System.currentTimeMillis()

                // Garbage collect stale entries (> 2 hours old) — only check every 60s
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

                val resolved = mutableListOf<PendingDlr>()

                // Copy bugle_db + WAL to temp, then query with Android SQLiteDatabase API
                try {
                    com.topjohnwu.superuser.Shell.cmd(
                        "cp '$srcDb' '$tmpDb' && cp '$srcDb-wal' '$tmpDb-wal' 2>/dev/null; cp '$srcDb-shm' '$tmpDb-shm' 2>/dev/null; chown $uid:$uid '$tmpDb' '$tmpDb-wal' '$tmpDb-shm' 2>/dev/null; chmod 600 '$tmpDb' '$tmpDb-wal' '$tmpDb-shm' 2>/dev/null"
                    ).exec()

                    android.database.sqlite.SQLiteDatabase.openDatabase(
                        tmpDb, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                    ).use { db ->
                        for (p in currentPending) {
                            try {
                                val ageMs = now - p.addedAt

                                // Query by exact bugle_db _id when known, fallback to first row after initialMaxId
                                val query = if (p.bugleMessageId > 0) {
                                    Pair("SELECT _id, message_status FROM messages WHERE _id = ?", arrayOf(p.bugleMessageId.toString()))
                                } else {
                                    Pair("SELECT _id, message_status FROM messages WHERE _id > ? ORDER BY _id ASC LIMIT 1", arrayOf(p.initialMaxId.toString()))
                                }
                                db.rawQuery(query.first, query.second
                                ).use { cursor ->
                                    if (cursor.moveToNext()) {
                                        val status = cursor.getInt(1)
                                        val alreadyDelivered = deliveredAt.containsKey(p.correlationId)

                                        when (status) {
                                            11 -> {
                                                // SEEN/READ — recipient opened the message
                                                if (alreadyDelivered) {
                                                    // DELIVERED was already sent — just update errorDetail with SEEN/READ
                                                    Timber.i("👁️ DLR SEEN: ${p.correlationId} — status=11, updating with SEEN/READ (age=${ageMs/1000}s)")
                                                    try {
                                                        wsClient.sendDeliveryResultAsync(
                                                            DeliveryResult(p.correlationId, "DELIVERED", "SEEN/READ"),
                                                            p.deviceToken
                                                        )
                                                    } catch (e: Exception) { Timber.e(e, "Failed to send SEEN/READ via WebSocket") }
                                                } else {
                                                    // DELIVERED wasn't sent yet — send it with SEEN/READ in one shot
                                                    Timber.i("✅ DLR DELIVERED+SEEN: ${p.correlationId} — status=11, first detection (age=${ageMs/1000}s)")
                                                    try {
                                                        wsClient.sendDeliveryResultAsync(
                                                            DeliveryResult(p.correlationId, "DELIVERED", "SEEN/READ"),
                                                            p.deviceToken
                                                        )
                                                    } catch (e: Exception) { Timber.e(e, "Failed to send DLR via WebSocket") }
                                                }
                                                resolved.add(p)
                                                deliveredAt.remove(p.correlationId)
                                            }
                                            2 -> {
                                                // DELIVERED — message confirmed delivered to recipient's device
                                                if (!alreadyDelivered) {
                                                    // First time seeing status=2: send DELIVERED but keep tracking for SEEN/READ
                                                    deliveredAt[p.correlationId] = now
                                                    Timber.i("✅ DLR DELIVERED: ${p.correlationId} — status=2, monitoring for SEEN/READ (age=${ageMs/1000}s)")
                                                    try {
                                                        wsClient.sendDeliveryResultAsync(
                                                            DeliveryResult(p.correlationId, "DELIVERED"),
                                                            p.deviceToken
                                                        )
                                                    } catch (e: Exception) { Timber.e(e, "Failed to send DELIVERED via WebSocket") }
                                                } else {
                                                    // Already sent DELIVERED — waiting for SEEN/READ
                                                    // Give up after 2 minutes
                                                    val waitedMs = now - deliveredAt[p.correlationId]!!
                                                    if (waitedMs > 120_000) {
                                                        Timber.i("⏱ DLR SEEN timeout: ${p.correlationId} — 2min without SEEN/READ, resolving")
                                                        resolved.add(p)
                                                        deliveredAt.remove(p.correlationId)
                                                    }
                                                }
                                            }
                                            8 -> {
                                                // FAILED — definitive send failure
                                                Timber.w("❌ DLR FAILED: ${p.correlationId} — status=8 (send failure, age=${ageMs/1000}s)")
                                                try {
                                                    wsClient.sendDeliveryResultAsync(
                                                        DeliveryResult(p.correlationId, "ERROR"),
                                                        p.deviceToken
                                                    )
                                                } catch (e: Exception) { Timber.e(e, "Failed to send ERROR via WebSocket") }
                                                resolved.add(p)
                                                deliveredAt.remove(p.correlationId)
                                            }
                                            1 -> {
                                                // SENDING — message still being submitted to RCS network.
                                                // Send SENT to unlock device (once), but if stuck >15s treat as error.
                                                if (!sentNotified.contains(p.correlationId)) {
                                                    sentNotified.add(p.correlationId)
                                                    Timber.i("📤 DLR SENT: ${p.correlationId} — message being sent (status=1, age=${ageMs/1000}s)")
                                                    try {
                                                        wsClient.sendDeliveryResultAsync(
                                                            DeliveryResult(p.correlationId, "SENT"),
                                                            p.deviceToken
                                                        )
                                                    } catch (e: Exception) { Timber.e(e, "Failed to send SENT via WebSocket") }
                                                }
                                                if (ageMs > 15_000) {
                                                    Timber.w("❌ DLR STUCK SENDING: ${p.correlationId} — status=1 for ${ageMs/1000}s, treating as ERROR")
                                                    try {
                                                        wsClient.sendDeliveryResultAsync(
                                                            DeliveryResult(p.correlationId, "ERROR"),
                                                            p.deviceToken
                                                        )
                                                    } catch (e: Exception) { Timber.e(e, "Failed to send ERROR via WebSocket") }
                                                    resolved.add(p)
                                                }
                                            }
                                            else -> {
                                                // Other transient states (5=queued, etc.) — keep waiting
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Timber.e(e, "DLR Watchdog: exception for ${p.correlationId}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "DLR Watchdog: failed to copy/open bugle_db")
                } finally {
                    com.topjohnwu.superuser.Shell.cmd("rm -f '$tmpDb' '$tmpDb-wal' '$tmpDb-shm' '$tmpDb-journal'").exec()
                }

                if (resolved.isNotEmpty()) {
                    dlrTracker.removeResolved(resolved)
                    resolved.forEach { sentNotified.remove(it.correlationId) }
                }
            }
        } finally {
            observer.stopWatching()
            Timber.i("DLR Watchdog: FileObserver stopped")
        }
    }

    override fun onDestroy() {
        scope.cancel()
        wsClient.disconnect()
        Timber.i("MessagingAgentService destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Messaging Agent Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Background SMPP relay with RCS transcoding" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun scheduleAutoPurgeAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AutoPurgeReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            1002,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val calendar = Calendar.getInstance().apply {
            timeInMillis = System.currentTimeMillis()
            set(Calendar.HOUR_OF_DAY, 3)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (calendar.timeInMillis <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }

        alarmManager.setRepeating(
            AlarmManager.RTC_WAKEUP,
            calendar.timeInMillis,
            AlarmManager.INTERVAL_DAY,
            pendingIntent
        )
        Timber.i("Scheduled Auto-Purge alarm for 3:00 AM daily")
    }

    private fun buildNotification(status: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Messaging Agent")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(this, 0,
                    Intent(this, SetupActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE)
            )
            .build()

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }

    /** Use native Android API to read battery percent and charging status non-root */
    private fun readBattery(): Pair<Int?, Boolean> {
        val intent = applicationContext.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        val plugged = intent?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val isCharging = plugged == android.os.BatteryManager.BATTERY_PLUGGED_AC ||
                         plugged == android.os.BatteryManager.BATTERY_PLUGGED_USB ||
                         plugged == android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS
        val percent = if (level >= 0 && scale > 0) (level * 100) / scale else null
        return Pair(percent, isCharging)
    }

    /** Read WiFi RSSI via WifiManager (no root needed on API 29+) */
    private fun readWifiRssi(): Int? {
        return try {
            val wm = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
            val info = wm.connectionInfo
            if (info.rssi != -127) info.rssi else null
        } catch (e: Exception) { null }
    }

    /** Shell: `dumpsys telephony.registry` → parse via Regex */
    private fun readGsmSignal(): Pair<Int?, Int?> {
        val output = com.topjohnwu.superuser.Shell.cmd("dumpsys telephony.registry").exec().out
        val fullText = output.joinToString(" ")

        var bestDbm: Int? = null
        var bestAsu: Int? = null

        // Try LTE RSRP first
        Regex("""rsrp=(-?\d+)""").findAll(fullText).forEach { match ->
            val dbm = match.groupValues[1].toIntOrNull()
            if (dbm != null && dbm < 2000000000 && dbm != -1) bestDbm = dbm
        }

        // Fallback to RSSI
        if (bestDbm == null) {
            Regex("""rssi=(-?\d+)""").findAll(fullText).forEach { match ->
                val dbm = match.groupValues[1].toIntOrNull()
                if (dbm != null && dbm < 2000000000 && dbm != -1 && dbm != 99) bestDbm = dbm
            }
        }

        // Try older ASU format
        Regex("""signalStrength\s+asu=(\d+)""").findAll(fullText).forEach { match ->
            val asu = match.groupValues[1].toIntOrNull()
            if (asu != null && asu in 0..31) {
                bestAsu = asu
                if (bestDbm == null) bestDbm = (2 * asu) - 113
            }
        }
        
        return bestDbm to bestAsu
    }

    /** Get network operator name */
    private fun readNetworkOperator(): String? {
        return try {
            val tm = applicationContext.getSystemService(android.telephony.TelephonyManager::class.java)
            tm.networkOperatorName.takeIf { it.isNotBlank() }
        } catch (e: Exception) { null }
    }

    /** Check RCS capability via ImsManager (root / privileged API) */
    private fun checkRcsCapable(): Boolean {
        return try {
            val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO,
                android.net.Uri.parse("smsto:0"))
            val pm = applicationContext.packageManager
            val resolveInfo = pm.queryIntentActivities(intent, 0)
            resolveInfo.isNotEmpty()
        } catch (e: Exception) { false }
    }

    /** Helper to parse active network transport (WIFI vs CELLULAR) via NetworkCapabilities */
    private fun getActiveNetworkType(): String? {
        return try {
            val cm = applicationContext.getSystemService(android.net.ConnectivityManager::class.java)
            val cap = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "NONE"
            when {
                cap.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                cap.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                else -> "OTHER"
            }
        } catch (e: Exception) { null }
    }
}
