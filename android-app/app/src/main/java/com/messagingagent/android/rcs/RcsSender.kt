package com.messagingagent.android.rcs

import android.content.Context
import com.topjohnwu.superuser.Shell
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import android.telephony.SubscriptionManager

data class RcsSendResult(
    val success: Boolean,
    val noRcs: Boolean = false,
    val error: String? = null,
    val sentEarly: Boolean = false  // true = fast-path DB verify confirmed message entered bugle_db
)

@Singleton
class RcsSender @Inject constructor(
    @ApplicationContext private val context: Context,
    internal val dlrTracker: PendingDlrTracker,
    private val prefs: com.messagingagent.android.data.PreferencesRepository
) {

    private companion object {
        const val MESSAGES_PKG = "com.google.android.apps.messaging"
        var cachedSendButtonX: Int = -1
        var cachedSendButtonY: Int = -1
        var lastSendTimeMs: Long = 0L
    }

    private val dispatchMutex = kotlinx.coroutines.sync.Mutex()
    private var sentMessageCount = 0

    suspend fun sendRcs(correlationId: String, deviceToken: String, to: String, text: String, subscriptionId: Int, dlrDelayMinSec: Int = 2, dlrDelayMaxSec: Int = 5): RcsSendResult {
        dispatchMutex.lock()
        try {
        if (!isMessagesInstalled()) {
            Timber.w("Google Messages not installed -- cannot send RCS to $to")
            com.messagingagent.android.service.DeviceLogBus.log("ERROR", "RCS: Google Messages not installed — cannot send to $to")
            return RcsSendResult(success = false, noRcs = true, error = "Google Messages not installed")
        }

        return try {
            val safeText = text.replace("'", "'\\''")
            val cleanTo = to.replace("[^0-9+]".toRegex(), "")

            val now = System.currentTimeMillis()
            lastSendTimeMs = now

            var initialMaxId = 0L
            try {
                val srcDb = "/data/data/com.google.android.apps.messaging/databases/bugle_db"
                val tmpDb = "${context.filesDir.absolutePath}/bugle.db"
                val uid = context.applicationInfo.uid
                val sqliteCmd = "/data/local/tmp/sqlite3 '$srcDb' 'SELECT MAX(_id) FROM messages;' 2>/dev/null"
                val sqliteRes = com.topjohnwu.superuser.Shell.cmd("if [ -x /data/local/tmp/sqlite3 ]; then $sqliteCmd; else echo 'FALLBACK'; fi").exec()
                val sqliteOut = sqliteRes.out.joinToString("").trim()
                
                if (sqliteOut != "FALLBACK" && sqliteOut.isNotEmpty()) {
                    initialMaxId = sqliteOut.toLongOrNull() ?: 0L
                } else {
                    com.topjohnwu.superuser.Shell.cmd("cp '$srcDb' '$tmpDb' && rm -f '$tmpDb-wal' '$tmpDb-shm' && chown $uid:$uid '$tmpDb' && chmod 600 '$tmpDb'").exec()
                    android.database.sqlite.SQLiteDatabase.openDatabase(tmpDb, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
                        db.rawQuery("SELECT MAX(_id) FROM messages", null).use { cursor ->
                            if (cursor.moveToFirst()) initialMaxId = cursor.getLong(0)
                        }
                    }
                    com.topjohnwu.superuser.Shell.cmd("rm -f '$tmpDb' '$tmpDb-wal' '$tmpDb-shm' '$tmpDb-journal'").exec()
                }
            } catch (e: Exception) { Timber.e(e, "Failed to capture initial bugle MAX(_id)") }

            // Wake up screen — MIUI-compatible 3-tier wake (Always execute block to avoid lock-screen stalls)
            var pwrState = com.topjohnwu.superuser.Shell.cmd("dumpsys power | grep -E 'mWakefulness|mDisplayReady'").exec().out.joinToString("")
            // Some devices report 'Awake' but 'mDisplayReady=false', or report 'Asleep'. We tap power if it isn't fully ready.
            if (pwrState.contains("Asleep") || pwrState.contains("mDisplayReady=false")) {
                Timber.i("Waking up screen with explicit keyevent 224 (KEYCODE_WAKEUP)")
                com.topjohnwu.superuser.Shell.cmd(
                    "svc power stayon true",
                    "input keyevent 224"
                ).exec()
                kotlinx.coroutines.delay(1000)
                // Re-check state, some devices bounce back
                pwrState = com.topjohnwu.superuser.Shell.cmd("dumpsys power | grep mWakefulness").exec().out.joinToString("")
                if (pwrState.contains("Asleep")) {
                    com.topjohnwu.superuser.Shell.cmd("input keyevent 224").exec()
                    kotlinx.coroutines.delay(1000)
                }
            } else {
                // Already awake, just secure stayon
                com.topjohnwu.superuser.Shell.cmd("svc power stayon true").exec()
            }
            // Dismiss lock screen explicitly every single time
            com.topjohnwu.superuser.Shell.cmd("wm dismiss-keyguard").exec()
            kotlinx.coroutines.delay(600)
            val wmRes = com.topjohnwu.superuser.Shell.cmd("wm size").exec()
            val wmL = wmRes.out.lastOrNull { it.contains("x") } ?: "1080x2400"
            val rp = wmL.substringAfter(":").trim().split("x")
            val dW = rp[0].trim().toInt(); val dH = rp[1].trim().toInt()
            com.topjohnwu.superuser.Shell.cmd("input swipe ${dW/2} ${(dH*0.85).toInt()} ${dW/2} ${(dH*0.25).toInt()} 300").exec()
            
            // Allow more time for MIUI lock screen dismissal animation before we throw the Intent
            kotlinx.coroutines.delay(1500)
            
            // MIUI workaround: Ensure Google Messages has background activity launch permission
            Shell.cmd(
                "appops set $MESSAGES_PKG AUTO_START allow 2>/dev/null",
                "appops set $MESSAGES_PKG MIUI_BACKGROUND_START allow 2>/dev/null",
                "cmd appops set $MESSAGES_PKG RUN_IN_BACKGROUND allow 2>/dev/null",
                "cmd appops set $MESSAGES_PKG RUN_ANY_IN_BACKGROUND allow 2>/dev/null",
                "settings put global hidden_api_policy 1 2>/dev/null"
            ).exec()

            // [PHASE 8 NATIVE ACCESSIBILITY INJECTION]: Ultra-low latency UI bypass.
            // Dispatch standard Intent to open the chat window, and use the in-memory Accessibility tree to blindly click the physical Send button.
            val startCmd = java.lang.StringBuilder("am start -W -a android.intent.action.SENDTO ")
                .append("-d 'smsto:$cleanTo' ")
                .append("--es sms_body '$safeText'")
            
            Shell.cmd(startCmd.toString()).exec()
            
            // Verify Google Messages actually launched — retry up to 2 times if it didn't
            var messagesLaunched = false
            for (attempt in 1..3) {
                kotlinx.coroutines.delay(800L)
                val focusedApp = Shell.cmd("dumpsys window | grep mCurrentFocus").exec().out.joinToString("")
                if (focusedApp.contains(MESSAGES_PKG)) {
                    messagesLaunched = true
                    Timber.i("Google Messages confirmed in foreground (attempt $attempt)")
                    break
                }
                Timber.w("Google Messages NOT in foreground (attempt $attempt): $focusedApp — retrying intent...")
                Shell.cmd(startCmd.toString()).exec()
                kotlinx.coroutines.delay(500L) // small delay after retry
            }
            
            if (!messagesLaunched) {
                Timber.e("Google Messages failed to launch after 3 attempts — will still try to find Send button")
            }
            
            // Attempt 0-latency native memory-mapped tap
            com.messagingagent.android.service.MessagingUiAutomatorService.lastFoundSendButtonRect = null
            var clicked = false
            
            for (clickAttempt in 1..4) {
                kotlinx.coroutines.delay(500L)
                clicked = com.messagingagent.android.service.MessagingUiAutomatorService.clickSendButton()
                
                // Jetpack Compose Accessibility click bug fallback:
                if (!clicked) {
                    val rect = com.messagingagent.android.service.MessagingUiAutomatorService.lastFoundSendButtonRect
                    if (rect != null) {
                        val cx = rect.centerX()
                        val cy = rect.centerY()
                        Timber.w("Guaranteed Fallback: Executing raw system tap at bounding box ($cx, $cy) to bypass Compose click filters")
                        Shell.cmd("input tap $cx $cy").exec()
                        clicked = true
                    }
                }
                if (clicked) break
                Timber.i("Send button not found natively yet (attempt $clickAttempt). Waiting...")
            }
            
            if (!clicked && cachedSendButtonX != -1 && cachedSendButtonY != -1) {
                Timber.w("Uiautomator Dump skipped -> Using Cached Send Button coordinates ($cachedSendButtonX, $cachedSendButtonY)")
                Shell.cmd("input tap $cachedSendButtonX $cachedSendButtonY").exec()
                clicked = true
            }

            if (!clicked) {
                Timber.w("Accessibility service failed to find Send button. Executing Uiautomator Dump Fallback...")
                Shell.cmd("uiautomator dump /data/local/tmp/ma_dump.xml").exec()
                val xmlRes = Shell.cmd("cat /data/local/tmp/ma_dump.xml").exec()
                val xmlOut = xmlRes.out.joinToString("\n")
                
                val regexes = listOf(
                    """resource-id="Compose:Draft:Send"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"""",
                    """resource-id="send_message_button_icon"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"""",
                    """content-desc="[^"]*(?i)(send|αποστολ|enviar)[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]""""
                ).map { it.toRegex(RegexOption.IGNORE_CASE) }
                
                var cx = -1
                var cy = -1
                
                for (regex in regexes) {
                    val match = regex.find(xmlOut)
                    if (match != null && match.groupValues.size >= 5) {
                        val x1 = match.groupValues[1].toInt()
                        val y1 = match.groupValues[2].toInt()
                        val x2 = match.groupValues[3].toInt()
                        val y2 = match.groupValues[4].toInt()
                        cx = (x1 + x2) / 2
                        cy = (y1 + y2) / 2
                        break
                    }
                }
                
                if (cx != -1 && cy != -1) {
                    Timber.w("Uiautomator Dump matched Send Button at ($cx, $cy)! Executing tap & saving to cache...")
                    cachedSendButtonX = cx
                    cachedSendButtonY = cy
                    Shell.cmd("input tap $cx $cy").exec()
                    clicked = true
                }
            }

            if (!clicked) {
                Timber.e("Native UI Accessibility and Uiautomator both failed to click the Send button")
                com.messagingagent.android.service.DeviceLogBus.log("ERROR", "RCS: UI Interaction failed for $cleanTo")
                // Last ditch effort: send keyevent 22 + 66
                Shell.cmd("input keyevent 22", "input keyevent 66").exec()
            }

            Timber.i("RCS dispatch tapped for $to. correlationId=$correlationId")
            
            dlrTracker.addPending(PendingDlr(
                correlationId = correlationId,
                destinationAddress = cleanTo,
                initialMaxId = initialMaxId,
                deviceToken = deviceToken,
                bugleMessageId = 0L,
                dlrDelayMinSec = dlrDelayMinSec,
                dlrDelayMaxSec = dlrDelayMaxSec
            ))
            
            com.messagingagent.android.service.DeviceLogBus.log("INFO", "RCS: ✓ Dispatched to $cleanTo (corr=$correlationId)")
            return RcsSendResult(success = true, sentEarly = true)
        } catch (e: Exception) {
            Timber.e(e, "RCS root dispatch exception for $to")
            com.messagingagent.android.service.DeviceLogBus.log("ERROR", "RCS: Exception for $to: ${e.message} (corr=$correlationId)")
            return RcsSendResult(success = false, noRcs = false, error = e.message)
        }
        } finally {
            dispatchMutex.unlock()
        }
    }

    private fun isMessagesInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(MESSAGES_PKG, 0)
            true
        } catch (e: Exception) { false }
    }
}
