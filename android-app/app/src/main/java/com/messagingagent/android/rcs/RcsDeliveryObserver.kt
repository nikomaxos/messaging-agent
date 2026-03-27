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
    suspend fun awaitDelivery(toAddress: String, initialMaxId: Long): RcsDeliveryStatus {
        val cleanDest = normalizeNumber(toAddress)
        Timber.d("RcsDeliveryObserver: starting bugle_db poll for $cleanDest (messages > $initialMaxId)")
        
        val result = withTimeoutOrNull(RECEIPT_TIMEOUT_MS) {
            var status: RcsDeliveryStatus? = null
            while (status == null) {
                try {
                    val tmpDb = "/data/local/tmp/bug_poll.db"
                    com.topjohnwu.superuser.Shell.cmd("cp /data/data/com.google.android.apps.messaging/databases/bugle_db $tmpDb", "chmod 666 $tmpDb").exec()
                    
                    android.database.sqlite.SQLiteDatabase.openDatabase(tmpDb, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
                        db.rawQuery("SELECT message_status FROM messages WHERE _id > $initialMaxId AND message_status != 1 ORDER BY _id ASC LIMIT 1", null).use { cursor ->
                            if (cursor.moveToFirst()) {
                                val msgStatus = cursor.getInt(0)
                                when (msgStatus) {
                                    11, 13, 14 -> status = RcsDeliveryStatus.DELIVERED // 11, 13=Delivered, 14=Read
                                    2 -> status = RcsDeliveryStatus.SENT // 2=Sent (Submitted)
                                    5, 8, 9 -> status = RcsDeliveryStatus.FAILED     // Failed/Error
                                    // 3=Sending, 4=Outbox -> keep waiting
                                }
                            }
                        }
                    }
                    com.topjohnwu.superuser.Shell.cmd("rm -f $tmpDb").exec()
                } catch (e: Exception) {
                    Timber.e("Error polling native SQLiteDatabase: ${e.message}")
                }
                
                if (status == null) {
                    kotlinx.coroutines.delay(300)
                }
            }
            status
        }
        
        val finalStatus = result ?: RcsDeliveryStatus.TIMEOUT
        Timber.i("RCS delivery status for $cleanDest resolved to: $finalStatus")
        return finalStatus
    }

    private fun normalizeNumber(number: String): String =
        number.replace(Regex("[^0-9+]"), "").let {
            if (it.length > 7) it.takeLast(7) else it
        }
}

enum class RcsDeliveryStatus { SENT, DELIVERED, FAILED, TIMEOUT }
