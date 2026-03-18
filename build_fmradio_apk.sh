#!/bin/bash
# Build FM Radio APK
# Requirements: Android SDK with API 34, Kotlin 1.9.20
#
# Setup:
#   1. Install Android Studio or Android SDK command-line tools
#   2. Set ANDROID_HOME environment variable
#   3. Install SDK platform 34: sdkmanager "platforms;android-34" "build-tools;34.0.0"
#   4. Run this script

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

if [ -z "$ANDROID_HOME" ]; then
    if [ -d "$HOME/Android/Sdk" ]; then
        export ANDROID_HOME="$HOME/Android/Sdk"
    elif [ -d "/usr/local/lib/android/sdk" ]; then
        export ANDROID_HOME="/usr/local/lib/android/sdk"
    else
        echo "ERROR: ANDROID_HOME not set. Install Android SDK first."
        exit 1
    fi
fi

echo "Using Android SDK: $ANDROID_HOME"
echo "sdk.dir=$ANDROID_HOME" > local.properties

echo "Building FM Radio APK..."
chmod +x gradlew
./gradlew :fmradio:assembleDebug

APK="fmradio/build/outputs/apk/debug/fmradio-debug.apk"
if [ -f "$APK" ]; then
    echo ""
    echo "=========================================="
    echo "  BUILD SUCCESS!"
    echo "  APK: $APK"
    echo "=========================================="
    echo ""
    echo "Install on device:"
    echo "  adb install $APK"
else
    echo "ERROR: APK not found at $APK"
    exit 1
fi
