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
import com.messagingagent.android.receiver.ConnectionWatchdogReceiver
import com.messagingagent.android.receiver.RebootWatchdogReceiver
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
import android.media.AudioManager

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
    val apkVersion: String?,
    val phoneNumber: String? = null,
    val adbWifiAddress: String? = null
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
        ConnectionWatchdogReceiver.schedule(this)
        RebootWatchdogReceiver.schedule(this)
        enforceDeviceHardening()
        deployExternalKeepalive()
        Timber.i("MessagingAgentService started (watchdog alarms + keepalive deployed)")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Timber.w("MessagingAgentService task removed — scheduling last-ditch restart")
        // Last-ditch: fire a delayed restart via root (runs even if our process dies)
        try {
            com.topjohnwu.superuser.Shell.cmd(
                "(sleep 5; am set-stopped-state com.messagingagent.android false; am startservice -n com.messagingagent.android/.service.MessagingAgentService 2>/dev/null || am start-foreground-service -n com.messagingagent.android/.service.MessagingAgentService 2>/dev/null) &"
            ).exec()
        } catch (e: Exception) {
            Timber.e(e, "Last-ditch restart failed")
        }
    }

    /**
     * Device hardening applied on every service start:
     * - Force MIUI autostart permission via root
     * - Disable MIUI battery saver restrictions
     * - Clear force-stopped flag
     * - Pin MIUI Security Center version to prevent autostart breakage
     * - Add to battery optimization whitelist
     */
    private fun enforceDeviceHardening() {
        try {
            com.topjohnwu.superuser.Shell.cmd(
                // Clear the force-stopped flag (in case MIUI killed us)
                "am set-stopped-state com.messagingagent.android false",
                // Force MIUI autostart permission
                "appops set com.messagingagent.android AUTO_START allow 2>/dev/null",
                // Ensure we're in the battery optimization whitelist
                "dumpsys deviceidle whitelist +com.messagingagent.android 2>/dev/null",
                // Disable MIUI battery saver for our app
                "cmd appops set com.messagingagent.android RUN_IN_BACKGROUND allow 2>/dev/null",
                "cmd appops set com.messagingagent.android RUN_ANY_IN_BACKGROUND allow 2>/dev/null",
                // Pin MIUI Security Center to prevent autostart permission breakage
                "pm disable-user --user 0 com.miui.securitycenter/com.miui.securitycenter.update.AutoUpdateReceiver 2>/dev/null",
                "pm disable-user --user 0 com.miui.securitycenter/com.miui.securitycenter.update.UpdateCheckJob 2>/dev/null",
                // Disable MIUI power-save kill for our app
                "cmd appops set com.messagingagent.android MIUI_BACKGROUND_START allow 2>/dev/null",
                // Increase logcat buffer to 16MB so crash logs survive longer
                "logcat -G 16M 2>/dev/null",
                "setprop persist.logd.size 16777216 2>/dev/null"
            ).exec()
            Timber.i("🛡️ Device hardening: MIUI autostart + battery saver + whitelist applied")
        } catch (e: Exception) {
            Timber.w(e, "Device hardening partially failed (may not be MIUI)")
        }

        // Enable ADB over TCP (separate from main hardening to avoid port flapping)
        enableAdbOverTcp()
    }

    /**
     * Enable ADB over TCP on port 5555 once. Checks if already enabled to avoid
     * restarting adbd on every service start (which would cause port flapping
     * and conflict with Android's wireless debugging feature).
     */
    private fun enableAdbOverTcp() {
        try {
            val currentPort = com.topjohnwu.superuser.Shell.cmd("getprop persist.adb.tcp.port").exec()
                .out.firstOrNull()?.trim() ?: ""
            if (currentPort == "5555") {
                Timber.d("ADB TCP already enabled on port 5555 — skipping")
                return
            }
            Timber.i("Enabling ADB over TCP on port 5555 (was: '$currentPort')")
            com.topjohnwu.superuser.Shell.cmd(
                "setprop persist.adb.tcp.port 5555",
                "stop adbd",
                "start adbd"
            ).exec()
            Timber.i("ADB over TCP enabled on port 5555 and adbd restarted")
        } catch (e: Exception) {
            Timber.w(e, "Failed to enable ADB over TCP")
        }
    }

    /**
     * Deploy and launch an external keepalive watchdog script that runs independently
     * of the app process. This script survives force-stop and will restart the app
     * within 30 seconds if MIUI or the OS kills it.
     */
    private fun deployExternalKeepalive() {
        try {
            val scriptDest = "/data/local/tmp/ma_keepalive.sh"
            // Copy script from APK assets to filesystem
            val scriptContent = assets.open("keepalive.sh").bufferedReader().readText()
            val tmpFile = java.io.File(filesDir, "keepalive.sh")
            tmpFile.writeText(scriptContent)

            com.topjohnwu.superuser.Shell.cmd(
                "cp '${tmpFile.absolutePath}' '$scriptDest'",
                "chmod 755 '$scriptDest'",
                "chown root:root '$scriptDest'",
                // Kill any stale keepalive instance and relaunch
                "if [ -f /data/local/tmp/ma_keepalive.lock ]; then kill \$(cat /data/local/tmp/ma_keepalive.lock) 2>/dev/null; rm -f /data/local/tmp/ma_keepalive.lock; fi",
                // Launch as a detached root process (survives app death)
                "setsid sh '$scriptDest' </dev/null >/dev/null 2>&1 &"
            ).exec()
            tmpFile.delete()
            Timber.i("🛡️ External keepalive watchdog deployed and launched")
        } catch (e: Exception) {
            Timber.e(e, "Failed to deploy external keepalive script")
        }
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

            // Full heartbeat loop — sensor data (every 20s)
            // Auto-reboot watchdog is now handled by RebootWatchdogReceiver via AlarmManager
            while (isActive) {
                delay(20_000)
                try {
                    val isConnected = wsClient.connectionStartTime.value != null

                    if (isConnected) {
                        val battery   = readBattery()
                        val wifiRssi  = readWifiRssi()
                        val gsm       = readGsmSignal()
                        val netOp     = readNetworkOperator()
                        val rcs       = checkRcsCapable()
                        val netType   = getActiveNetworkType()

                        val phoneNum  = readPhoneNumber()
                        val adbAddr   = readAdbWifiAddress()

                        val payload = HeartbeatPayload(
                            batteryPercent    = battery.first,
                            isCharging        = battery.second,
                            wifiSignalDbm     = wifiRssi,
                            gsmSignalDbm      = gsm.first,
                            gsmSignalAsu      = gsm.second,
                            networkOperator   = netOp,
                            rcsCapable        = rcs,
                            activeNetworkType = netType,
                            apkVersion        = com.messagingagent.android.BuildConfig.VERSION_NAME,
                            phoneNumber       = phoneNum,
                            adbWifiAddress    = adbAddr
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
                // 2. 2-second timeout (periodic fallback check for when FileObserver misses events)
                kotlinx.coroutines.withTimeoutOrNull(2000) {
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

                // Copy bugle_db ONLY (no WAL/SHM) to avoid native SQLite crash from corrupt WAL
                try {
                    com.topjohnwu.superuser.Shell.cmd(
                        "cp '$srcDb' '$tmpDb' && rm -f '$tmpDb-wal' '$tmpDb-shm' && chown $uid:$uid '$tmpDb' && chmod 600 '$tmpDb'"
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
                                            8, 5, 9, 3 -> {
                                                // ERROR-like statuses — but don't report immediately!
                                                // Google Messages can set transient error statuses before
                                                // the message is actually processed. Wait at least 8 seconds.
                                                if (ageMs > 8_000) {
                                                    Timber.w("❌ DLR FAILED: ${p.correlationId} — bugle status=$status (age=${ageMs/1000}s)")
                                                    try {
                                                        wsClient.sendDeliveryResultAsync(
                                                            DeliveryResult(p.correlationId, "ERROR", "bugle_status=$status"),
                                                            p.deviceToken
                                                        )
                                                    } catch (e: Exception) { Timber.e(e, "Failed to send ERROR via WebSocket") }
                                                    resolved.add(p)
                                                    deliveredAt.remove(p.correlationId)
                                                } else {
                                                    // Transient — keep waiting for status to settle
                                                    Timber.d("⏳ DLR WAIT: ${p.correlationId} — bugle status=$status transient (age=${ageMs/1000}s < 8s)")
                                                }
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
                                                    Timber.w("❌ DLR STUCK SENDING: ${p.correlationId} — bugle status=1 for ${ageMs/1000}s, treating as ERROR")
                                                    try {
                                                        wsClient.sendDeliveryResultAsync(
                                                            DeliveryResult(p.correlationId, "ERROR", "bugle_status=1 (stuck sending ${ageMs/1000}s)"),
                                                            p.deviceToken
                                                        )
                                                    } catch (e: Exception) { Timber.e(e, "Failed to send ERROR via WebSocket") }
                                                    resolved.add(p)
                                                }
                                            }
                                            else -> {
                                                // Unknown status — log it and keep waiting up to 8s, then report as ERROR
                                                if (ageMs > 8_000) {
                                                    Timber.w("❌ DLR UNKNOWN: ${p.correlationId} — bugle status=$status (age=${ageMs/1000}s)")
                                                    try {
                                                        wsClient.sendDeliveryResultAsync(
                                                            DeliveryResult(p.correlationId, "ERROR", "bugle_status=$status (unknown)"),
                                                            p.deviceToken
                                                        )
                                                    } catch (e: Exception) { Timber.e(e, "Failed to send ERROR via WebSocket") }
                                                    resolved.add(p)
                                                    deliveredAt.remove(p.correlationId)
                                                }
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

    /** Try multiple methods to detect the SIM's phone number.
     *  1. SubscriptionInfo.number (standard API)
     *  2. TelephonyManager.line1Number
     *  3. Root shell: read from telephony.db (MIUI / devices that hide the number)
     */
    private fun readPhoneNumber(): String? {
        try {
            if (androidx.core.content.ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.READ_PHONE_NUMBERS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                val subMgr = applicationContext.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as android.telephony.SubscriptionManager
                val subs = subMgr.activeSubscriptionInfoList ?: emptyList()
                for (sub in subs) {
                    val num = sub.number
                    if (!num.isNullOrBlank()) return num.trim()
                }
                // Fallback: TelephonyManager.line1Number
                val tm = applicationContext.getSystemService(android.telephony.TelephonyManager::class.java)
                val line1 = tm.line1Number
                if (!line1.isNullOrBlank()) return line1.trim()
            }
        } catch (e: Exception) { Timber.w(e, "Standard phone number read failed") }

        // Root fallback: query telephony settings or SIM manager
        try {
            val result = com.topjohnwu.superuser.Shell.cmd(
                "content query --uri content://telephony/siminfo --projection number --where \"sim_id>=0\" 2>/dev/null"
            ).exec()
            if (result.isSuccess) {
                for (line in result.out) {
                    val match = Regex("number=([+\\d][\\d\\s+]+)").find(line)
                    if (match != null) {
                        val num = match.groupValues[1].trim()
                        if (num.isNotBlank() && num.length >= 7) return num
                    }
                }
            }
        } catch (e: Exception) { Timber.w(e, "Root phone number read failed") }

        return null
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

    /**
     * Enables WiFi ADB via root and returns the IP:port address.
     * On each heartbeat, ensures ADB over TCP is enabled so the backend
     * always has the current address even after device reboots.
     */
    private fun readAdbWifiAddress(): String? {
        try {
            // 1. Get WiFi IP address
            val wifiMgr = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
            val wifiInfo = wifiMgr.connectionInfo
            val ipInt = wifiInfo.ipAddress
            if (ipInt == 0) return null
            val ip = String.format(
                "%d.%d.%d.%d",
                ipInt and 0xff, (ipInt shr 8) and 0xff,
                (ipInt shr 16) and 0xff, (ipInt shr 24) and 0xff
            )

            // 2. Enable ADB over TCP via root (idempotent — safe to run every heartbeat)
            val enableResult = com.topjohnwu.superuser.Shell.cmd(
                "setprop service.adb.tcp.port 5555",
                "stop adbd",
                "start adbd"
            ).exec()
            if (!enableResult.isSuccess) {
                Timber.w("Failed to enable ADB over TCP: ${enableResult.err}")
            }

            // 3. Read the actual ADB TCP port
            val portResult = com.topjohnwu.superuser.Shell.cmd("getprop service.adb.tcp.port").exec()
            val port = portResult.out.firstOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: "5555"

            return "$ip:$port"
        } catch (e: Exception) {
            Timber.w(e, "Failed to read ADB WiFi address")
            return null
        }
    }
}
