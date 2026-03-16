---
description: Build the Android debug APK and copy it to the repo root
---

// turbo-all

1. Build the APK using the android-build-env Docker image:

```powershell
docker run --rm `
  -v "c:\Dev\messaging-agent\android-app:/project" `
  -v "android-gradle-cache:/root/.gradle" `
  -e "ANDROID_HOME=/android-sdk" `
  -w /project `
  android-build-env `
  bash -c "echo 'sdk.dir=/android-sdk' > local.properties && java -Xmx2g -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain clean assembleDebug --no-daemon"
```

2. Copy the built APK to the repo root folder with the Version Name (always overwrite):

```powershell
Set-Location "c:\Dev\messaging-agent"
$apkNamePattern = 'versionName\s*=\s*"([^"]+)"'
$versionStr = (Select-String -Path "android-app\app\build.gradle.kts" -Pattern $apkNamePattern).Matches.Groups[1].Value
$outName = "MessagingAgent-debug-v$versionStr.apk"

Copy-Item "android-app\app\build\outputs\apk\debug\app-debug.apk" $outName -Force
Get-Item $outName | Select-Object Name, Length, LastWriteTime
```

3. Push the APK to the connected Android device (if phone is connected via USB):

```powershell
Set-Location "c:\Dev\messaging-agent"
$versionStr = (Select-String -Path "android-app\app\build.gradle.kts" -Pattern 'versionName\s*=\s*"([^"]+)"').Matches.Groups[1].Value
C:\adb\adb.exe push "MessagingAgent-debug-v$versionStr.apk" /sdcard/Download/MessagingAgent.apk
```

4. Stage and commit the updated APK:

```powershell
Set-Location "c:\Dev\messaging-agent"
$versionStr = (Select-String -Path "android-app\app\build.gradle.kts" -Pattern 'versionName\s*=\s*"([^"]+)"').Matches.Groups[1].Value
git add "MessagingAgent-debug-v$versionStr.apk" android-app/
git commit -m "build: update MessagingAgent-debug-v$versionStr.apk"
```
