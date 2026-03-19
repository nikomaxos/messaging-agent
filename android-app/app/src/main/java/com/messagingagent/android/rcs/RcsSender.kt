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
    val error: String? = null
)

@Singleton
class RcsSender @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dlrTracker: PendingDlrTracker
) {

    private companion object {
        const val MESSAGES_PKG = "com.google.android.apps.messaging"
        var cachedSendX: Int = -1
        var cachedSendY: Int = -1
        var cachedDialogX: Int = -1
        var cachedDialogY: Int = -1
    }

    private var sentMessageCount = 0

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
            com.topjohnwu.superuser.Shell.cmd("cp '$srcDb' '$tmpDb' && chown $uid:$uid '$tmpDb' && chmod 600 '$tmpDb'").exec()
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

            // Wait minimally for Google Messages UI to render
            kotlinx.coroutines.delay(400)

            var clicked = false

            // FAST PATH: If we already know the exact screen coordinates from a previous message, tap them instantly!
            if (cachedSendX > 0 && cachedSendY > 0) {
                Timber.i("Fast-path: Tapping cached Send button at $cachedSendX, $cachedSendY")
                Shell.cmd("input tap $cachedSendX $cachedSendY").exec()
                clicked = true
                kotlinx.coroutines.delay(200)

                // If a dialog was previously detected and its coords cached, tap it just in case
                if (cachedDialogX > 0 && cachedDialogY > 0) {
                    Shell.cmd("input tap $cachedDialogX $cachedDialogY").exec()
                    kotlinx.coroutines.delay(100)
                }

                // Verify it actually sent by looking at the DB immediately (the status should change from draft/unsent to sending/sent)
                var fastPathSuccess = false
                for (j in 0 until 3) {
                    val pDb = "${context.filesDir.absolutePath}/bug_fast.db"
                    val pSrc = "/data/data/com.google.android.apps.messaging/databases/bugle_db"
                    val pUid = android.os.Process.myUid()
                    com.topjohnwu.superuser.Shell.cmd("cp '$pSrc' '$pDb' && cp '$pSrc-wal' '$pDb-wal' 2>/dev/null; cp '$pSrc-shm' '$pDb-shm' 2>/dev/null; chown $pUid:$pUid '$pDb' '$pDb-wal' '$pDb-shm' 2>/dev/null; chmod 600 '$pDb' '$pDb-wal' '$pDb-shm' 2>/dev/null").exec()
                    try {
                        android.database.sqlite.SQLiteDatabase.openDatabase(pDb, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
                            db.rawQuery("SELECT _id FROM messages WHERE _id > $initialMaxId ORDER BY _id ASC LIMIT 1", null).use { cursor ->
                                if (cursor.moveToFirst()) {
                                    fastPathSuccess = true
                                }
                            }
                        }
                    } catch (e: Exception) { }
                    Shell.cmd("rm -f $pDb", "rm -f $pDb-wal", "rm -f $pDb-shm").exec()
                    
                    if (fastPathSuccess) break
                    kotlinx.coroutines.delay(200)
                }

                if (fastPathSuccess) {
                    Timber.i("Fast-path successful. Screen tap was recognized.")
                    Shell.cmd("input keyevent 3").exec() // HOME key
                } else {
                    Timber.w("Fast-path tap failed to register a new message. Falling back to slow UiAutomator dump...")
                    clicked = false // Reset and proceed to slow path
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
                
                val match = sendBtnRegex.find(xml) ?: sendDescRegex.find(xml)
                
                if (match != null) {
                    val fullNode = match.value
                    if (fullNode.contains("SMS", ignoreCase = true)) {
                        Timber.w("RCS not available (Send button indicates SMS: $fullNode). Aborting dispatch to $to.")
                        Shell.cmd("input keyevent 3").exec()
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

                    Shell.cmd("input keyevent 3").exec() // HOME key
                    break
                }
                
                Timber.w("Send button not found in UI dump. Retrying (attempt ${i + 1}/6)...")
                kotlinx.coroutines.delay(200)
            }
            } // Close slow path block
            
            if (!clicked) {
                Timber.e("Failed to find or click the Send button in Google Messages. Aborting process.")
                Shell.cmd("input keyevent 3").exec() // Send Android back to Home to clear screen
                return RcsSendResult(success = false, error = "Failed to locate send button via UI Automator")
            }

            // The "Send" button has been tapped. Capture the exact bugle_db _id of the new message.
            var bugleMessageId = 0L
            val capDb = "${context.filesDir.absolutePath}/bug_cap.db"
            val capSrc = "/data/data/com.google.android.apps.messaging/databases/bugle_db"
            val capUid = android.os.Process.myUid()
            com.topjohnwu.superuser.Shell.cmd("cp '$capSrc' '$capDb' && cp '$capSrc-wal' '$capDb-wal' 2>/dev/null; cp '$capSrc-shm' '$capDb-shm' 2>/dev/null; chown $capUid:$capUid '$capDb' '$capDb-wal' '$capDb-shm' 2>/dev/null; chmod 600 '$capDb' '$capDb-wal' '$capDb-shm' 2>/dev/null").exec()
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

    private fun isMessagesInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(MESSAGES_PKG, 0)
            true
        } catch (e: Exception) { false }
    }
}

