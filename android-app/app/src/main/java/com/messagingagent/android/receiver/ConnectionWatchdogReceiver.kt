package com.messagingagent.android.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.messagingagent.android.data.PreferencesRepository
import com.messagingagent.android.service.WebSocketRelayClient
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * AlarmManager-based watchdog that fires every 60s to check WebSocket connectivity.
 * If the connection is down, forces a reconnect attempt.
 * Uses setExactAndAllowWhileIdle to work even in Doze mode.
 */
@AndroidEntryPoint
class ConnectionWatchdogReceiver : BroadcastReceiver() {

    @Inject lateinit var wsClient: WebSocketRelayClient
    @Inject lateinit var prefs: PreferencesRepository

    override fun onReceive(context: Context, intent: Intent) {
        // Always reschedule the next alarm first
        schedule(context)

        val isConnected = wsClient.connectionStartTime.value != null
        if (isConnected) return  // all good, nothing to do

        Timber.w("ConnectionWatchdog: not connected — attempting reconnect")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val regState = prefs.registrationFlow().first()
                val backendUrl = regState.backendUrl ?: return@launch
                if (!regState.isRegistered) return@launch

                wsClient.connect(backendUrl, regState.sims) { status ->
                    Timber.d("ConnectionWatchdog reconnect status: $status")
                }
            } catch (e: Exception) {
                Timber.e(e, "ConnectionWatchdog reconnect failed")
            }
        }
    }

    companion object {
        private const val REQUEST_CODE = 9001

        fun schedule(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ConnectionWatchdogReceiver::class.java)
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
            val intent = Intent(context, ConnectionWatchdogReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(pi)
        }
    }
}
