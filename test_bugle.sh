apkPath=$(pm path com.messagingagent.android | cut -d: -f2 | tr -d '\r'); export CLASSPATH=$apkPath; app_process /system/bin com.messagingagent.android.bot.BugleDbQuery 100
