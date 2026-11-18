#!/usr/bin/env bash
set -euo pipefail
# Requires Android SDK, JDK 17, and Gradle (wrapper or system) + signing config for release.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/android"
if [ -x ./gradlew ]; then ./gradlew assembleRelease assembleDebug; else gradle assembleRelease assembleDebug; fi

# Copy artifacts to root build/android for deliverables
mkdir -p "$ROOT/build/android"
cp "app/build/outputs/apk/debug/app-debug.apk" "$ROOT/build/android/OpenShare-android-debug.apk"
cp "app/build/outputs/apk/release/app-release-unsigned.apk" "$ROOT/build/android/OpenShare-android-release-unsigned.apk"

echo "Artifacts copied to build/android/:"
echo "  - build/android/OpenShare-android-debug.apk"
echo "  - build/android/OpenShare-android-release-unsigned.apk"
