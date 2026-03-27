package com.messagingagent.android.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.LocationManager
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
import kotlin.coroutines.resume
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
    val adbWifiAddress: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null
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
        val notification = buildNotification("Connecting to backend")
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
        Timber.w("MessagingAgentService task removed -- scheduling last-ditch restart")
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
                "setprop persist.logd.size 16777216 2>/dev/null",
                // MIUI: Also whitelist Google Messages for background activity launch
                // This prevents "am start failed" when dispatching messages
                "appops set com.google.android.apps.messaging AUTO_START allow 2>/dev/null",
                "appops set com.google.android.apps.messaging MIUI_BACKGROUND_START allow 2>/dev/null",
                "cmd appops set com.google.android.apps.messaging RUN_IN_BACKGROUND allow 2>/dev/null",
                "cmd appops set com.google.android.apps.messaging RUN_ANY_IN_BACKGROUND allow 2>/dev/null",
                "dumpsys deviceidle whitelist +com.google.android.apps.messaging 2>/dev/null"
            ).exec()
            Timber.i(" Device hardening: MIUI autostart + battery saver + whitelist applied (incl. Google Messages)")
        } catch (e: Exception) {
            Timber.w(e, "Device hardening partially failed (may not be MIUI)")
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
            Timber.i(" External keepalive watchdog deployed and launched")
        } catch (e: Exception) {
            Timber.e(e, "Failed to deploy external keepalive script")
        }
    }

    companion object {
        const val NOTIFICATION_ID = 1001

        var lastLocationSendTime = 0L
        var forceLocationRefresh = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            val regState = prefs.registrationFlow().first()
            val backendUrl = regState.backendUrl ?: run {
                Timber.w("No backend URL configured -- service idle")
                return@launch
            }
            if (!regState.isRegistered) {
                Timber.w("No registered SIMs -- service idle")
                return@launch
            }
            wsClient.connect(backendUrl, regState.sims) { status ->
                updateNotification(status)
            }
            // Fast ping loop -- lightweight, just triggers queue drain on backend (every 5s)
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

            // Full heartbeat loop -- sensor data (every 20s)
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
                        
                        // Rate limit location to 1 hour (3600000 ms) unless force refreshed
                        val now = System.currentTimeMillis()
                        val location = if (forceLocationRefresh || (now - lastLocationSendTime >= 3600000)) {
                            lastLocationSendTime = now
                            forceLocationRefresh = false
                            readLocationActive()
                        } else {
                            Pair(null, null)
                        }

                        val payload = HeartbeatPayload(
                            batteryPercent    = battery.first,
                            isCharging        = battery.second,
                            wifiSignalDbm     = wifiRssi,
                            gsmSignalDbm      = gsm.first,
                            gsmSignalAsu      = gsm.second,
                            networkOperator   = netOp,
                            rcsCapable        = rcs,
                            activeNetworkType = netType,
                            apkVersion        = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" },
                            phoneNumber       = phoneNum,
                            adbWifiAddress    = adbAddr,
                            latitude          = location.first,
                            longitude         = location.second
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

    @Suppress("DEPRECATION")
    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun readLocationActive(): Pair<Double?, Double?> {
        return try {
            val lm = applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                try {
                    val listener = object : android.location.LocationListener {
                        override fun onLocationChanged(loc: android.location.Location) {
                            try { lm.removeUpdates(this) } catch (e: Exception) {}
                            if (cont.isActive) cont.resume(Pair(loc.latitude, loc.longitude))
                        }
                        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {}
                    }
                    lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, android.os.Looper.getMainLooper())
                    
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                        kotlinx.coroutines.delay(12000)
                        try { lm.removeUpdates(listener) } catch (e: Exception) {}
                        if (cont.isActive) cont.resume(Pair(null, null))
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error requesting single update")
                    if (cont.isActive) cont.resume(Pair(null, null))
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Location manager setup failed")
            Pair(null, null)
        }
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

    /** Shell: `dumpsys telephony.registry`  parse via Regex */
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

            // 2. Check if ADB TCP is already enabled — only restart adbd if NOT already set
            val currentPort = com.topjohnwu.superuser.Shell.cmd("getprop service.adb.tcp.port").exec()
                .out.firstOrNull()?.trim() ?: ""
            val persistPort = com.topjohnwu.superuser.Shell.cmd("getprop persist.adb.tcp.port").exec()
                .out.firstOrNull()?.trim() ?: ""

            if (currentPort != "5555" && persistPort != "5555") {
                // ADB TCP not enabled (e.g. after reboot) — enable it ONCE
                Timber.i("ADB TCP not enabled (service=$currentPort, persist=$persistPort) — enabling now")
                com.topjohnwu.superuser.Shell.cmd(
                    "setprop service.adb.tcp.port 5555",
                    "setprop persist.adb.tcp.port 5555",
                    "stop adbd",
                    "start adbd"
                ).exec()
            }

            // 3. Read the actual port (should be 5555 now)
            val port = com.topjohnwu.superuser.Shell.cmd("getprop service.adb.tcp.port").exec()
                .out.firstOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: "5555"

            return "$ip:$port"
        } catch (e: Exception) {
            Timber.w(e, "Failed to read ADB WiFi address")
            return null
        }
    }


}
