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
        var cachedSendX: Int = -1
        var cachedSendY: Int = -1
        var cachedDialogX: Int = -1
        var cachedDialogY: Int = -1
    }

    private var sentMessageCount = 0
    private var consecutiveSendButtonFailures = 0
    private val RESTART_THRESHOLD = 3

    suspend fun sendRcs(correlationId: String, deviceToken: String, to: String, text: String, subscriptionId: Int, dlrDelayMinSec: Int = 2, dlrDelayMaxSec: Int = 5): RcsSendResult {
        if (!isMessagesInstalled()) {
            Timber.w("Google Messages not installed — cannot send RCS to $to")
            return RcsSendResult(success = false, noRcs = true, error = "Google Messages not installed")
        }

        return try {
            val safeText = text.replace("'", "'\\''")
            val cleanTo = to.replace("[^0-9+]".toRegex(), "")

            // Wake up screen and dismiss keyguard via root
            Timber.i("Waking up screen and dismissing keyguard")
            com.topjohnwu.superuser.Shell.cmd("input keyevent KEYCODE_WAKEUP").exec()
            com.topjohnwu.superuser.Shell.cmd("wm dismiss-keyguard").exec()
            kotlinx.coroutines.delay(500)

            var initialMaxId = 0L
            val tmpDb = "${context.filesDir.absolutePath}/bug_init.db"
            val srcDb = "/data/data/com.google.android.apps.messaging/databases/bugle_db"
            val uid = android.os.Process.myUid()
            com.topjohnwu.superuser.Shell.cmd("cp '$srcDb' '$tmpDb' && rm -f '$tmpDb-wal' '$tmpDb-shm' && chown $uid:$uid '$tmpDb' && chmod 600 '$tmpDb'").exec()
            try {
                android.database.sqlite.SQLiteDatabase.openDatabase(tmpDb, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("SELECT MAX(_id) FROM messages", null).use { cursor ->
                        if (cursor.moveToFirst()) {
                            initialMaxId = cursor.getLong(0)
                        }
                    }
                }
            } catch (e: Exception) { Timber.e(e, "Failed to capture initial bugle MAX(_id)") }
            com.topjohnwu.superuser.Shell.cmd("rm -f $tmpDb", "rm -f $tmpDb-wal", "rm -f $tmpDb-shm").exec()
            Timber.i("Captured initial bugle_db MAX(_id): $initialMaxId")

            val intentCmd = java.lang.StringBuilder("am start -a android.intent.action.SENDTO " +
                    "-d smsto:$cleanTo " +
                    "--es sms_body '$safeText' " +
                    "--ez compose_mode true " +
                    "-f 0x14000000 ")

            if (subscriptionId >= 0) {
                intentCmd.append("--ei \"android.intent.extra.SIM_SUBSCRIPTION_ID\" $subscriptionId ")
                intentCmd.append("--ei \"android.telephony.extra.SUBSCRIPTION_INDEX\" $subscriptionId ")
            }

            intentCmd.append("-p $MESSAGES_PKG")

            Timber.i("Executing root RCS intent: $intentCmd")
            val shellResult = Shell.cmd(intentCmd.toString()).exec()

            if (!shellResult.isSuccess) {
                Timber.e("Root intent dispatch failed: ${shellResult.err}")
                return RcsSendResult(success = false, error = "am start failed: ${shellResult.err}")
            }

            // Wait for Google Messages UI to render and sms_body to populate the compose field
            kotlinx.coroutines.delay(800)

            var clicked = false

            // FAST PATH: If we already know the exact screen coordinates from a previous message, tap them instantly!
            if (cachedSendX > 0 && cachedSendY > 0) {
                Timber.i("Fast-path: Tapping cached Send button at $cachedSendX, $cachedSendY")
                Shell.cmd("input tap $cachedSendX $cachedSendY").exec()
                clicked = true
                kotlinx.coroutines.delay(100)

                // If a dialog was previously detected and its coords cached, tap it just in case
                if (cachedDialogX > 0 && cachedDialogY > 0) {
                    Shell.cmd("input tap $cachedDialogX $cachedDialogY").exec()
                }

                // Quick single-attempt DB verify — confirm the tap actually sent the message.
                // Without this, a missed tap causes the DLR watchdog to pick up the NEXT
                // message's DB entry and falsely attribute delivery to the missed one.
                kotlinx.coroutines.delay(500)
                var fastPathBugleId = 0L
                val pDb = "${context.filesDir.absolutePath}/bug_fast.db"
                val pSrc = "/data/data/com.google.android.apps.messaging/databases/bugle_db"
                val pUid = android.os.Process.myUid()
                try {
                    com.topjohnwu.superuser.Shell.cmd("cp '$pSrc' '$pDb' && rm -f '$pDb-wal' '$pDb-shm' && chown $pUid:$pUid '$pDb' && chmod 600 '$pDb'").exec()
                    android.database.sqlite.SQLiteDatabase.openDatabase(pDb, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
                        db.rawQuery("SELECT _id FROM messages WHERE _id > $initialMaxId ORDER BY _id ASC LIMIT 1", null).use { cursor ->
                            if (cursor.moveToFirst()) fastPathBugleId = cursor.getLong(0)
                        }
                    }
                } catch (e: Exception) { Timber.w(e, "Fast-path DB check failed") }
                Shell.cmd("rm -f $pDb", "rm -f $pDb-wal", "rm -f $pDb-shm").exec()

                if (fastPathBugleId > 0) {
                    // Guard: if another pending DLR already claimed this _id, don't duplicate
                    val isDuplicate = dlrTracker.hasPendingWithBugleId(fastPathBugleId)
                    if (isDuplicate) {
                        Timber.w("Fast-path _id=$fastPathBugleId already claimed by another pending DLR — falling back to slow path")
                        clicked = false
                    } else {
                        Timber.i("Fast-path verified: message entered bugle_db as _id=$fastPathBugleId — sending SENT immediately")
                        exitMessagesCleanly()
                        dlrTracker.addPending(PendingDlr(
                            correlationId = correlationId,
                            destinationAddress = cleanTo,
                            initialMaxId = initialMaxId,
                            deviceToken = deviceToken,
                            bugleMessageId = fastPathBugleId,
                            dlrDelayMinSec = dlrDelayMinSec,
                            dlrDelayMaxSec = dlrDelayMaxSec
                        ))
                        return RcsSendResult(success = true, sentEarly = true)
                    }
                } else {
                    Timber.w("Fast-path tap did NOT register in bugle_db — falling back to slow path")
                    clicked = false // Reset — slow path will re-discover and re-tap
                }
            }

            // SLOW PATH: First time parsing the layout, or fast-path missed due to keyboard size changes
            if (!clicked) {
                for (i in 0 until 6) {
                    val dumpRes = Shell.cmd("uiautomator dump /data/local/tmp/dump.xml && cat /data/local/tmp/dump.xml").exec()
                val xml = dumpRes.out.joinToString(" ")

                // Match exact button elements for sending, capturing the entire <node> tag to inspect attributes
                val sendBtnRegex = """<node[^>]*resource-id="[^"]*send_message_button[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*>""".toRegex(RegexOption.IGNORE_CASE)
                val sendDescRegex = """<node[^>]*content-desc="(?i)send\s+[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*>""".toRegex()
                // Newer Google Messages versions use Jetpack Compose test tags
                val composeSendRegex = """<node[^>]*resource-id="Compose:Draft:Send"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*>""".toRegex()
                
                val match = sendBtnRegex.find(xml) ?: composeSendRegex.find(xml) ?: sendDescRegex.find(xml)
                
                if (match != null) {
                    val fullNode = match.value
                    if (fullNode.contains("SMS", ignoreCase = true)) {
                        Timber.w("RCS not available (Send button indicates SMS: $fullNode). Aborting dispatch to $to.")
                        exitMessagesCleanly()
                        return RcsSendResult(success = false, noRcs = true, error = "Recipient does not have RCS capabilities")
                    }

                    val x1 = match.groupValues[1].toInt()
                    val y1 = match.groupValues[2].toInt()
                    val x2 = match.groupValues[3].toInt()
                    val y2 = match.groupValues[4].toInt()
                    val cx = (x1 + x2) / 2
                    val cy = (y1 + y2) / 2
                    
                    Timber.i("Found send button at $cx, $cy. Tapping and caching coordinates...")
                    cachedSendX = cx
                    cachedSendY = cy

                    Shell.cmd("input tap $cx $cy").exec()
                    clicked = true
                    kotlinx.coroutines.delay(200)
                    
                    // Check for any MMS conversion/warning dialogs and click OK/Send
                    val postDumpRes = Shell.cmd("uiautomator dump /data/local/tmp/dump.xml && cat /data/local/tmp/dump.xml").exec()
                    val postXml = postDumpRes.out.joinToString(" ")
                    val dialogBtnRegex = """text="(?i)(send|ok|continue|accept)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"""".toRegex()
                    val dialogMatch = dialogBtnRegex.find(postXml)
                    if (dialogMatch != null && (postXml.contains("multimedia", ignoreCase = true) || postXml.contains("mms", ignoreCase = true) || postXml.contains("charges", ignoreCase = true))) {
                        val dx1 = dialogMatch.groupValues[2].toInt()
                        val dy1 = dialogMatch.groupValues[3].toInt()
                        val dx2 = dialogMatch.groupValues[4].toInt()
                        val dy2 = dialogMatch.groupValues[5].toInt()
                        val dcx = (dx1+dx2)/2
                        val dcy = (dy1+dy2)/2
                        Timber.i("Found MMS confirmation dialog. Tapping OK/Send at $dcx, $dcy and caching coordinates...")
                        cachedDialogX = dcx
                        cachedDialogY = dcy
                        Shell.cmd("input tap $dcx $dcy").exec()
                        kotlinx.coroutines.delay(200)
                    }

                    exitMessagesCleanly()
                    break
                }
                
                Timber.w("Send button not found in UI dump. Retrying (attempt ${i + 1}/6)...")
                kotlinx.coroutines.delay(300)
            }
            } // Close slow path block
            
            if (!clicked) {
                consecutiveSendButtonFailures++
                Timber.e("Failed to find or click the Send button in Google Messages. Consecutive failures: $consecutiveSendButtonFailures/$RESTART_THRESHOLD")
                exitMessagesCleanly()
                
                if (consecutiveSendButtonFailures >= RESTART_THRESHOLD) {
                    val selfHealingOn = kotlinx.coroutines.runBlocking { prefs.isSelfHealingEnabled() }
                    if (selfHealingOn) {
                        Timber.w("🔄 RESTART THRESHOLD REACHED ($RESTART_THRESHOLD consecutive send-button failures). Self-healing enabled — restarting Google Messages...")
                        restartGoogleMessages()
                    } else {
                        Timber.w("🔄 RESTART THRESHOLD REACHED ($RESTART_THRESHOLD consecutive send-button failures). Self-healing DISABLED — skipping restart.")
                        consecutiveSendButtonFailures = 0  // Reset counter anyway to avoid spam logging
                    }
                }
                
                return RcsSendResult(success = false, error = "Failed to locate send button via UI Automator (fail #$consecutiveSendButtonFailures)")
            }
            
            // Send button was successfully clicked — reset failure counter
            consecutiveSendButtonFailures = 0

            // The "Send" button has been tapped. Capture the exact bugle_db _id of the new message.
            var bugleMessageId = 0L
            val capDb = "${context.filesDir.absolutePath}/bug_cap.db"
            val capSrc = "/data/data/com.google.android.apps.messaging/databases/bugle_db"
            val capUid = android.os.Process.myUid()
            com.topjohnwu.superuser.Shell.cmd("cp '$capSrc' '$capDb' && rm -f '$capDb-wal' '$capDb-shm' && chown $capUid:$capUid '$capDb' && chmod 600 '$capDb'").exec()
            try {
                android.database.sqlite.SQLiteDatabase.openDatabase(capDb, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("SELECT _id FROM messages WHERE _id > ? ORDER BY _id ASC LIMIT 1", arrayOf(initialMaxId.toString())).use { cursor ->
                        if (cursor.moveToFirst()) bugleMessageId = cursor.getLong(0)
                    }
                }
            } catch (e: Exception) { Timber.e(e, "Failed to capture bugle_db message _id") }
            com.topjohnwu.superuser.Shell.cmd("rm -f '$capDb' '$capDb-wal' '$capDb-shm' '$capDb-journal'").exec()

            Timber.i("RCS dispatch tapped for $to. bugleMessageId=$bugleMessageId, correlationId=$correlationId")
            dlrTracker.addPending(PendingDlr(
                correlationId = correlationId,
                destinationAddress = cleanTo,
                initialMaxId = initialMaxId,
                deviceToken = deviceToken,
                bugleMessageId = bugleMessageId,
                dlrDelayMinSec = dlrDelayMinSec,
                dlrDelayMaxSec = dlrDelayMaxSec
            ))
            
            return RcsSendResult(success = true)
        } catch (e: Exception) {
            Timber.e(e, "RCS root dispatch exception for $to")
            return RcsSendResult(success = false, noRcs = false, error = e.message)
        }
    }

    /**
     * Exit Google Messages by pressing HOME.
     * HOME is safe — it doesn't affect our own app's activity stack.
     * The am start intent flags (CLEAR_TOP | NEW_TASK) handle fresh state on next launch.
     */
    private suspend fun exitMessagesCleanly() {
        Shell.cmd("input keyevent 3").exec()   // HOME
        kotlinx.coroutines.delay(300)          // Let launcher fully appear before next am start
    }

    private fun isMessagesInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(MESSAGES_PKG, 0)
            true
        } catch (e: Exception) { false }
    }

    /**
     * Force-stop Google Messages, clear cached coordinates, wait, and re-launch.
     * Called when consecutive send-button failures reach the threshold.
     * Safe: each failed message was already reported as ERROR to backend before this runs.
     */
    private suspend fun restartGoogleMessages() {
        try {
            // 1. Force-stop Google Messages
            Timber.i("🛑 Force-stopping Google Messages...")
            Shell.cmd("am force-stop $MESSAGES_PKG").exec()
            kotlinx.coroutines.delay(1000)

            // 2. Clear cached button coordinates — they may be stale after restart
            cachedSendX = -1
            cachedSendY = -1
            cachedDialogX = -1
            cachedDialogY = -1
            Timber.i("🧹 Cleared cached button coordinates")

            // 3. Wait for the app to fully stop
            kotlinx.coroutines.delay(2000)

            // 4. Re-launch Google Messages to its main activity so it reinitializes
            Timber.i("🚀 Re-launching Google Messages...")
            Shell.cmd("am start -n $MESSAGES_PKG/.ui.ConversationListActivity").exec()
            kotlinx.coroutines.delay(2000)

            // 5. Go back to home so the next dispatch gets a clean state
            Shell.cmd("input keyevent 3").exec()  // HOME
            kotlinx.coroutines.delay(500)

            // 6. Reset counter — give the fresh app a chance
            consecutiveSendButtonFailures = 0
            Timber.i("✅ Google Messages restarted successfully. Counter reset.")
        } catch (e: Exception) {
            Timber.e(e, "Failed to restart Google Messages")
        }
    }
}
