# macOS (OpenShareMac)

SwiftUI + Network.framework skeleton for the Wi-Fi MVP.

## Build (dev)
- Requires Xcode / Swift toolchain on macOS 13+.
- Open as Swift package: `cd macos/OpenShareMac && swift build` (or open in Xcode).
- For a distributable app bundle + `.dmg` you need an Xcode app target, signing, and notarization:

```bash
# example (after creating Xcode project/target)
xcodebuild -scheme OpenShareMac -configuration Release archive -archivePath build/OpenShareMac.xcarchive
# export signed app, then:
hdiutil create -volname OpenShare -srcfolder OpenShareMac.app -ov -format UDZO OpenShare.dmg
# notarize + staple with notarytool (Developer ID required)
```

## Implemented skeleton
- Mode/role UI, session state model
- Bonjour browse/advertise scaffold
- TLS connection + length-delimited framing helpers
- Manifest builder, path-safety checks, SHA-256 streaming helper

## TODO
- Complete TLS identity/pinning, handshake + explicit accept UI flow
- Chunk send/receive loop with ACKs, staging/finalize, progress wiring
- Hotspot QR scan/join flow (camera) and endpoint connect
- Real `.dmg` packaging pipeline in CI/release scripts
