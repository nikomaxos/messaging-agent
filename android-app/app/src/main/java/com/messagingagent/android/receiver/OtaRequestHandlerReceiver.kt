package com.messagingagent.android.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.messagingagent.android.data.PreferencesRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class OtaRequestHandlerReceiver : BroadcastReceiver() {

    @Inject
    lateinit var prefs: PreferencesRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.messagingagent.android.REQUEST_OTA") {
            CoroutineScope(Dispatchers.IO).launch {
                val backendUrl = prefs.getBackendUrl()
                if (backendUrl != null) {
                    val triggerIntent = Intent("com.messagingagent.guardian.ACTION_TRIGGER_OTA").apply {
                        putExtra("url", "${backendUrl.trimEnd('/')}/api/public/apk/download")
                        addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                        setPackage("com.messagingagent.guardian")
                    }
                    context.sendBroadcast(triggerIntent)
                }
            }
        }
    }
}
