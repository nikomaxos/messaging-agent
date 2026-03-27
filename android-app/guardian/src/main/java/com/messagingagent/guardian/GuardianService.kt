package com.messagingagent.guardian

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

class GuardianService : Service() {

    private val client = OkHttpClient()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "guardian_channel")
            .setContentTitle("Messaging Guardian")
            .setContentText("OTA MDM Service Active")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .build()
        startForeground(1, notification)
        startWatchdog()
    }

    private fun startWatchdog() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Open a single, persistent interactive root shell to avoid recursive Magisk UI toasts
                val process = Runtime.getRuntime().exec("su")
                val os = java.io.DataOutputStream(process.outputStream)
                val reader = process.inputStream.bufferedReader()

                while (true) {
                    try {
                        // Request pid and immediately echo a deterministic end token
                        os.writeBytes("pidof com.messagingagent.android; echo __DONE__\n")
                        os.flush()

                        var output = ""
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line == "__DONE__") break
                            output += line
                        }

                        if (output.trim().isEmpty()) {
                            Log.w("Guardian", "Watchdog: Worker App is DEAD. Resurrecting via persistent shell...")
                            os.writeBytes("monkey -p com.messagingagent.android -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1\n")
                            os.flush()
                        }
                    } catch (e: Exception) {
                        Log.e("Guardian", "Watchdog stream error: ${e.message}")
                    }
                    kotlinx.coroutines.delay(60_000) // Check every 60 seconds
                }
            } catch (e: Exception) {
                Log.e("Guardian", "Persistent Shell failed: ${e.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "DOWNLOAD_AND_INSTALL") {
            val url = intent.getStringExtra("url")
            if (url != null) {
                downloadAndInstall(url)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun downloadAndInstall(url: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.i("Guardian", "Starting download from: ${"$"}url")
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val apkFile = File(cacheDir, "update.apk")
                    response.body?.byteStream()?.use { input ->
                        apkFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.i("Guardian", "Download complete. Size: ${"$"}{apkFile.length()}")
                    installApk(apkFile)
                } else {
                    Log.e("Guardian", "Download failed: ${"$"}{response.code}")
                }
            } catch (e: Exception) {
                Log.e("Guardian", "Error downloading APK: ${"$"}{e.message}")
            }
        }
    }

    private fun installApk(apkFile: File) {
        try {
            val packageInstaller = packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName("com.messagingagent.android")
            
            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            val out = session.openWrite("package", 0, -1)
            apkFile.inputStream().use { input ->
                input.copyTo(out)
            }
            session.fsync(out)
            out.close()

            // Commit the session
            val intent = Intent("com.messagingagent.guardian.ACTION_INSTALL_COMPLETE")
            val pendingIntent = PendingIntent.getBroadcast(
                this,
                sessionId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            
            Log.i("Guardian", "Committing PackageInstaller session...")
            session.commit(pendingIntent.intentSender)
            
        } catch (e: Exception) {
            Log.e("Guardian", "PackageInstaller Exception: ${"$"}{e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "guardian_channel",
                "Guardian Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
