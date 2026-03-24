package com.messagingagent.android.service

/**
 * Simple logging interface to forward device-side events to the admin panel.
 * Breaks the circular dependency between WebSocketRelayClient and RcsSender.
 */
interface DeviceLogger {
    fun addLog(level: String, message: String)
}
