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
import com.messagingagent.android.worker.HeartbeatWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import timber.log.Timber
import javax.inject.Inject

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
        }
        // Schedule heartbeat worker
        HeartbeatWorker.schedule(this)
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
}
