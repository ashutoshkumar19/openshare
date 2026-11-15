import Foundation
import Combine
import AppKit
import Network
import OpenShareCore

enum TransferMode: String, CaseIterable, Identifiable {
    case sameWifi = "Same Wi‑Fi"
    case hotspot = "Hotspot Host/Join"
    var id: String { rawValue }
}

enum Role: String, CaseIterable, Identifiable {
    case send = "Send"
    case receive = "Receive"
    var id: String { rawValue }
}

enum SessionState: String {
    case idle, discovering, pairing, awaitingAccept, transferring, verifying, complete, failed, cancelled
}

struct TransferProgress {
    var fileName: String = ""
    var bytesSent: Int64 = 0
    var totalBytes: Int64 = 0
    var filesDone: Int = 0
    var filesTotal: Int = 0
}

@MainActor
final class AppState: ObservableObject {
    @Published var mode: TransferMode = .sameWifi
    @Published var role: Role = .receive
    @Published var sessionState: SessionState = .idle
    @Published var peers: [PeerDevice] = []
    @Published var status: String = "Select mode and role to begin."
    @Published var progress = TransferProgress()
    @Published var transferStart: Date? = nil
    @Published var elapsed: TimeInterval = 0
    @Published var totalDuration: TimeInterval? = nil
    @Published var localDeviceName: String = Host.current().localizedName ?? "Mac"
    @Published var sessionToken: String = UUID().uuidString
    @Published var incomingOffer: IncomingOffer?
    @Published var selectedURLs: [URL] = []
    @Published var hotspotPayloadText: String = ""
    @Published var manualHost: String = ""
    @Published var manualPort: String = "8765"
    @Published var manualToken: String = ""
    @Published var scannedHotspot: HotspotQRPayload? = nil

    let discovery = DiscoveryService()
    let transferEngine = TransferEngine()
    private let tls = TLSService()
    private var session: SessionController?
    private var pendingConnection: NWConnection?
    private var elapsedTimer: Timer?

    func formatDuration(_ t: TimeInterval) -> String {
        let s = max(0, Int(t.rounded()))
        let h = s / 3600, m = (s % 3600) / 60, sec = s % 60
        if h > 0 { return String(format: "%d:%02d:%02d", h, m, sec) }
        return String(format: "%d:%02d", m, sec)
    }

    private func startElapsedTimer(from start: Date) {
        elapsedTimer?.invalidate()
        elapsed = 0
        elapsedTimer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, self.transferStart != nil else { return }
                self.elapsed = Date().timeIntervalSince(start)
            }
        }
    }

    private func stopElapsedTimer(finalize: Bool) {
        if let start = transferStart {
            let d = Date().timeIntervalSince(start)
            elapsed = d
            if finalize { totalDuration = d }
        }
        elapsedTimer?.invalidate(); elapsedTimer = nil
    }

    func start() {
        sessionToken = UUID().uuidString
        progress = TransferProgress()
        transferStart = nil; elapsed = 0; totalDuration = nil; elapsedTimer?.invalidate(); elapsedTimer = nil
        incomingOffer = nil
        status = "Starting \(mode.rawValue) as \(role.rawValue)…"
        switch mode {
        case .sameWifi:
            if role == .receive { startReceiver() } else { startSenderBrowse() }
            sessionState = .discovering
        case .hotspot:
            if role == .receive {
                sessionState = .idle
                status = "macOS cannot host an OpenShare hotspot session. Use an Android device as host/receiver (Hotspot + Receive + Start) and scan its OpenShare QR from this Mac, or use Same Wi‑Fi mode."
            } else {
                sessionState = .pairing
                if scannedHotspot != nil {
                    status = "Scanned payload ready. Join the host Wi‑Fi, then click Connect & Start Transfer."
                } else if selectedURLs.isEmpty {
                    status = "Hotspot send ready: 1) Choose files/folders. 2) Scan host OpenShare QR. 3) Join host Wi‑Fi. 4) Connect."
                } else {
                    status = "Hotspot send ready: scan the host OpenShare QR, join its Wi‑Fi, then connect."
                }
            }
        }
    }

    private func startReceiver() {
        do {
            try tls.listen(port: ProtocolInfo.defaultPort)
            tls.onAccepted = { [weak self] conn in
                Task { @MainActor in self?.attachIncoming(conn) }
            }
            discovery.startAdvertising(name: localDeviceName, port: Int(ProtocolInfo.defaultPort))
            status = "Advertising as \(localDeviceName). Waiting for sender…"
        } catch {
            status = "Listen failed: \(error.localizedDescription)"; sessionState = .failed
        }
    }

    private func startSenderBrowse() {
        discovery.startBrowsing { [weak self] found in Task { @MainActor in self?.peers = found } }
        status = "Browsing for receivers on local network…"
    }

    func attachIncoming(_ conn: NWConnection) {
        guard role == .receive else { conn.cancel(); return }
        discovery.stop()
        sessionState = .pairing
        status = "Sender connected. Handshaking…"
        let s = SessionController(role: .receiver, token: sessionToken, deviceName: localDeviceName)
        bind(session: s)
        session = s
        s.attach(connection: conn)
    }

    func connect(to peer: PeerDevice) {
        guard role == .send else { return }
        sessionState = .pairing
        status = "Connecting to \(peer.name)…"
        discovery.stop()
        let conn = tls.connect(host: peer.host, port: peer.port)
        let s = SessionController(role: .sender, token: sessionToken, deviceName: localDeviceName)
        bind(session: s)
        session = s
        if !selectedURLs.isEmpty {
            do { s.setSendManifest(try transferEngine.buildManifest(from: selectedURLs)) } catch { status = "Manifest failed: \(error.localizedDescription)"; return }
        }
        s.attach(connection: conn)
    }

    func ingestScannedPayload(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        hotspotPayloadText = trimmed
        guard !trimmed.isEmpty else { scannedHotspot = nil; status = "No QR payload scanned."; return }
        if trimmed.hasPrefix("WIFI:") || trimmed.uppercased().hasPrefix("WIFI:") {
            scannedHotspot = nil
            let ssid = trimmed.components(separatedBy: "S:").dropFirst().first?.components(separatedBy: ";").first ?? ""
            let pass = trimmed.components(separatedBy: "P:").dropFirst().first?.components(separatedBy: ";").first ?? ""
            var msg = "Scanned system Wi‑Fi QR" + (ssid.isEmpty ? "" : " (SSID: \(ssid))")
            if !pass.isEmpty { msg += " (password: \(pass))" }
            msg += ". Join that hotspot in macOS Wi‑Fi Settings. This QR does not include OpenShare session info — scan the OpenShare QR shown in the host app to connect."
            status = msg
            return
        }
        guard trimmed.hasPrefix("{"), let data = trimmed.data(using: .utf8) else {
            scannedHotspot = nil
            status = "Unsupported QR content. Scan the OpenShare hotspot payload QR (JSON) shown by the host app."
            return
        }
        do {
            let p = try JSONDecoder().decode(HotspotQRPayload.self, from: data)
            scannedHotspot = p
            sessionToken = p.token
            manualHost = p.host; manualPort = String(p.port)
            status = "OpenShare QR scanned for '\(p.name)'. Join Wi‑Fi '\(p.ssid)' in System Settings, then click Connect & Start Transfer."
        } catch {
            scannedHotspot = nil
            status = "Invalid OpenShare QR payload: \(error.localizedDescription). Use the JSON payload shown by the host app."
        }
    }

    func connectScannedHotspot() {
        guard let p = scannedHotspot else { status = "Scan a host OpenShare QR first."; return }
        if role == .send && selectedURLs.isEmpty { status = "Select files/folders before connecting."; return }
        status = "Connecting to \(p.host):\(p.port)… Make sure this Mac is joined to Wi‑Fi '\(p.ssid)'."
        connectManual(host: p.host, port: UInt16(p.port), token: p.token)
    }

    func connectHotspotFromPayload() {
        let trimmed = hotspotPayloadText.trimmingCharacters(in: .whitespacesAndNewlines)
        if let p = scannedHotspot, !trimmed.isEmpty, trimmed == (try? String(data: JSONEncoder().encode(p), encoding: .utf8)) ?? hotspotPayloadText.trimmingCharacters(in: .whitespacesAndNewlines) {
            connectScannedHotspot(); return
        }
        guard !trimmed.isEmpty else { status = "No QR payload to connect. Scan or paste an OpenShare hotspot payload."; return }
        if trimmed.hasPrefix("WIFI:") || trimmed.uppercased().hasPrefix("WIFI:") { ingestScannedPayload(trimmed); return }
        guard trimmed.hasPrefix("{"), let data = trimmed.data(using: .utf8) else { ingestScannedPayload(trimmed); return }
        do {
            let p = try JSONDecoder().decode(HotspotQRPayload.self, from: data)
            scannedHotspot = p
            sessionToken = p.token
            manualHost = p.host; manualPort = String(p.port)
            connectScannedHotspot()
        } catch {
            ingestScannedPayload(trimmed)
        }
    }

    func connectManual(host: String, port: UInt16, token: String) {
        // reset any previous attempt
        session?.cancel()
        session = nil
        sessionToken = token
        sessionState = .pairing
        status = "Connecting to \(host):\(port)…"
        let conn = tls.connect(host: host, port: port)
        let role: SessionController.Role = (self.role == .send) ? .sender : .receiver
        let s = SessionController(role: role, token: token, deviceName: localDeviceName)
        bind(session: s)
        session = s
        if self.role == .send, !selectedURLs.isEmpty {
            do { s.setSendManifest(try transferEngine.buildManifest(from: selectedURLs)) } catch { status = "Manifest failed: \(error.localizedDescription)"; return }
        }
        s.attach(connection: conn)
    }

    func chooseFiles() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = true; panel.canChooseDirectories = true; panel.allowsMultipleSelection = true
        if panel.runModal() == .OK { selectedURLs = panel.urls; status = "Selected \(panel.urls.count) item(s)." }
    }

    func acceptIncoming() {
        guard let offer = incomingOffer else { return }
        let panel = NSOpenPanel()
        panel.canChooseFiles = false; panel.canChooseDirectories = true; panel.canCreateDirectories = true
        panel.prompt = "Receive Here"
        panel.message = "Choose destination for \(offer.offer.fileCount) file(s) (\(offer.offer.totalBytes) bytes)"
        if panel.runModal() == .OK, let dest = panel.url {
            incomingOffer = nil
            session?.acceptOffer(to: dest)
        }
    }

    func declineIncoming() { session?.declineOffer(reason: "User declined"); incomingOffer = nil }

    func cancelTransfer() { session?.cancel() }

    func stop() {
        session?.cancel()
        session = nil
        discovery.stop(); tls.stop()
        stopElapsedTimer(finalize: false)
        sessionState = .idle; peers = []; incomingOffer = nil
        status = "Stopped."
    }

    private func bind(session s: SessionController) {
        s.onStatus = { [weak self] msg in Task { @MainActor in self?.status = msg } }
        s.onState = { [weak self] st in
            Task { @MainActor in
                guard let self else { return }
                self.sessionState = st
                if st == .transferring && self.transferStart == nil {
                    self.transferStart = Date(); self.totalDuration = nil; self.startElapsedTimer(from: self.transferStart!)
                }
                if st == .complete { self.stopElapsedTimer(finalize: true) }
                if st == .failed || st == .cancelled { self.stopElapsedTimer(finalize: false) }
            }
        }
        s.onProgress = { [weak self] p in Task { @MainActor in self?.progress = p } }
        s.onIncomingOffer = { [weak self] offer in Task { @MainActor in self?.incomingOffer = offer; self?.status = "Incoming offer from \(offer.from)" } }
        s.onFailure = { [weak self] msg in Task { @MainActor in self?.status = msg; self?.sessionState = .failed; self?.stopElapsedTimer(finalize: false) } }
        s.onComplete = { [weak self] in Task { @MainActor in self?.status = "Transfer complete."; self?.sessionState = .complete; self?.stopElapsedTimer(finalize: true) } }
    }
}
