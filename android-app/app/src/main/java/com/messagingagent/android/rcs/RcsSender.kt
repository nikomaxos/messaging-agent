package com.messagingagent.android.rcs

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.SmsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

data class RcsSendResult(
    val success: Boolean,
    val noRcs: Boolean = false,
    val error: String? = null
)

@Singleton
class RcsSender @Inject constructor(
    @ApplicationContext private val context: Context
) {

    suspend fun sendRcs(to: String, text: String): RcsSendResult = suspendCancellableCoroutine { cont ->
        try {
            val smsManager = context.getSystemService(SmsManager::class.java)
            if (smsManager == null) {
                cont.resume(RcsSendResult(success = false, noRcs = true, error = "SmsManager unavailable"))
                return@suspendCancellableCoroutine
            }

            val action = "SMS_SENT_${System.currentTimeMillis()}"
            val intent = Intent(action)
            // FLAG_IMMUTABLE is required for Android 12+
            val pendingIntent = PendingIntent.getBroadcast(
                context, 0, intent, PendingIntent.FLAG_IMMUTABLE
            )

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    try {
                        c.unregisterReceiver(this)
                    } catch (e: Exception) { /* ignored */ }
                    
                    if (resultCode == Activity.RESULT_OK) {
                        Timber.i("SMS physically sent to $to")
                        cont.resume(RcsSendResult(success = true))
                    } else {
                        Timber.w("SMS failed to send to $to (code: $resultCode)")
                        cont.resume(RcsSendResult(success = false, error = "SmsManager error: $resultCode"))
                    }
                }
            }

            // Register receiver dynamically for this specific send
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, IntentFilter(action))
            }

            // Execute the physical send
            smsManager.sendTextMessage(to, null, text, pendingIntent, null)
            Timber.i("SMS dispatched to radio hardware for $to")

            cont.invokeOnCancellation {
                try { context.unregisterReceiver(receiver) } catch (e: Exception) { }
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception during physical SMS send for $to")
            if (cont.isActive) {
                cont.resume(RcsSendResult(success = false, error = e.message))
            }
        }
    }
}
