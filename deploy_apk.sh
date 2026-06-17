#!/bin/bash
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

echo "Connecting to device..."
adb connect 192.168.60.166:37443

echo "Building APK..."
cd /home/nick/Development/messaging-agent/android-app
./gradlew clean assembleDebug --no-daemon --console=plain > build.log 2>&1

if [ $? -eq 0 ]; then
    echo "Build successful! Installing..."
    adb install -r app/build/outputs/apk/debug/app-debug.apk
else
    echo "Build failed! Check build.log"
    cat build.log | tail -n 50
fi
