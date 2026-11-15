# Android (OpenShareAndroid)

Kotlin + Jetpack Compose skeleton for the Wi-Fi MVP.

## Build
- Requires Android Studio / Android SDK (compileSdk 35), JDK 17, Gradle.
- Build debug APK:
```bash
cd android
./gradlew assembleDebug
```
- Build release APK (configure signing in `app/build.gradle.kts` or `keystore.properties`):
```bash
./gradlew assembleRelease
# output: app/build/outputs/apk/release/app-release.apk
```

## Implemented skeleton
- Compose UI for mode/role selection, status, peer list
- NSD register/discover scaffold
- Length-delimited frame + chunk codec helpers
- Foreground service stub for transfers
- Manifest, permissions, protocol/path-safety models

## TODO
- TLS socket layer (ephemeral cert / trust strategy) and handshake/accept flow
- Manifest build from SAF/Uris, chunked streaming send/receive, staging + SHA-256 verify/finalize
- Local Only Hotspot host + QR generation, QR scan (CameraX/ZXing) and hotspot join/connect
- Wire progress, cancel/error handling, and file picker/destination UX
- Add Gradle wrapper JAR (`gradle/wrapper/gradle-wrapper.jar`) or generate via `gradle wrapper`
