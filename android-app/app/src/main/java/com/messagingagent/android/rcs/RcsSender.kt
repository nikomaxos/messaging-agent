package com.messagingagent.android.rcs

import android.content.Context
import com.topjohnwu.superuser.Shell
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

data class RcsSendResult(
    val success: Boolean,
    val noRcs: Boolean = false,
    val error: String? = null
)

@Singleton
class RcsSender @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deliveryObserver: RcsDeliveryObserver
) {

    private companion object {
        const val MESSAGES_PKG = "com.google.android.apps.messaging"
    }

    suspend fun sendRcs(to: String, text: String): RcsSendResult {
        if (!isMessagesInstalled()) {
            Timber.w("Google Messages not installed — cannot send RCS to $to")
            return RcsSendResult(success = false, noRcs = true, error = "Google Messages not installed")
        }

        return try {
            val safeText = text.replace("'", "'\\''")
            val cleanTo = to.replace("[^0-9+]".toRegex(), "")

            val intentCmd = "am start -a android.intent.action.SENDTO " +
                    "-d smsto:$cleanTo " +
                    "--es sms_body '$safeText' " +
                    "--ez compose_mode true " +
                    "-f 0x14000000 " +
                    "-p $MESSAGES_PKG"

            Timber.i("Executing root RCS intent: $intentCmd")
            val shellResult = Shell.cmd(intentCmd).exec()

            if (!shellResult.isSuccess) {
                Timber.e("Root intent dispatch failed: ${shellResult.err}")
                return RcsSendResult(success = false, error = "am start failed: ${shellResult.err}")
            }

            // Wait for Google Messages UI to render
            kotlinx.coroutines.delay(1500)

            // Automate clicking the exact Send button using uiautomator bounds matching
            var clicked = false
            for (i in 0 until 4) {
                val dumpRes = Shell.cmd("uiautomator dump /data/local/tmp/dump.xml && cat /data/local/tmp/dump.xml").exec()
                val xml = dumpRes.out.joinToString(" ")

                // Match exact button elements for sending
                val sendBtnRegex = """resource-id="[^"]*send_message_button[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"""".toRegex(RegexOption.IGNORE_CASE)
                val sendDescRegex = """content-desc="(?i)send\s+[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"""".toRegex()
                
                val match = sendBtnRegex.find(xml) ?: sendDescRegex.find(xml)
                
                if (match != null) {
                    val x1 = match.groupValues[1].toInt()
                    val y1 = match.groupValues[2].toInt()
                    val x2 = match.groupValues[3].toInt()
                    val y2 = match.groupValues[4].toInt()
                    val cx = (x1 + x2) / 2
                    val cy = (y1 + y2) / 2
                    
                    Timber.i("Found send button at $cx, $cy. Tapping...")
                    Shell.cmd("input tap $cx $cy").exec()
                    clicked = true
                    kotlinx.coroutines.delay(600)
                    Shell.cmd("input keyevent 3").exec() // HOME key
                    break
                }
                
                Timber.w("Send button not found in UI dump. Retrying (attempt ${i + 1}/4)...")
                kotlinx.coroutines.delay(1000)
            }
            
            if (!clicked) {
                Timber.e("Failed to find or click the Send button in Google Messages. Aborting process.")
                Shell.cmd("input keyevent 3").exec() // Send Android back to Home to clear screen
                return RcsSendResult(success = false, error = "Failed to locate send button via UI Automator")
            }

            // Observe the SMS/MMS content provider for physical dispatch confirmation 
            val status: RcsDeliveryStatus = deliveryObserver.awaitDelivery(to)

            when (status) {
                RcsDeliveryStatus.DELIVERED -> {
                    Timber.i("RCS delivered and recorded for $to")
                    RcsSendResult(success = true)
                }
                RcsDeliveryStatus.FAILED -> {
                    Timber.w("RCS failed (fallback to SMS required) for $to")
                    RcsSendResult(success = false, noRcs = true, error = "RCS delivery refused by carrier")
                }
                RcsDeliveryStatus.TIMEOUT -> {
                    Timber.w("RCS delivery receipt timed out for $to")
                    RcsSendResult(success = false, error = "Delivery receipt timeout (message stuck in Outbox)")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "RCS root dispatch exception for $to")
            RcsSendResult(success = false, noRcs = false, error = e.message)
        }
    }

    private fun isMessagesInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(MESSAGES_PKG, 0)
            true
        } catch (e: Exception) { false }
    }
}

