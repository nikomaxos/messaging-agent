package com.messagingagent.android.rcs

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

data class RcsSendResult(
    val success: Boolean,
    val noRcs: Boolean = false,
    val error: String? = null
)

/**
 * RCS message sender using the Android Messages intent API.
 *
 * Flow:
 *  1. Record current highest SMS ID (for ContentObserver baseline)
 *  2. Fire smsto: intent → Google Messages upgrades to RCS if capable
 *  3. Wait up to 30s via [RcsDeliveryObserver] for delivery receipt
 *  4. Return result (DELIVERED / NO_RCS-equivalent FAILED / TIMEOUT)
 *
 * If Google Messages is not installed, the intent fails and noRcs=true
 * is returned so the backend can signal ESME_RDELIVERYFAILURE to upstream.
 */
@Singleton
class RcsSender @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deliveryObserver: RcsDeliveryObserver
) {

    private companion object {
        const val MESSAGES_PKG = "com.google.android.apps.messaging"
    }

    /**
     * Attempt to send an RCS simple text message to [to].
     *
     * @param to   Destination phone number (E.164 or local format)
     * @param text SMS/RCS message body
     * @return [RcsSendResult] with success, noRcs, or error details
     */
    suspend fun sendRcs(to: String, text: String): RcsSendResult {
        // Verify Google Messages is installed
        if (!isMessagesInstalled()) {
            Timber.w("Google Messages not installed — cannot send RCS to $to")
            return RcsSendResult(success = false, noRcs = true,
                error = "Google Messages not installed")
        }

        return try {
            // Build the intent
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:$to")
                putExtra("sms_body", text)
                putExtra("compose_mode", false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage(MESSAGES_PKG)
            }

            // Fire the intent, then await the delivery observer
            context.startActivity(intent)
            Timber.i("RCS intent fired for $to")

            // awaitDelivery is a suspend fun — call it directly in this coroutine
            val status: RcsDeliveryStatus = deliveryObserver.awaitDelivery(to)

            when (status) {
                RcsDeliveryStatus.DELIVERED -> {
                    Timber.i("RCS delivered to $to")
                    RcsSendResult(success = true)
                }
                RcsDeliveryStatus.FAILED -> {
                    Timber.w("RCS failed (no RCS capability?) for $to")
                    RcsSendResult(success = false, noRcs = true, error = "RCS delivery failed")
                }
                RcsDeliveryStatus.TIMEOUT -> {
                    Timber.w("RCS delivery receipt timed out for $to")
                    RcsSendResult(success = true, error = "Delivery receipt timeout (assumed sent)")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "RCS send exception for $to")
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
