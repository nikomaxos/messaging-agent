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
    val wifiSignalDbm: Int?,
    val gsmSignalDbm: Int?,
    val gsmSignalAsu: Int?,
    val networkOperator: String?,
    val rcsCapable: Boolean
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
            wsClient.connect(backendUrl, token) { status ->
                updateNotification(status)
            }
            
            // Heartbeat loop
            while (isActive) {
                delay(30_000)
                try {
                    val battery   = readBattery()
                    val wifiRssi  = readWifiRssi()
                    val gsm       = readGsmSignal()
                    val netOp     = readNetworkOperator()
                    val rcs       = checkRcsCapable()

                    val payload = HeartbeatPayload(
                        batteryPercent  = battery,
                        wifiSignalDbm   = wifiRssi,
                        gsmSignalDbm    = gsm.first,
                        gsmSignalAsu    = gsm.second,
                        networkOperator = netOp,
                        rcsCapable      = rcs
                    )

                    val json = Json.encodeToString(HeartbeatPayload.serializer(), payload)
                    wsClient.sendHeartbeat(json)
                    Timber.d("Heartbeat sent: battery=$battery% wifi=$wifiRssi gsm=${gsm.first}")
                } catch (e: Exception) {
                    Timber.e(e, "Heartbeat failed")
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

    /** Shell: `dumpsys battery` → parse level field */
    private fun readBattery(): Int? {
        val output = com.topjohnwu.superuser.Shell.cmd("dumpsys battery | grep level").exec().out
        return output.firstOrNull()?.split(":")?.getOrNull(1)?.trim()?.toIntOrNull()
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

        // Prefer LTE RSRP
        val rsrpMatch = Regex("""rsrp=(-?\d+)""").find(fullText)
        if (rsrpMatch != null) {
            val dbm = rsrpMatch.groupValues[1].toIntOrNull()
            return dbm to null
        }

        // Fallback to RSSI
        val rssiMatch = Regex("""rssi=(-?\d+)""").find(fullText)
        if (rssiMatch != null) {
            val dbm = rssiMatch.groupValues[1].toIntOrNull()
            return dbm to null
        }

        // Fallback to old ASU mapping
        val asuMatch = Regex("""signalStrength asu=(\d+)""").find(fullText)
        if (asuMatch != null) {
            val asu = asuMatch.groupValues[1].toIntOrNull()
            if (asu != null && asu in 0..31) {
                return ((2 * asu) - 113) to asu
            }
        }
        
        return null to null
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
}
