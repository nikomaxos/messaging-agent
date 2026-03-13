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
                    "-p $MESSAGES_PKG"

            Timber.i("Executing root RCS intent: $intentCmd")
            val shellResult = Shell.cmd(intentCmd).exec()

            if (!shellResult.isSuccess) {
                Timber.e("Root intent dispatch failed: ${shellResult.err}")
                return RcsSendResult(success = false, error = "am start failed: ${shellResult.err}")
            }

            // Compose mode 'true' leaves the UI open so we can safely script the UI clicks.
            // TAB (61) jumps focus to the Send Button. ENTER (66) presses it. 
            // HOME (3) exits the app to the background so the user isn't interrupted.
            Shell.cmd(
                "sleep 1.5",
                "input keyevent 61", // TAB to Send button
                "input keyevent 66", // ENTER to click Send
                "sleep 0.5",
                "input keyevent 22", // Fallback DPAD_RIGHT just in case
                "input keyevent 66", // Fallback ENTER
                "sleep 1",
                "input keyevent 3"   // HOME key to hide visual interface
            ).exec()

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

