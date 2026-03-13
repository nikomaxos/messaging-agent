package com.messagingagent.android.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.messagingagent.android.data.PreferencesRepository
import com.messagingagent.android.service.MessagingAgentService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var prefs: PreferencesRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Timber.d("Boot completed received, checking registration...")
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val state = prefs.registrationFlow().first()
                    if (state.isRegistered) {
                        Timber.d("Device is registered. Autostarting MessagingAgentService from boot.")
                        val serviceIntent = Intent(context, MessagingAgentService::class.java)
                        // Using startService since MessagingAgentService elevates itself to foreground
                        context.startService(serviceIntent)
                    } else {
                        Timber.d("Device is not registered. Skipping service autostart.")
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error checking registration state on boot")
                }
            }
        }
    }
}
