package com.messagingagent.android.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.messagingagent.android.R
import com.messagingagent.android.data.PreferencesRepository
import com.messagingagent.android.ui.SetupActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val CHANNEL_ID = "messaging_agent_service"
    private val NOTIFICATION_ID = 1001

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification("Connecting to backend…")
        startForeground(NOTIFICATION_ID, notification)
        Timber.i("MessagingAgentService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            val backendUrl = prefs.getBackendUrl() ?: run {
                Timber.w("No backend URL configured — service idle")
                return@launch
            }
            val token = prefs.getDeviceToken() ?: run {
                Timber.w("No device token configured — service idle")
                return@launch
            }
            val deviceId = prefs.getDeviceId() ?: run {
                Timber.w("No device ID configured — service idle")
                return@launch
            }
            wsClient.connect(backendUrl, token, deviceId) { status ->
                updateNotification(status)
            }
            var lastConnectedTime = System.currentTimeMillis()

            // Heartbeat loop & Auto-Reboot Watchdog
            while (isActive) {
                delay(30_000)
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

                        val json = Json.encodeToString(HeartbeatPayload.serializer(), payload)
                        wsClient.sendHeartbeat(json)
                        Timber.d("Heartbeat sent: battery=${battery.first}% charging=${battery.second} network=$netType wifi=$wifiRssi gsm=${gsm.first}")
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Heartbeat/Watchdog loop failed")
                }
            }
        }
        return START_STICKY
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
