---
description: Build the Android debug APK and copy it to the android-app folder
---
// turbo-all

> [!CAUTION]
> **MANDATORY**: After building, you MUST copy the APK to `android-app/` with the version in the filename.
> Example: `messaging-agent-1.0.74.apk`
> You must ALSO copy it to `android-app/apk-updates/update.apk` for remote deployment.
> NEVER skip the versioned copy. The user has explicitly requested this.

## Steps

1. Bump BOTH `versionCode` and `versionName` in `android-app/app/build.gradle.kts` (lines 18-19). They must match — e.g. versionCode=74, versionName="1.0.74".

2. Build the APK using the Docker builder image:
```powershell
docker run --rm -v c:\Dev\messaging-agent\android-app:/project -w /project android-apk-builder ./gradlew assembleDebug
```

3. If the builder image doesn't exist yet, build it first:
```powershell
docker build -f c:\Dev\messaging-agent\android-app\Dockerfile.build -t android-apk-builder c:\Dev\messaging-agent\android-app
```

4. **MANDATORY — Copy built APK with versioned filename AND to the remote-deploy directory**:
```powershell
Copy-Item "c:\Dev\messaging-agent\android-app\app\build\outputs\apk\debug\app-debug.apk" "c:\Dev\messaging-agent\android-app\messaging-agent-<VERSION>.apk" -Force
Copy-Item "c:\Dev\messaging-agent\android-app\app\build\outputs\apk\debug\app-debug.apk" "c:\Dev\messaging-agent\android-app\apk-updates\update.apk" -Force
Set-Content -Path "c:\Dev\messaging-agent\android-app\apk-updates\apk-meta.txt" -Value "messaging-agent-<VERSION>.apk"
```
Replace `<VERSION>` with the actual versionName (e.g. `messaging-agent-1.0.74.apk`).

5. Restart the backend container so it picks up the new APK for remote deployment:
```powershell
docker compose -f c:\Dev\messaging-agent\docker-compose.yml restart backend
```

6. Deploy via admin panel remote deployment, or directly via ADB:
```powershell
adb -s <device-ip>:5555 install -r "c:\Dev\messaging-agent\android-app\messaging-agent-<VERSION>.apk"
```

## Notes
- `android-app/apk-updates/` is volume-mounted into the backend at `/tmp/updates/`
- `apk-meta.txt` stores the filename — shown in the admin panel below the Upload APK button
- `local.properties` has `sdk.dir=/android-sdk` — this is the Docker path, do NOT change
- No Android SDK on host — always build via Docker
- Build time: ~7-8 min full, ~2-3 min incremental
