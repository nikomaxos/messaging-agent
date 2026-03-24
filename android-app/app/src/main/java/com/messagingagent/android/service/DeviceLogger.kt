package com.messagingagent.android.service

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Global device log bus that collects logs from any component (RcsSender, etc.)
 * without requiring DI injection. WebSocketRelayClient drains these logs on each
 * heartbeat cycle and forwards them to the admin panel.
 *
 * This avoids circular DI between WebSocketRelayClient and RcsSender.
 */
object DeviceLogBus {
    data class LogEntry(val time: String, val level: String, val message: String)

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val buffer = CopyOnWriteArrayList<LogEntry>()

    fun log(level: String, message: String) {
        buffer.add(LogEntry(timeFmt.format(Date()), level, message))
        // Keep buffer bounded
        while (buffer.size > 500) {
            buffer.removeAt(0)
        }
    }

    /** Drain all pending log entries (called by WebSocketRelayClient on each heartbeat). */
    fun drain(): List<LogEntry> {
        val snapshot = ArrayList(buffer)
        buffer.clear()
        return snapshot
    }
}
