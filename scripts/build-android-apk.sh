#!/usr/bin/env bash
set -euo pipefail
# Requires Android SDK, JDK 17, and Gradle (wrapper or system) + signing config for release.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/android"
if [ -x ./gradlew ]; then ./gradlew assembleRelease; else gradle assembleRelease; fi
echo "Check app/build/outputs/apk/release/ for APK artifacts."
