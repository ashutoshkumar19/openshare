#!/usr/bin/env bash
set -euo pipefail
# Builds release binary, assembles .app, ad-hoc signs, and creates unsigned DMG.
# For distributable release: replace ad-hoc signing with Developer ID, add entitlements, and notarize+staple.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG="$ROOT/macos/OpenShareMac"
APP="$ROOT/build/macos/OpenShare.app"
OUT="$ROOT/build/macos/OpenShare-macOS-mvp-unsigned.dmg"
cd "$PKG" && swift build -c release
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$PKG/.build/release/OpenShareMac" "$APP/Contents/MacOS/OpenShareMac"
chmod +x "$APP/Contents/MacOS/OpenShareMac"
cp "$PKG/Resources/AppIcon.icns" "$APP/Contents/Resources/AppIcon.icns"
cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleName</key><string>OpenShare</string>
  <key>CFBundleDisplayName</key><string>OpenShare</string>
  <key>CFBundleIdentifier</key><string>com.openshare.mac</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>CFBundleShortVersionString</key><string>0.1.0-mvp</string>
  <key>CFBundleExecutable</key><string>OpenShareMac</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>LSMinimumSystemVersion</key><string>13.0</string>
  <key>CFBundleIconFile</key><string>AppIcon</string>
  <key>CFBundleIconName</key><string>AppIcon</string>
  <key>NSLocalNetworkUsageDescription</key><string>Discover and transfer files to nearby devices on your local network.</string>
  <key>NSCameraUsageDescription</key><string>Scan QR codes to connect for hotspot transfers.</string>
  <key>NSBonjourServices</key>
  <array><string>_openshare._tcp</string></array>
</dict>
</plist>
PLIST
codesign --force --deep --sign - "$APP" || true
hdiutil create -volname "OpenShare" -srcfolder "$APP" -ov -format UDZO "$OUT"
echo "Created $OUT (unsigned/ad-hoc). Notarize for distribution."
