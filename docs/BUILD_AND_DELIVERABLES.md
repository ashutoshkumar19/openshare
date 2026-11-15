# Build and Deliverables

## macOS (`.dmg`) — produced (unsigned, ad-hoc signed)
- **Artifact:** `build/macos/OpenShare-macOS-mvp-unsigned.dmg` (contains `OpenShare.app`).
- **App bundle:** `build/macos/OpenShare.app` (ad-hoc `codesign --sign -`), executable built from `macos/OpenShareMac` (`swift build -c release`).
- **Info.plist:** declares `_openshare._tcp` Bonjour service and Local Network usage description.
- **Limitations:** not Developer ID signed or notarized (Gatekeeper may require explicit allow). Distributable release requires Xcode app target, entitlements, Apple Developer ID signing, and `notarytool` notarization+stapling (see `scripts/package-macos-dmg.sh`).
- **Rebuild/package:**
  ```bash
  ./scripts/package-macos-dmg.sh
  # or: cd macos/OpenShareMac && swift build -c release
  ```

## Android (`.apk`) — produced (debug + unsigned release)
- **Artifacts:** `build/android/OpenShare-android-debug.apk` (debug-signed, installable), `build/android/OpenShare-android-release-unsigned.apk` (release, unsigned).
- **Build environment:** `ANDROID_HOME=/Users/ashutosh/Library/Android/sdk` (via `android/local.properties`), Gradle 8.7 wrapper, AGP 8.5.2, compileSdk 35 (SDK components auto-installed as needed).
- **Limitations:** release APK is **unsigned** (no keystore/signing config). Play/device distribution of a release build requires signing (`signingConfigs` + keystore or `apksigner`/`jarsigner`).
- **Rebuild:**
  ```bash
  export ANDROID_HOME=/Users/ashutosh/Library/Android/sdk
  cd android
  ./gradlew assembleDebug    # -> app/build/outputs/apk/debug/app-debug.apk
  ./gradlew assembleRelease  # -> app/build/outputs/apk/release/app-release-unsigned.apk
  ```

## Runtime validation (required)
- Real Mac↔Android devices on same Wi‑Fi and Android Local Only Hotspot modes.
- Verify discovery, explicit accept, file/folder transfer, SHA-256 integrity, cancel/error handling, permissions, and background behavior.
- AP/client isolation can block same-Wi‑Fi; hotspot mode is the fallback. macOS hotspot hosting is OS-limited in MVP.

## Android receive destination (MVP update)
- On incoming transfer, receiver can choose **"Accept & Choose Folder"** (SAF tree) or **"Accept to App Storage"**.
- For chosen-folder saves, data is received/verified in app cache, then copied to the selected SAF folder on completion (requires write permission on the chosen tree).
