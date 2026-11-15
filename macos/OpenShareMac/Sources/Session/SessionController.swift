import Foundation
import Network
import CryptoKit
import OpenShareCore

struct IncomingOffer: Identifiable {
    let id: String
    let offer: TransferOffer
    let from: String
}

final class SessionController {
    enum Role { case sender, receiver }

    let role: Role
    private(set) var token: String
    let deviceName: String
    private var framed: FramedConnection?
    private let engine = TransferEngine()
    private let queue = DispatchQueue(label: "openshare.session")
    private var pendingManifest: TransferEngine.BuiltManifest?
    private var pendingOffer: TransferOffer?
    private var cancelled = false
    private var receiveState: ReceiveState?
    private var destinationRoot: URL?
    private var stagingRoot: URL?
    private var helloPeer: Hello?
    private var offerSent = false
    private var keepaliveTimer: DispatchSourceTimer?
    private let ackLock = NSCondition()
    private var ackedFileId: String?
    private var ackedChunkIndex: UInt64?

    var onStatus: ((String) -> Void)?
    var onState: ((SessionState) -> Void)?
    var onProgress: ((TransferProgress) -> Void)?
    var onIncomingOffer: ((IncomingOffer) -> Void)?
    var onComplete: (() -> Void)?
    var onFailure: ((String) -> Void)?

    init(role: Role, token: String, deviceName: String) {
        self.role = role; self.token = token; self.deviceName = deviceName
    }

    func attach(connection: NWConnection) {
        let f = FramedConnection(connection: connection)
        self.framed = f
        f.onFrame = { [weak self] type, payload in self?.handle(type: type, payload: payload) }
        f.onClose = { [weak self] err in
            guard let self, !self.cancelled else { return }
            if let err { self.fail("Connection closed: \(err.localizedDescription)") }
        }
        f.start()
        if role == .sender {
            onStatus?("Handshaking…")
            sendHello()
            queue.asyncAfter(deadline: .now() + 0.15) { [weak self] in self?.sendOfferIfReady() }
        } else {
            onStatus?("Waiting for sender handshake…")
        }
        startKeepalive()
    }

    func setSendManifest(_ manifest: TransferEngine.BuiltManifest) { pendingManifest = manifest }

    func acceptOffer(to destination: URL) {
        guard let offer = pendingOffer else { return }
        destinationRoot = destination
        stagingRoot = destination.appendingPathComponent(".openshare-staging-\(offer.transferId)")
        do {
            try FileManager.default.createDirectory(at: stagingRoot!, withIntermediateDirectories: true)
            for e in offer.entries where e.type == .dir && PathSafety.isSafe(relativePath: e.path) {
                try FileManager.default.createDirectory(at: destination.appendingPathComponent(e.path), withIntermediateDirectories: true)
            }
        } catch { fail("Failed to prepare destination: \(error.localizedDescription)"); return }
        if let avail = engine.availableDiskBytes(at: destination), avail < offer.totalBytes {
            sendError(.diskFull, "Insufficient disk space"); fail("Insufficient disk space"); return
        }
        receiveState = ReceiveState(offer: offer)
        framed?.sendJSON(AcceptMsg(transferId: offer.transferId), type: .accept)
        onState?(.transferring); onStatus?("Receiving…")
    }

    func declineOffer(reason: String? = nil) {
        guard let offer = pendingOffer else { return }
        framed?.sendJSON(DeclineMsg(transferId: offer.transferId, reason: reason), type: .decline)
        onState?(.cancelled)
    }

    func cancel() {
        cancelled = true
        stopKeepalive()
        framed?.sendJSON(CancelMsg(reason: "user"), type: .cancel)
        framed?.close(); cleanupStaging(); onState?(.cancelled)
    }

    private func startKeepalive() {
        stopKeepalive()
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + 5, repeating: 5)
        t.setEventHandler { [weak self] in
            guard let self, !self.cancelled else { return }
            self.framed?.send(type: .ping, payload: Data())
        }
        keepaliveTimer = t
        t.resume()
    }

    private func stopKeepalive() {
        keepaliveTimer?.cancel()
        keepaliveTimer = nil
    }

    private func sendHello() {
        let hello = Hello(v: ProtocolInfo.version, deviceId: UUID().uuidString, deviceName: deviceName, platform: "macos", token: token, caps: ["files","folders","sha256"])
        framed?.sendJSON(hello, type: .hello)
    }

    private func sendOfferIfReady() {
        guard role == .sender, !offerSent, let manifest = pendingManifest else { return }
        offerSent = true
        framed?.sendJSON(manifest.offer, type: .transferOffer)
        onState?(.awaitingAccept)
        onStatus?("Offer sent, waiting for accept…")
    }

    private func handle(type: FrameType, payload: Data) {
        switch type {
        case .hello:
            guard let peer = try? FrameCodec.decodeJSON(Hello.self, from: payload) else { return }
            helloPeer = peer
            if peer.v != ProtocolInfo.version { framed?.sendJSON(HelloAck(ok: false, reason: "VERSION_UNSUPPORTED"), type: .helloAck); fail("Version unsupported"); return }
            if role == .receiver { token = peer.token }
            framed?.sendJSON(HelloAck(ok: true, reason: nil), type: .helloAck)
            if role == .receiver { onStatus?("Connected. Waiting for offer…") }
        case .helloAck:
            if let ack = try? FrameCodec.decodeJSON(HelloAck.self, from: payload) {
                if !ack.ok { fail(ack.reason ?? "Handshake rejected"); return }
            }
            sendOfferIfReady()
        case .transferOffer:
            guard role == .receiver, let offer = try? FrameCodec.decodeJSON(TransferOffer.self, from: payload) else { return }
            pendingOffer = offer
            onState?(.awaitingAccept)
            onIncomingOffer?(IncomingOffer(id: offer.transferId, offer: offer, from: helloPeer?.deviceName ?? "Peer"))
        case .accept:
            guard role == .sender, let manifest = pendingManifest else { return }
            onState?(.transferring); onStatus?("Sending…")
            queue.async { [weak self] in self?.sendTransfer(manifest) }
        case .decline: fail("Receiver declined")
        case .chunk: guard role == .receiver else { return }; handleChunk(payload)
        case .chunkAck:
            guard role == .sender, let msg = try? FrameCodec.decodeJSON(ChunkAckMsg.self, from: payload) else { return }
            ackLock.lock(); ackedFileId = msg.fileId; ackedChunkIndex = msg.chunkIndex; ackLock.broadcast(); ackLock.unlock()
        case .fileDone:
            guard role == .receiver, let msg = try? FrameCodec.decodeJSON(FileDoneMsg.self, from: payload) else { return }
            verifyAndFinalize(fileId: msg.fileId, expectedHash: msg.sha256)
        case .complete:
            if role == .sender { self.stopKeepalive(); DispatchQueue.main.async { [weak self] in self?.onState?(.complete); self?.onStatus?("Transfer complete"); self?.onComplete?() } }
        case .cancel:
            cancelled = true; stopKeepalive(); cleanupStaging(); DispatchQueue.main.async { [weak self] in self?.onState?(.cancelled); self?.onFailure?("Cancelled by peer"); self?.onStatus?("Cancelled by peer") }
        case .error:
            let msg = (try? FrameCodec.decodeJSON(ErrorMsg.self, from: payload))?.message ?? "Remote error"; fail(msg)
        case .ping: framed?.send(type: .pong, payload: Data())
        default: break
        }
    }

    private func sendChunkAndWaitAck(fileId: String, chunkIndex: UInt64, endOfFile: Bool, data: Data) throws {
        ackLock.lock(); ackedFileId = nil; ackedChunkIndex = nil; ackLock.unlock()
        framed?.send(type: .chunk, payload: ChunkHeader(fileId: fileId, chunkIndex: chunkIndex, endOfFile: endOfFile, crc32c: CRC32.checksum(data), data: data).encode())
        let deadline = Date().addingTimeInterval(60)
        ackLock.lock()
        while !(ackedFileId == fileId && ackedChunkIndex == chunkIndex) {
            if cancelled { ackLock.unlock(); throw TransferError.cancelled }
            if !ackLock.wait(until: Date(timeIntervalSinceNow: 0.5)) { /* timeout slice */ }
            if Date() > deadline { ackLock.unlock(); throw NSError(domain: "OpenShare", code: 408, userInfo: [NSLocalizedDescriptionKey: "Timed out waiting for receiver acknowledgement"]) }
        }
        ackLock.unlock()
    }

    private func sendTransfer(_ manifest: TransferEngine.BuiltManifest) {
        do {
            var sentBytes: Int64 = 0; var filesDone = 0
            let filesTotal = manifest.offer.fileCount
            for entry in manifest.offer.entries where entry.type == .file {
                guard !cancelled else { throw TransferError.cancelled }
                guard let url = manifest.urlByEntryId[entry.id] else { continue }
                let hash = try engine.sha256Hex(fileURL: url)
                let handle = try FileHandle(forReadingFrom: url)
                defer { try? handle.close() }
                let attrs = try FileManager.default.attributesOfItem(atPath: url.path)
                let size = (attrs[.size] as? NSNumber)?.int64Value ?? 0
                var idx: UInt64 = 0; var remaining = size
                if size == 0 {
                    try sendChunkAndWaitAck(fileId: entry.id, chunkIndex: 0, endOfFile: true, data: Data())
                } else {
                    while remaining > 0 {
                        if cancelled { throw TransferError.cancelled }
                        let chunk = try handle.read(upToCount: ProtocolInfo.chunkSize) ?? Data()
                        if chunk.isEmpty { break }
                        remaining -= Int64(chunk.count)
                        let eof = remaining <= 0
                        try sendChunkAndWaitAck(fileId: entry.id, chunkIndex: idx, endOfFile: eof, data: chunk)
                        sentBytes += Int64(chunk.count); idx += 1
                        let p = TransferProgress(fileName: entry.path, bytesSent: sentBytes, totalBytes: manifest.offer.totalBytes, filesDone: filesDone, filesTotal: filesTotal)
                        DispatchQueue.main.async { [weak self] in self?.onProgress?(p) }
                    }
                }
                framed?.sendJSON(FileDoneMsg(fileId: entry.id, sha256: hash), type: .fileDone)
                filesDone += 1
                let p = TransferProgress(fileName: entry.path, bytesSent: sentBytes, totalBytes: manifest.offer.totalBytes, filesDone: filesDone, filesTotal: filesTotal)
                DispatchQueue.main.async { [weak self] in self?.onProgress?(p) }
            }
            DispatchQueue.main.async { [weak self] in self?.onState?(.verifying); self?.onStatus?("All data received by receiver. Verifying…") }
        } catch { fail(error.localizedDescription) }
    }

    private func handleChunk(_ payload: Data) {
        guard let header = ChunkHeader.decode(payload), var rs = receiveState, let stagingRoot else { return }
        do {
            guard let entry = rs.entryById[header.fileId], PathSafety.isSafe(relativePath: entry.path) else { sendError(.pathUnsafe, "Unsafe path"); return }
            if header.crc32c != 0 {
                let actualCrc = CRC32.checksum(header.data)
                guard actualCrc == header.crc32c else { sendError(.hashMismatch, "Chunk CRC mismatch: \(entry.path)"); fail("Chunk CRC mismatch"); return }
            }
            let part = try engine.stagingURL(for: entry.path + ".part", in: stagingRoot)
            if !FileManager.default.fileExists(atPath: part.path) { FileManager.default.createFile(atPath: part.path, contents: nil) }
            let handle = try FileHandle(forWritingTo: part)
            defer { try? handle.close() }
            try handle.seekToEnd(); try handle.write(contentsOf: header.data)
            rs.bytesReceived += Int64(header.data.count); receiveState = rs
            framed?.sendJSON(ChunkAckMsg(fileId: header.fileId, chunkIndex: header.chunkIndex), type: .chunkAck)
            let p = TransferProgress(fileName: entry.path, bytesSent: rs.bytesReceived, totalBytes: rs.offer.totalBytes, filesDone: rs.filesVerified, filesTotal: rs.offer.fileCount)
            DispatchQueue.main.async { [weak self] in self?.onProgress?(p) }
        } catch { sendError(.ioError, error.localizedDescription); fail(error.localizedDescription) }
    }

    private func verifyAndFinalize(fileId: String, expectedHash: String) {
        guard var rs = receiveState, let stagingRoot, let destRoot = destinationRoot else { return }
        guard let entry = rs.entryById[fileId] else { return }
        do {
            let part = try engine.stagingURL(for: entry.path + ".part", in: stagingRoot)
            let actual = try engine.sha256Hex(fileURL: part)
            guard actual.lowercased() == expectedHash.lowercased() else { sendError(.hashMismatch, entry.path); fail("Hash mismatch: \(entry.path)"); return }
            let final = destRoot.appendingPathComponent(entry.path)
            try FileManager.default.createDirectory(at: final.deletingLastPathComponent(), withIntermediateDirectories: true)
            if FileManager.default.fileExists(atPath: final.path) { try? FileManager.default.removeItem(at: final) }
            try FileManager.default.moveItem(at: part, to: final)
            rs.filesVerified += 1; receiveState = rs
            framed?.sendJSON(FileVerifiedMsg(fileId: fileId), type: .fileVerified)
            if rs.filesVerified >= rs.offer.fileCount {
                framed?.sendJSON(CompleteMsg(transferId: rs.offer.transferId), type: .complete)
                cleanupStaging()
                stopKeepalive()
                DispatchQueue.main.async { [weak self] in self?.onState?(.complete); self?.onStatus?("Transfer complete"); self?.onComplete?() }
            }
        } catch { sendError(.ioError, error.localizedDescription); fail(error.localizedDescription) }
    }

    private func sendError(_ code: ProtocolErrorCode, _ message: String) { framed?.sendJSON(ErrorMsg(code: code.rawValue, message: message), type: .error) }
    private func fail(_ message: String) {
        stopKeepalive()
        ackLock.lock(); ackedFileId = "__failed__"; ackedChunkIndex = nil; ackLock.broadcast(); ackLock.unlock()
        DispatchQueue.main.async { [weak self] in self?.onState?(.failed); self?.onFailure?(message); self?.onStatus?(message) }
    }
    private func cleanupStaging() { if let s = stagingRoot { try? FileManager.default.removeItem(at: s) } }
}

private struct ReceiveState {
    let offer: TransferOffer
    let entryById: [String: ManifestEntry]
    var bytesReceived: Int64 = 0
    var filesVerified: Int = 0
    init(offer: TransferOffer) { self.offer = offer; self.entryById = Dictionary(uniqueKeysWithValues: offer.entries.map { ($0.id, $0) }) }
}
