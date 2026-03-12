package com.messagingagent.android.rcs

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.messagingagent.android.service.DeliveryResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observes the Android MMS/RCS message content provider to detect delivery
 * confirmations from Google Messages after an RCS send attempt.
 *
 * Strategy:
 *   1. Before sending, record the current highest message ID in the MMS thread
 *   2. Fire the RCS intent
 *   3. Wait (via ContentObserver on content://mms-sms/conversations) for a new
 *      message to appear with type=SENT (2) or type=FAILED (5)
 *   4. Return the result upstream
 *
 * The observer uses a callbackFlow so it is coroutine-friendly and auto-cleans up.
 *
 * Root note: Reading content://mms-sms/ does NOT require root; it requires the
 * READ_SMS permission which is declared in the manifest.
 */
@Singleton
class RcsDeliveryObserver @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        // MMS/SMS provider URIs
        private val SMS_URI         = Uri.parse("content://sms")
        private val CONVERSATIONS   = Uri.parse("content://mms-sms/conversations")
        private const val COL_TYPE  = "type"
        private const val COL_ID    = "_id"
        private const val COL_ADDR  = "address"

        // SMS message types
        private const val TYPE_SENT   = 2
        private const val TYPE_FAILED = 5

        // How long to wait for a delivery confirmation before timing out
        private const val RECEIPT_TIMEOUT_MS = 30_000L
    }

    /**
     * Wait for a delivery receipt for messages sent to [toAddress].
     *
     * @param toAddress destination phone number
     * @return DeliveryStatus.DELIVERED, FAILED, or TIMEOUT
     */
    suspend fun awaitDelivery(toAddress: String): RcsDeliveryStatus {
        val result = withTimeoutOrNull(RECEIPT_TIMEOUT_MS) {
            observeSmsThread(toAddress).first()
        }
        return result ?: RcsDeliveryStatus.TIMEOUT
    }

    /**
     * Returns a cold Flow that emits once when a sent/failed status appears
     * for messages addressed to [toAddress].
     */
    private fun observeSmsThread(toAddress: String) = callbackFlow<RcsDeliveryStatus> {
        val resolver: ContentResolver = context.contentResolver
        val normalizedTo = normalizeNumber(toAddress)

        // Record latest known message ID before the intent fires
        val priorMaxId = queryMaxId(resolver)
        Timber.d("RcsDeliveryObserver: watching for delivery to $normalizedTo (priorMaxId=$priorMaxId)")

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                checkForDelivery(resolver, normalizedTo, priorMaxId)?.let { status ->
                    Timber.i("RCS delivery status for $toAddress: $status")
                    trySend(status)
                    close()   // stop observing after first result
                }
            }
        }

        resolver.registerContentObserver(CONVERSATIONS, true, observer)

        awaitClose {
            resolver.unregisterContentObserver(observer)
            Timber.d("RcsDeliveryObserver unregistered for $toAddress")
        }
    }

    /**
     * Query the SMS table for any new message (ID > priorMaxId) sent to [toAddress]
     * with type SENT or FAILED.
     */
    private fun checkForDelivery(resolver: ContentResolver, toAddress: String, priorMaxId: Long): RcsDeliveryStatus? {
        val cursor = resolver.query(
            SMS_URI,
            arrayOf(COL_ID, COL_TYPE, COL_ADDR),
            "$COL_ID > ? AND $COL_ADDR LIKE ?",
            arrayOf(priorMaxId.toString(), "%${toAddress.takeLast(7)}%"),
            "$COL_ID DESC"
        ) ?: return null

        cursor.use {
            if (!it.moveToFirst()) return null
            val typeIdx = it.getColumnIndexOrThrow(COL_TYPE)
            return when (it.getInt(typeIdx)) {
                TYPE_SENT   -> RcsDeliveryStatus.DELIVERED
                TYPE_FAILED -> RcsDeliveryStatus.FAILED
                else        -> null   // still in-flight (outbox / sending)
            }
        }
    }

    private fun queryMaxId(resolver: ContentResolver): Long {
        val cursor = resolver.query(SMS_URI, arrayOf("MAX(_id) as max_id"),
            null, null, null) ?: return 0L
        return cursor.use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }
    }

    private fun normalizeNumber(number: String): String =
        number.replace(Regex("[^0-9+]"), "").let {
            if (it.length > 7) it.takeLast(7) else it
        }
}

enum class RcsDeliveryStatus { DELIVERED, FAILED, TIMEOUT }
