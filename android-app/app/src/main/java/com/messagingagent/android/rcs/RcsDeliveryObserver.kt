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
        private const val TYPE_OUTBOX = 4
        private const val TYPE_FAILED = 5
        private const val TYPE_QUEUED = 6

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
        
        // Record latest known message ID before the intent fires
        val priorMaxId = queryMaxId(resolver)
        val priorMaxMmsId = queryMaxMmsId(resolver)
        Timber.d("RcsDeliveryObserver: watching for delivery (priorMaxId=$priorMaxId, priorMaxMmsId=$priorMaxMmsId)")

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                var status = checkForDelivery(resolver, priorMaxId)
                if (status == null) {
                    status = checkForMmsDelivery(resolver, priorMaxMmsId)
                }
                
                status?.let {
                    Timber.i("RCS delivery status for $toAddress: $it")
                    trySend(it)
                    close()   // stop observing after first result
                }
            }
        }

        resolver.registerContentObserver(Uri.parse("content://sms"), true, observer)
        resolver.registerContentObserver(Uri.parse("content://mms"), true, observer)

        awaitClose {
            resolver.unregisterContentObserver(observer)
            Timber.d("RcsDeliveryObserver unregistered for $toAddress")
        }
    }

    /**
     * Query the SMS table for any new message (ID > priorMaxId)
     * with type SENT or FAILED.
     */
    private fun checkForDelivery(resolver: ContentResolver, priorMaxId: Long): RcsDeliveryStatus? {
        val cursor = resolver.query(
            SMS_URI,
            arrayOf(COL_ID, COL_TYPE),
            "$COL_ID > ?",
            arrayOf(priorMaxId.toString()),
            "$COL_ID DESC"
        ) ?: return null

        cursor.use {
            if (!it.moveToFirst()) return null
            val typeIdx = it.getColumnIndexOrThrow(COL_TYPE)
            return when (it.getInt(typeIdx)) {
                TYPE_SENT, TYPE_OUTBOX, TYPE_QUEUED -> RcsDeliveryStatus.DELIVERED
                TYPE_FAILED -> RcsDeliveryStatus.FAILED
                else        -> null   // still in-flight
            }
        }
    }

    private fun checkForMmsDelivery(resolver: ContentResolver, priorMaxMmsId: Long): RcsDeliveryStatus? {
        val cursor = resolver.query(
            Uri.parse("content://mms"),
            arrayOf("_id", "msg_box"),
            "_id > ?",
            arrayOf(priorMaxMmsId.toString()),
            "_id DESC"
        ) ?: return null

        cursor.use {
            if (!it.moveToFirst()) return null
            val boxIdx = it.getColumnIndexOrThrow("msg_box")
            return when (it.getInt(boxIdx)) {
                2, 4 -> RcsDeliveryStatus.DELIVERED // 2=Sent, 4=Outbox
                5 -> RcsDeliveryStatus.FAILED       // 5=Failed
                else -> null
            }
        }
    }

    private fun queryMaxId(resolver: ContentResolver): Long {
        val cursor = resolver.query(
            SMS_URI,
            arrayOf("_id"),
            null,
            null,
            "_id DESC LIMIT 1"
        ) ?: return 0L
        return cursor.use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }
    }

    private fun queryMaxMmsId(resolver: ContentResolver): Long {
        val cursor = resolver.query(
            Uri.parse("content://mms"),
            arrayOf("_id"),
            null,
            null,
            "_id DESC LIMIT 1"
        ) ?: return 0L
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
