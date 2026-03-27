---
description: Build the Android debug APK and upload it for OTA updates
---

# Build APK & Upload for OTA

// turbo-all

1. Build the APK inside the Docker builder:
```
docker run --rm -v c:\Dev\messaging-agent\android-app:/project -w /project android-apk-builder ./gradlew clean assembleDebug
```

2. Copy the APKs to the standard location:
```
Copy-Item "c:\Dev\messaging-agent\android-app\app\build\outputs\apk\debug\app-debug.apk" "c:\Dev\messaging-agent\android-app\MessagingAgent-debug.apk" -Force
Copy-Item "c:\Dev\messaging-agent\android-app\guardian\build\outputs\apk\debug\guardian-debug.apk" "c:\Dev\messaging-agent\android-app\MessagingGuardian-debug.apk" -Force
```

3. Upload the APKs to the backend for OTA updates:
```
$token = (Invoke-RestMethod -Uri http://localhost:8080/api/auth/login -Method POST -Body '{"username":"admin","password":"admin"}' -ContentType 'application/json').token
curl.exe -X POST http://localhost:8080/api/apk/upload -H "Authorization: Bearer $token" -F "file=@c:\Dev\messaging-agent\android-app\MessagingAgent-debug.apk"
curl.exe -X POST http://localhost:8080/api/apk/upload -H "Authorization: Bearer $token" -F "file=@c:\Dev\messaging-agent\android-app\MessagingGuardian-debug.apk"
```

4. Verify the uploaded APK info:
```
$token = (Invoke-RestMethod -Uri http://localhost:8080/api/auth/login -Method POST -Body '{"username":"admin","password":"admin"}' -ContentType 'application/json').token; Invoke-RestMethod -Uri http://localhost:8080/api/apk/info -Headers @{Authorization="Bearer $token"}
```

> **IMPORTANT**: Every time a new APK version is built, it MUST be uploaded to the backend for OTA updates using step 3. This ensures devices can auto-update to the latest version.
