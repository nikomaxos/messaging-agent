@echo off
echo Uploading Messaging Agent APK...
curl -X POST http://localhost:9090/api/apk/upload -H "Authorization: Bearer undefined" -F "file=@c:\Dev\messaging-agent\android-app\app\build\outputs\apk\debug\app-debug.apk"
echo.
echo Uploading Guardian APK...
curl -X POST http://localhost:9090/api/apk/upload-guardian -H "Authorization: Bearer undefined" -F "file=@c:\Dev\messaging-agent\android-app\guardian\build\outputs\apk\debug\guardian-debug.apk"
echo.
echo Upload Complete!
pause
