import Foundation
import CryptoKit
import OpenShareCore

final class TransferEngine {
    struct BuiltManifest {
        let offer: TransferOffer
        let baseURL: URL
        let urlByEntryId: [String: URL]
    }

    func buildManifest(from urls: [URL]) throws -> BuiltManifest {
        var entries: [ManifestEntry] = []
        var urlById: [String: URL] = [:]
        var total: Int64 = 0
        let fm = FileManager.default
        let rootName = urls.count == 1 ? urls[0].lastPathComponent : "Transfer"
        let base = urls.count == 1 ? urls[0].deletingLastPathComponent() : commonParent(of: urls)

        for url in urls {
            let vals = try url.resourceValues(forKeys: [.isDirectoryKey, .fileSizeKey, .contentModificationDateKey])
            let relRoot = relativePath(for: url, base: base)
            guard PathSafety.isSafe(relativePath: relRoot) else { throw TransferError.unsafePath(relRoot) }
            if vals.isDirectory == true {
                let dirId = UUID().uuidString
                entries.append(ManifestEntry(id: dirId, type: .dir, path: relRoot, size: nil, sha256: nil, mtime: vals.contentModificationDate.map { Int64($0.timeIntervalSince1970) }))
                guard let en = fm.enumerator(at: url, includingPropertiesForKeys: [.isDirectoryKey, .fileSizeKey, .contentModificationDateKey]) else { continue }
                for case let child as URL in en {
                    let childVals = try child.resourceValues(forKeys: [.isDirectoryKey, .fileSizeKey, .contentModificationDateKey])
                    let rel = relativePath(for: child, base: base)
                    guard PathSafety.isSafe(relativePath: rel) else { throw TransferError.unsafePath(rel) }
                    if childVals.isDirectory == true {
                        entries.append(ManifestEntry(id: UUID().uuidString, type: .dir, path: rel, size: nil, sha256: nil, mtime: childVals.contentModificationDate.map { Int64($0.timeIntervalSince1970) }))
                    } else {
                        let size = Int64(childVals.fileSize ?? 0)
                        let id = UUID().uuidString
                        total += size
                        entries.append(ManifestEntry(id: id, type: .file, path: rel, size: size, sha256: nil, mtime: childVals.contentModificationDate.map { Int64($0.timeIntervalSince1970) }))
                        urlById[id] = child
                    }
                }
            } else {
                let size = Int64(vals.fileSize ?? 0)
                let id = UUID().uuidString
                total += size
                entries.append(ManifestEntry(id: id, type: .file, path: relRoot, size: size, sha256: nil, mtime: vals.contentModificationDate.map { Int64($0.timeIntervalSince1970) }))
                urlById[id] = url
            }
        }
        let offer = TransferOffer(transferId: UUID().uuidString, totalBytes: total, fileCount: entries.filter { $0.type == .file }.count, rootName: rootName, entries: entries)
        return BuiltManifest(offer: offer, baseURL: base, urlByEntryId: urlById)
    }

    func sha256Hex(fileURL: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: fileURL)
        defer { try? handle.close() }
        var hasher = SHA256()
        while true {
            let data = try handle.read(upToCount: 1024 * 1024) ?? Data()
            if data.isEmpty { break }
            hasher.update(data: data)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    func stagingURL(for relativePath: String, in stagingRoot: URL) throws -> URL {
        let safe = PathSafety.normalized(relativePath)
        guard PathSafety.isSafe(relativePath: safe) else { throw TransferError.unsafePath(safe) }
        let dest = stagingRoot.appendingPathComponent(safe)
        try FileManager.default.createDirectory(at: dest.deletingLastPathComponent(), withIntermediateDirectories: true)
        return dest
    }

    func availableDiskBytes(at url: URL) -> Int64? {
        let vals = try? url.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        if let v = vals?.volumeAvailableCapacityForImportantUsage { return Int64(v) }
        return nil
    }

    private func relativePath(for url: URL, base: URL) -> String {
        let path = url.standardizedFileURL.path
        let basePath = base.standardizedFileURL.path
        if path == basePath { return url.lastPathComponent }
        if path.hasPrefix(basePath + "/") { return String(path.dropFirst(basePath.count + 1)) }
        return url.lastPathComponent
    }

    private func commonParent(of urls: [URL]) -> URL {
        guard let first = urls.first else { return URL(fileURLWithPath: NSTemporaryDirectory()) }
        return first.deletingLastPathComponent()
    }
}

enum TransferError: LocalizedError {
    case unsafePath(String)
    case diskFull
    case hashMismatch(String)
    case io(String)
    case cancelled
    case remote(String)
    var errorDescription: String? {
        switch self {
        case .unsafePath(let p): return "Unsafe path: \(p)"
        case .diskFull: return "Insufficient disk space"
        case .hashMismatch(let f): return "Hash mismatch: \(f)"
        case .io(let m): return m
        case .cancelled: return "Cancelled"
        case .remote(let m): return m
        }
    }
}
