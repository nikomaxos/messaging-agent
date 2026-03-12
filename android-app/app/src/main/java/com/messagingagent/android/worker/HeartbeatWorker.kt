package com.messagingagent.android.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.messagingagent.android.data.PreferencesRepository
import com.messagingagent.android.service.WebSocketRelayClient
import com.topjohnwu.superuser.Shell
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.concurrent.TimeUnit

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
 * WorkManager periodic worker that:
 *  1. Reads device metrics via root shell commands (libsu)
 *  2. Sends heartbeat payload to backend over the active WebSocket
 */
@HiltWorker
class HeartbeatWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val wsClient: WebSocketRelayClient,
    private val prefs: PreferencesRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
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
            return Result.success()
        } catch (e: Exception) {
            Timber.e(e, "Heartbeat failed")
            return Result.retry()
        }
    }

    /** Shell: `dumpsys battery` → parse level field */
    private fun readBattery(): Int? {
        val output = Shell.cmd("dumpsys battery | grep level").exec().out
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

    /** Shell: `dumpsys telephony.registry` → parse signalStrength */
    private fun readGsmSignal(): Pair<Int?, Int?> {
        val output = Shell.cmd("dumpsys telephony.registry | grep -i 'signalStrength'").exec().out
        // Parse first ASU value from signal strength dump
        val line = output.firstOrNull() ?: return null to null
        val parts = line.split(" ")
        val asu = parts.getOrNull(1)?.toIntOrNull()
        val dbm = if (asu != null) (2 * asu) - 113 else null   // dBm = 2*ASU - 113 (GSM)
        return dbm to asu
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
        // Try to detect RCS via intent resolution
        return try {
            val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO,
                android.net.Uri.parse("smsto:0"))
            val pm = applicationContext.packageManager
            val resolveInfo = pm.queryIntentActivities(intent, 0)
            resolveInfo.isNotEmpty()
        } catch (e: Exception) { false }
    }

    companion object {
        private const val WORK_NAME = "heartbeat_worker"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(30, TimeUnit.SECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
