package com.messagingagent.guardian

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.BroadcastReceiver
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

class GuardianService : Service() {

    private val client = OkHttpClient()

    private fun sendProgress(status: String, progress: Int = -1) {
        val intent = Intent("com.messagingagent.guardian.UPDATE_STATUS")
        intent.putExtra("status", status)
        intent.putExtra("progress", progress)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

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

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun downloadAndInstall(url: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                sendProgress("Starting download...", 0)
                Log.i("Guardian", "Starting download from: ${url}")
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val body = response.body
                    if (body != null) {
                        val contentLength = body.contentLength()
                        val apkFile = File(cacheDir, "update.apk")
                        
                        body.byteStream().use { input ->
                            apkFile.outputStream().use { output ->
                                val buffer = ByteArray(8 * 1024)
                                var bytesCopied: Long = 0
                                var bytesRead: Int
                                var lastProgress = 0
                                while (input.read(buffer).also { bytesRead = it } >= 0) {
                                    output.write(buffer, 0, bytesRead)
                                    bytesCopied += bytesRead
                                    if (contentLength > 0) {
                                        val progress = ((bytesCopied * 100) / contentLength).toInt()
                                        if (progress != lastProgress) {
                                            if (progress % 5 == 0 || progress == 100) {
                                                sendProgress("Downloading... $progress%", progress)
                                            }
                                            lastProgress = progress
                                        }
                                    }
                                }
                            }
                        }
                        sendProgress("Download complete. Installing...", 100)
                        Log.i("Guardian", "Download complete. Size: ${apkFile.length()}")
                        installApk(apkFile)
                    }
                } else {
                    sendProgress("Download failed: HTTP ${response.code}")
                    Log.e("Guardian", "Download failed: ${response.code}")
                }
            } catch (e: Exception) {
                sendProgress("Error downloading APK: ${e.message}")
                Log.e("Guardian", "Error downloading APK: ${e.message}")
            }
        }
    }

    private fun installApk(apkFile: File) {
        try {
            sendProgress("Package Downloaded. Installing silently as root...")
            
            val process = Runtime.getRuntime().exec("su")
            val os = java.io.DataOutputStream(process.outputStream)
            val reader = process.inputStream.bufferedReader()
            val errorReader = process.errorStream.bufferedReader()
            
            os.writeBytes("pm install -r ${apkFile.absolutePath}\n")
            os.writeBytes("echo __DONE__\n")
            os.flush()
            
            var output = ""
            var errorOutput = ""
            var isSuccess = false
            
            while (true) {
                val line = reader.readLine() ?: break
                if (line == "__DONE__") break
                if (line.contains("Success", ignoreCase = true)) {
                    isSuccess = true
                }
                output += line + "\n"
            }
            
            if (errorReader.ready()) {
                errorOutput = errorReader.readText()
            }
            
            if (isSuccess || output.contains("Success", ignoreCase = true)) {
                sendProgress("Installation Complete. Starting app...")
                Log.i("Guardian", "Silent install success: $output")
                
                // Launch the app
                os.writeBytes("monkey -p com.messagingagent.android -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1\n")
                os.flush()
            } else {
                sendProgress("Silent install failed. Reason: $output | $errorOutput")
                Log.e("Guardian", "Silent install failed: $output | Error: $errorOutput")
            }
            
            os.writeBytes("exit\n")
            os.flush()
            process.waitFor()

        } catch (e: Exception) {
            val errorMsg = e.message ?: e.toString()
            sendProgress("Installer Error: $errorMsg")
            Log.e("Guardian", "Installer Exception: $errorMsg", e)
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
