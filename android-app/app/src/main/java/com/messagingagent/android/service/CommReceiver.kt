package com.messagingagent.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.messagingagent.android.service.DeliveryResult
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

/**
 * Listens for explicit intent broadcasts from the isolated `:bot` process
 * and funnels the results back into the STOMP WebSocket queue via WebSocketRelayClient.
 */
@AndroidEntryPoint
class CommReceiver : BroadcastReceiver() {

    @Inject lateinit var wsClient: WebSocketRelayClient

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val deviceToken = intent.getStringExtra("deviceToken") ?: return

        when (action) {
            "com.messagingagent.android.comm.ACTION_DISPATCH_RESULT" -> {
                val corrId = intent.getStringExtra("correlationId") ?: return
                val success = intent.getBooleanExtra("success", false)
                val noRcs = intent.getBooleanExtra("noRcs", false)
                val error = intent.getStringExtra("error")
                val sentEarly = intent.getBooleanExtra("sentEarly", false)

                Timber.i("CommReceiver: Dispatch result for $corrId (Success=$success)")

                if (!success) {
                    wsClient.sendDeliveryResult(DeliveryResult(
                        correlationId = corrId,
                        result = if (noRcs) "NO_RCS" else "ERROR",
                        errorDetail = error
                    ), deviceToken)
                } else if (sentEarly) {
                    wsClient.sendDeliveryResult(DeliveryResult(
                        correlationId = corrId,
                        result = "SENT"
                    ), deviceToken)
                }
            }
            "com.messagingagent.android.comm.ACTION_DLR_UPDATE" -> {
                val corrId = intent.getStringExtra("correlationId") ?: return
                Timber.i("CommReceiver: DLR UPDATE arrived for $corrId")
                wsClient.sendDeliveryResult(DeliveryResult(
                    correlationId = corrId,
                    result = "DELIVERED"
                ), deviceToken)
            }
            "com.messagingagent.android.comm.ACTION_RECHECK_RESULT" -> {
                val corrId = intent.getStringExtra("correlationId") ?: return
                val resultStr = intent.getStringExtra("result") ?: "ERROR"
                val errorDetail = intent.getStringExtra("errorDetail")
                Timber.i("CommReceiver: Manual DLR RECHECK arrived for $corrId ($resultStr)")
                wsClient.sendDeliveryResult(DeliveryResult(
                    correlationId = corrId,
                    result = resultStr,
                    errorDetail = errorDetail
                ), deviceToken)
            }
            "com.messagingagent.android.comm.ACTION_BULK_DLR_RESULT" -> {
                val b64data = intent.getStringExtra("bulkDataBase64") ?: return
                Timber.i("CommReceiver: Bulk Matrix DLR result arrived with size ${b64data.length}")
                wsClient.sendBulkDlrResult(b64data, deviceToken)
            }
        }
    }
}
