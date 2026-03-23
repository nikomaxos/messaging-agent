package com.messagingagent.android.service

import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.Job

class BugleRootService : RootService() {
    private var clientMessenger: Messenger? = null
    private val serviceJob = Job()

    inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                1 -> {
                    clientMessenger = msg.replyTo
                    // No longer polling for DLRs — MessagingAgentService handles all DLR resolution
                    // to avoid race conditions where this service reports ERROR on transient statuses
                }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder {
        return Messenger(IncomingHandler()).binder
    }

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }
}
