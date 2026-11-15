# Architecture & Platform Notes (MVP)

## Components (per app)
- **UI:** mode select (Same Wi-Fi / Hotspot), Send/Receive, device/session list, manifest review + explicit accept, progress, errors.
- **Session/transfer engine:** state machine `idle → discovering|pairing → awaiting_accept → transferring → verifying → complete|failed|cancelled`.
- **Transport:** TLS TCP abstraction; identical framed protocol over same-Wi-Fi and hotspot links.
- **Discovery/pairing:** Bonjour/NSD for same-Wi-Fi; QR encode/decode for hotspot bootstrap.
- **Storage:** streaming read/write, staging + hash verify + atomic finalize, path-safety enforcement.

## macOS (Swift/SwiftUI)
- **Networking:** Network.framework (`NWListener`, `NWConnection`) with TLS; Bonjour via `NWBrowser`/NetService.
- **Files:** user-selected source/destination (open/save panels, security-scoped access), recursive enumeration for folders, streaming IO.
- **Hotspot host constraint:** no app-controlled local-only hotspot API; Internet Sharing is system-managed. MVP: prefer macOS as joiner or same-Wi-Fi; hosting only when user configures sharing and endpoint details are known.
- **Packaging:** Xcode archive → Developer ID sign → notarize → `.dmg` (e.g. `hdiutil`). Requires Apple Developer account for distribution outside dev.

## Android (Kotlin/Jetpack Compose)
- **Networking:** `NsdManager` for discovery, `ServerSocket`/`Socket` or Network.framework equivalents via Java APIs with TLS (SSLEngine/Conscrypt); coroutines/Flow for streaming.
- **Hotspot:** `WifiManager.startLocalOnlyHotspot` (API 26+) for host; QR carries system-assigned SSID/passphrase and host endpoint; join via WifiNetworkSpecifier (API 29+) or user-guided connect fallback.
- **Files:** Storage Access Framework / MediaStore as appropriate; foreground service + persistent notification during transfers.
- **Packaging:** Gradle `assembleRelease` producing signed `.apk` (keystore/signing config).

## Interop
- Shared golden fixtures in `shared/fixtures/` (manifest examples, frame vectors) for unit tests on both platforms.
- Real-device validation matrix: Mac↔Android, each Wi-Fi mode, accept/decline, cancel, large file/folder.

## Known risks
- AP/client isolation blocks same-Wi-Fi peer traffic → detect and suggest hotspot.
- Asymmetric hotspot hosting capability (Android host straightforward; macOS limited).
- Backgrounding, network changes, storage limits; enforce streaming and preflight size checks.
