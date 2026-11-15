# OpenShare Transfer Protocol (MVP, v1)

This document defines the shared, versioned protocol for offline Mac↔Android transfers over local Wi-Fi. It is the source of truth for both native implementations.

## 1. Goals
- Offline, no internet/relay.
- One sender → one receiver per session (extensible to multi-peer later).
- Streamed file/folder transfers with integrity verification.
- Mode-agnostic data path: same-Wi-Fi and hotspot differ only in network bring-up/discovery.

## 2. Service discovery (same Wi-Fi)
- **Service type:** `_openshare._tcp.local.`
- **Transport:** TCP, TLS 1.2+ required.
- **TXT records (advertised by receiver/listener):**
  - `v=1` (protocol major)
  - `id=<instance-uuid>`
  - `name=<display-name>`
  - `port=<tcp-port>`
  - `caps=files,folders,sha256`
- **Browse/resolve:** macOS via Bonjour (Network.framework/NetService), Android via NSD (`NsdManager`). Device list is advisory; user selects peer and receiver must explicitly accept.

## 3. Hotspot bootstrap (QR)
When a host creates a hotspot and service endpoint it presents a QR payload (UTF-8 JSON, Base64URL allowed for transport):
```json
{
  "mode": "hotspot",
  "v": 1,
  "ssid": "<ssid>",
  "passphrase": "<passphrase|null>",
  "host": "<ipv4-or-hostname>",
  "port": 8765,
  "token": "<short-lived-session-token>",
  "name": "<host-display-name>"
}
```
- Joiner connects to `ssid`, then opens TLS TCP to `host:port` and presents `token` in handshake.
- Android Local Only Hotspot credentials are system-assigned; QR must reflect actual values. macOS hosting is constrained (see architecture doc).

## 4. Connection and security
- **TLS:** required for control and data. MVP uses an ephemeral self-signed certificate per session/app instance.
- **Token binding:** a short-lived random session token (>=128-bit entropy, base64url) is issued per session. Same-Wi-Fi: exchanged in handshake and shown/confirmed via accept flow. Hotspot: delivered via QR. Peers must reject mismatched/expired tokens.
- **Trust model:** TOFU bound to token within the session; explicit receiver accept required before any file bytes are written to final destination. Not a replacement for user verification on hostile LANs, but prevents casual snooping/tampering.

## 5. Framing
Single multiplexed TLS TCP stream with length-delimited frames:
```
+---------+---------+------------------+
| u32 len | u8 type | payload (len-1)  |  (big-endian length)
+---------+---------+------------------+
```
- **Max frame payload:** 1 MiB (configurable cap); chunk payloads sized to fit.
- **Encoding:** JSON for control messages (`type` in 0x01–0x3F), binary for file chunks (`0x40+`). All JSON UTF-8.

### Frame types (MVP)
| Type | Name | Dir | Description |
|------|------|-----|-------------|
| 0x01 | HELLO | both | version, device id/name, caps, token |
| 0x02 | HELLO_ACK | both | acceptance or rejection reason |
| 0x03 | TRANSFER_OFFER | S→R | manifest summary/count/hash + entries ref/inline |
| 0x04 | ACCEPT | R→S | accept all (MVP all-or-nothing) |
| 0x05 | DECLINE | R→S | decline with reason |
| 0x06 | CHUNK | S→R | file data chunk (binary header + bytes) |
| 0x07 | CHUNK_ACK | R→S | ack by file id + chunk index (flow control/progress) |
| 0x08 | FILE_DONE | S→R | sender finished file, includes file hash |
| 0x09 | FILE_VERIFIED | R→S | receiver verified hash and finalized |
| 0x0A | COMPLETE | R→S | all entries verified/finalized |
| 0x0B | CANCEL | both | cancel session/transfer |
| 0x0C | ERROR | both | fatal/entry error with code |
| 0x0D | PING/PONG | both | optional keepalive |

## 6. Messages

### HELLO
```json
{
  "v": 1,
  "device_id": "uuid",
  "device_name": "Ashutosh’s MacBook",
  "platform": "macos|android",
  "token": "<session-token>",
  "caps": ["files", "folders", "sha256"]
}
```

### TRANSFER_OFFER
```json
{
  "transfer_id": "uuid",
  "total_bytes": 12345,
  "file_count": 10,
  "root_name": "Photos",
  "entries": [
    {"id":"e1","type":"dir","path":"Photos/2026"},
    {"id":"e2","type":"file","path":"Photos/2026/a.jpg","size":4567,"sha256":"<hex>","mtime":1731600000}
  ]
}
```
- Paths are relative, `/`-separated, UTF-8 NFC preferred. **Must reject** absolute paths, `..` segments, empty segments, NUL, or traversal outside transfer root.
- `sha256` may be omitted in offer and supplied in `FILE_DONE` if computed streaming; receiver must verify before finalize.

### CHUNK (binary payload layout)
```
file_id_len:u16 | file_id:utf8 | chunk_index:u64 | flags:u8 | crc32c:u32 | data:bytes
```
- `flags`: bit0=end-of-file for this entry. `crc32c` covers `data` only (integrity per chunk).

## 7. Transfer flow
1. **Connect:** TLS handshake → `HELLO`/`HELLO_ACK` (token validated).
2. **Offer:** sender builds manifest (files/dirs, sizes) → `TRANSFER_OFFER`.
3. **Accept:** receiver shows summary and explicitly accepts → `ACCEPT` (or `DECLINE`).
4. **Send:** for each file stream `CHUNK`s sequentially; receiver writes to staging (`.part`/temp dir), updates progress, emits `CHUNK_ACK` (batched/windowed) and on completion verifies hash → `FILE_VERIFIED`. Sender may send `FILE_DONE` with final hash.
5. **Complete:** after all entries verified and atomically moved into destination, receiver sends `COMPLETE`.
6. **Cancel/Error:** `CANCEL` or `ERROR` stops session; receiver discards or marks incomplete staging.

## 8. Storage and finalize rules
- Write to staging area on destination volume; fsync as appropriate; verify hash; atomic rename/move into final relative path; create directories as needed.
- Preflight: receiver should check available space against `total_bytes` before accepting (best-effort).
- Handle name collisions by policy (MVP: prompt or auto-rename with suffix, consistent across platforms).

## 9. Error codes (MVP)
`TOKEN_INVALID`, `VERSION_UNSUPPORTED`, `DECLINED`, `PATH_UNSAFE`, `HASH_MISMATCH`, `DISK_FULL`, `IO_ERROR`, `CANCELLED`, `TIMEOUT`, `NETWORK_ISOLATION_SUSPECTED`.

## 10. Extensibility
- Capability flags and `v` allow adding resume (chunk bitmaps), compression, multi-peer fan-out, and additional transports (e.g. Bluetooth) without breaking v1 peers that negotiate correctly.
