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
  bash -c "echo 'sdk.dir=/android-sdk' > local.properties && java -Xmx2g -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain assembleDebug --no-daemon 2>&1 | tail -8"
```

2. Copy the built APK to the repo root folder (always overwrite):

```powershell
Copy-Item "c:\Dev\messaging-agent\android-app\app\build\outputs\apk\debug\app-debug.apk" `
          "c:\Dev\messaging-agent\MessagingAgent-debug.apk" -Force
Get-Item "c:\Dev\messaging-agent\MessagingAgent-debug.apk" | Select-Object Name, Length, LastWriteTime
```

3. Push the APK to the connected Android device (if phone is connected via USB):

```powershell
C:\adb\adb.exe push "c:\Dev\messaging-agent\MessagingAgent-debug.apk" /sdcard/Download/MessagingAgent.apk
```

4. Stage and commit the updated APK:

```powershell
Set-Location "c:\Dev\messaging-agent"
git add MessagingAgent-debug.apk android-app/
git commit -m "build: update MessagingAgent-debug.apk"
```
