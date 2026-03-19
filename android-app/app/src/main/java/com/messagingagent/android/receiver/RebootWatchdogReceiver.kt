package com.messagingagent.android.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.messagingagent.android.data.PreferencesRepository
import com.messagingagent.android.service.WebSocketRelayClient
import com.topjohnwu.superuser.Shell
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * AlarmManager-based auto-reboot watchdog that fires every 60s.
 * If autoReboot is enabled AND the WebSocket has been disconnected for ≥5 minutes,
 * triggers a root reboot.
 * Uses setExactAndAllowWhileIdle to work even in Doze mode.
 */
@AndroidEntryPoint
class RebootWatchdogReceiver : BroadcastReceiver() {

    @Inject lateinit var wsClient: WebSocketRelayClient
    @Inject lateinit var prefs: PreferencesRepository

    override fun onReceive(context: Context, intent: Intent) {
        // Always reschedule the next alarm first
        schedule(context)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!prefs.isAutoRebootEnabled()) return@launch

                val isConnected = wsClient.connectionStartTime.value != null
                if (isConnected) {
                    // Store current time as "last connected" in shared prefs
                    prefs.setLastConnectedTime(System.currentTimeMillis())
                    return@launch
                }

                val lastConnected = prefs.getLastConnectedTime()
                if (lastConnected == 0L) {
                    // First run, set baseline
                    prefs.setLastConnectedTime(System.currentTimeMillis())
                    return@launch
                }

                val disconnectedMs = System.currentTimeMillis() - lastConnected
                if (disconnectedMs >= 5 * 60 * 1000) {
                    Timber.w("RebootWatchdog: disconnected for ${disconnectedMs / 1000}s — rebooting!")
                    Shell.cmd("su -c reboot").exec()
                } else {
                    Timber.d("RebootWatchdog: disconnected for ${disconnectedMs / 1000}s (< 5 min, waiting)")
                }
            } catch (e: Exception) {
                Timber.e(e, "RebootWatchdog failed")
            }
        }
    }

    companion object {
        private const val REQUEST_CODE = 9002

        fun schedule(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, RebootWatchdogReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 60_000,
                pi
            )
        }

        fun cancel(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, RebootWatchdogReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(pi)
        }
    }
}
