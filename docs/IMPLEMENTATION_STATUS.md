# Implementation Status

## Completed in-repo
- **Protocol/spec:** `docs/protocol.md` (v1) — service `_openshare._tcp`, discovery TXT, hotspot QR JSON bootstrap, length-delimited framing, JSON control messages, binary chunk format, manifest/offer, send/receive/verify/finalize flow, path safety, error codes. **MVP transport note:** implementation uses plain TCP with session-token binding + explicit receiver accept; TLS hardening (ephemeral cert/pinning) is a follow-up (initial TLS attempt failed cross-platform handshake without provisioned server identity).
- **Architecture:** `docs/architecture.md` with platform constraints.
- **macOS app (`macos/OpenShareMac`, SwiftPM, builds):**
  - `OpenShareCore`: public protocol models, frame/chunk codecs, path safety, message types.
  - UI: mode/role selection, file/folder picker, peer list/connect, incoming offer accept/decline with destination picker, hotspot QR payload connect, progress/status.
  - Networking/discovery: NWListener/NWConnection TCP transport, Bonjour advertise/browse with numeric host resolution.
  - Session/transfer: HELLO/HELLO_ACK token+version validation, TRANSFER_OFFER/ACCEPT/DECLINE, chunked streaming send, staged receive, per-file SHA-256 verify, atomic finalize, cancel/error handling, preflight disk-space check, directory creation, progress callbacks.
  - **Build verified:** `cd macos/OpenShareMac && swift build -c release` succeeds.
- **Android app (`android/`, Gradle project):**
  - Compose UI/ViewModel: mode/role, file/folder pickers, NSD peer connect, incoming accept/decline, hotspot host QR payload display, QR payload connect, progress/status.
  - Discovery: NSD register/discover/resolve.
  - Networking: TCP socket helpers, framed socket, chunk encode/decode.
  - Session/transfer: handshake/offer/accept, chunked send from ContentResolver/URIs (incl. SAF tree manifest walk), staged receive, SHA-256 verify, finalize, cancel/error handling.
  - Hotspot: Local Only Hotspot host helper and QR payload encode/decode.
  - Foreground service stub and permissions/manifest configured.

## Delivered artifacts
- **macOS:** `build/macos/OpenShare-MacOS-mvp-unsigned.dmg` (ad-hoc signed; not notarized).
- **Android:** `build/android/OpenShare-android-debug.apk`, `build/android/OpenShare-android-release-unsigned.apk` (unsigned release).
- See `docs/BUILD_AND_DELIVERABLES.md`.

## Known limitations / follow-ups
- **Transport security:** no TLS in current MVP build (token-bound + accept only). Add proper TLS with server identity/cert pinning.
- **SAF destination on Android:** receive currently saves to app external storage for reliable streaming finalize.
- **macOS hotspot host:** OS-limited in MVP.
- One-to-one only, no resume, no Bluetooth, no Windows/iOS.
- Requires real-device validation for same-Wi‑Fi and Android Local Only Hotspot modes.
