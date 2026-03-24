---
description: Build the Android debug APK and upload it for OTA updates
---

# Build APK & Upload for OTA

// turbo-all

1. Build the APK inside the Docker builder:
```
docker run --rm -v c:\Dev\messaging-agent\android-app:/project -w /project android-apk-builder ./gradlew clean assembleDebug
```

2. Copy the APK to the standard location:
```
Copy-Item "c:\Dev\messaging-agent\android-app\app\build\outputs\apk\debug\app-debug.apk" "c:\Dev\messaging-agent\android-app\MessagingAgent-debug.apk" -Force
```

3. Upload the APK to the backend for OTA updates:
```
$token = (Invoke-RestMethod -Uri http://localhost:8080/api/auth/login -Method POST -Body '{"username":"admin","password":"admin"}' -ContentType 'application/json').token; $form = [System.Net.Http.MultipartFormDataContent]::new(); $fileBytes = [System.IO.File]::ReadAllBytes("c:\Dev\messaging-agent\android-app\MessagingAgent-debug.apk"); $fileContent = [System.Net.Http.ByteArrayContent]::new($fileBytes); $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("application/octet-stream"); $form.Add($fileContent, "file", "MessagingAgent-debug.apk"); $client = [System.Net.Http.HttpClient]::new(); $client.DefaultRequestHeaders.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new("Bearer", $token); $result = $client.PostAsync("http://localhost:8080/api/apk/upload", $form).Result; Write-Host "Upload status: $($result.StatusCode)"
```

4. Verify the uploaded APK info:
```
$token = (Invoke-RestMethod -Uri http://localhost:8080/api/auth/login -Method POST -Body '{"username":"admin","password":"admin"}' -ContentType 'application/json').token; Invoke-RestMethod -Uri http://localhost:8080/api/apk/info -Headers @{Authorization="Bearer $token"}
```

> **IMPORTANT**: Every time a new APK version is built, it MUST be uploaded to the backend for OTA updates using step 3. This ensures devices can auto-update to the latest version.
