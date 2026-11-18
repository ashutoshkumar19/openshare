#!/usr/bin/env bash
set -euo pipefail
# Builds and packages both macOS and Android deliverables into root build/ directory.
# Prerequisites:
#   - macOS: Xcode command line tools + Swift 5.9+ (macOS 13+)
#   - Android: JDK 17 + Android SDK (set ANDROID_HOME or configure android/local.properties)

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "==> Building macOS (release + .app + .dmg)..."
"$ROOT/scripts/package-macos-dmg.sh"

echo "==> Building Android (debug + release APKs)..."
"$ROOT/scripts/build-android-apk.sh"

echo "==> Done. Deliverables:"
echo "  - build/macos/OpenShare-macOS-mvp-unsigned.dmg"
echo "  - build/android/OpenShare-android-debug.apk"
echo "  - build/android/OpenShare-android-release-unsigned.apk"
