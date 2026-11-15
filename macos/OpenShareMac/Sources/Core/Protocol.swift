import Foundation

public struct ProtocolInfo {
    public static let version = 1
    public static let serviceType = "_openshare._tcp."
    public static let serviceDomain = "local."
    public static let defaultPort: UInt16 = 8765
    public static let maxFramePayload = 1_048_576
    public static let chunkSize = 256 * 1024
}

public enum FrameType: UInt8, Codable, Sendable {
    case hello = 0x01, helloAck = 0x02, transferOffer = 0x03, accept = 0x04, decline = 0x05
    case chunk = 0x06, chunkAck = 0x07, fileDone = 0x08, fileVerified = 0x09
    case complete = 0x0A, cancel = 0x0B, error = 0x0C, ping = 0x0D, pong = 0x0E
}

public enum EntryType: String, Codable, Sendable { case file, dir }

public struct ManifestEntry: Codable, Hashable, Sendable {
    public let id: String
    public let type: EntryType
    public let path: String
    public let size: Int64?
    public let sha256: String?
    public let mtime: Int64?
    public init(id: String, type: EntryType, path: String, size: Int64? = nil, sha256: String? = nil, mtime: Int64? = nil) {
        self.id = id; self.type = type; self.path = path; self.size = size; self.sha256 = sha256; self.mtime = mtime
    }
}

public struct TransferOffer: Codable, Sendable {
    public let transferId: String
    public let totalBytes: Int64
    public let fileCount: Int
    public let rootName: String
    public let entries: [ManifestEntry]
    public init(transferId: String, totalBytes: Int64, fileCount: Int, rootName: String, entries: [ManifestEntry]) {
        self.transferId = transferId; self.totalBytes = totalBytes; self.fileCount = fileCount; self.rootName = rootName; self.entries = entries
    }
}

public struct Hello: Codable, Sendable {
    public let v: Int
    public let deviceId: String
    public let deviceName: String
    public let platform: String
    public let token: String
    public let caps: [String]
    public init(v: Int, deviceId: String, deviceName: String, platform: String, token: String, caps: [String]) {
        self.v = v; self.deviceId = deviceId; self.deviceName = deviceName; self.platform = platform; self.token = token; self.caps = caps
    }
}

public struct HelloAck: Codable, Sendable {
    public let ok: Bool
    public let reason: String?
    public init(ok: Bool, reason: String? = nil) { self.ok = ok; self.reason = reason }
}

public struct AcceptMsg: Codable, Sendable { public let transferId: String; public init(transferId: String){self.transferId=transferId} }
public struct DeclineMsg: Codable, Sendable { public let transferId: String; public let reason: String?; public init(transferId:String,reason:String?=nil){self.transferId=transferId;self.reason=reason} }
public struct FileDoneMsg: Codable, Sendable { public let fileId: String; public let sha256: String; public init(fileId:String,sha256:String){self.fileId=fileId;self.sha256=sha256} }
public struct FileVerifiedMsg: Codable, Sendable { public let fileId: String; public init(fileId:String){self.fileId=fileId} }
public struct CompleteMsg: Codable, Sendable { public let transferId: String; public init(transferId:String){self.transferId=transferId} }
public struct CancelMsg: Codable, Sendable { public let reason: String?; public init(reason:String?=nil){self.reason=reason} }
public struct ErrorMsg: Codable, Sendable { public let code: String; public let message: String; public init(code:String,message:String){self.code=code;self.message=message} }
public struct ChunkAckMsg: Codable, Sendable { public let fileId: String; public let chunkIndex: UInt64; public init(fileId:String,chunkIndex:UInt64){self.fileId=fileId;self.chunkIndex=chunkIndex} }

public struct HotspotQRPayload: Codable, Sendable {
    public let mode: String
    public let v: Int
    public let ssid: String
    public let passphrase: String?
    public let host: String
    public let port: UInt16
    public let token: String
    public let name: String
    public init(mode: String, v: Int, ssid: String, passphrase: String?, host: String, port: UInt16, token: String, name: String) {
        self.mode = mode; self.v = v; self.ssid = ssid; self.passphrase = passphrase; self.host = host; self.port = port; self.token = token; self.name = name
    }
}

public enum ProtocolErrorCode: String, Codable, Sendable {
    case tokenInvalid = "TOKEN_INVALID"
    case versionUnsupported = "VERSION_UNSUPPORTED"
    case declined = "DECLINED"
    case pathUnsafe = "PATH_UNSAFE"
    case hashMismatch = "HASH_MISMATCH"
    case diskFull = "DISK_FULL"
    case ioError = "IO_ERROR"
    case cancelled = "CANCELLED"
    case timeout = "TIMEOUT"
    case networkIsolationSuspected = "NETWORK_ISOLATION_SUSPECTED"
}

public enum PathSafety {
    public static func normalized(_ path: String) -> String { path.replacingOccurrences(of: "\\", with: "/") }
    public static func isSafe(relativePath: String) -> Bool {
        let p = normalized(relativePath)
        if p.isEmpty || p.hasPrefix("/") { return false }
        if p.contains("\0") { return false }
        let parts = p.split(separator: "/", omittingEmptySubsequences: false).map(String.init)
        if parts.isEmpty { return false }
        return !parts.contains { $0 == ".." || $0.isEmpty }
    }
}

public enum FrameCodec {
    public static func encode(type: FrameType, payload: Data) -> Data {
        var frame = Data()
        var len = UInt32(payload.count + 1).bigEndian
        withUnsafeBytes(of: &len) { frame.append(contentsOf: $0) }
        frame.append(type.rawValue)
        frame.append(payload)
        return frame
    }

    public static func encodeJSON<T: Encodable>(_ value: T, type: FrameType) throws -> Data {
        let payload = try JSONEncoder().encode(value)
        return encode(type: type, payload: payload)
    }

    public static func decodeJSON<T: Decodable>(_ type: T.Type, from payload: Data) throws -> T {
        try JSONDecoder().decode(type, from: payload)
    }
}

public struct ChunkHeader {
    public let fileId: String
    public let chunkIndex: UInt64
    public let endOfFile: Bool
    public let crc32c: UInt32
    public let data: Data

    public init(fileId: String, chunkIndex: UInt64, endOfFile: Bool, crc32c: UInt32, data: Data) {
        self.fileId = fileId; self.chunkIndex = chunkIndex; self.endOfFile = endOfFile; self.crc32c = crc32c; self.data = data
    }

    public func encode() -> Data {
        let idBytes = Data(fileId.utf8)
        var out = Data()
        var idLen = UInt16(idBytes.count).bigEndian
        withUnsafeBytes(of: &idLen) { out.append(contentsOf: $0) }
        out.append(idBytes)
        var idx = chunkIndex.bigEndian
        withUnsafeBytes(of: &idx) { out.append(contentsOf: $0) }
        out.append(endOfFile ? 1 : 0)
        var crc = crc32c.bigEndian
        withUnsafeBytes(of: &crc) { out.append(contentsOf: $0) }
        out.append(data)
        return out
    }

    public static func decode(_ payload: Data) -> ChunkHeader? {
        var offset = 0
        func read<T>(_ type: T.Type) -> T? {
            let size = MemoryLayout<T>.size
            guard payload.count >= offset + size else { return nil }
            let value = payload.subdata(in: offset..<(offset+size)).withUnsafeBytes { $0.load(as: T.self) }
            offset += size
            return value
        }
        guard let idLenBE: UInt16 = read(UInt16.self) else { return nil }
        let idLen = Int(UInt16(bigEndian: idLenBE))
        guard payload.count >= offset + idLen else { return nil }
        let fileId = String(data: payload.subdata(in: offset..<(offset+idLen)), encoding: .utf8) ?? ""
        offset += idLen
        guard let idxBE: UInt64 = read(UInt64.self), let flags: UInt8 = read(UInt8.self), let crcBE: UInt32 = read(UInt32.self) else { return nil }
        let data = payload.subdata(in: offset..<payload.count)
        return ChunkHeader(fileId: fileId, chunkIndex: UInt64(bigEndian: idxBE), endOfFile: (flags & 1) == 1, crc32c: UInt32(bigEndian: crcBE), data: data)
    }
}


public enum CRC32 {
    private static let table: [UInt32] = (0..<256).map { i -> UInt32 in
        var c = UInt32(i)
        for _ in 0..<8 { c = (c & 1) != 0 ? (0xEDB88320 ^ (c >> 1)) : (c >> 1) }
        return c
    }
    public static func checksum(_ data: Data) -> UInt32 {
        var c: UInt32 = 0xFFFFFFFF
        for b in data { c = table[Int((c ^ UInt32(b)) & 0xFF)] ^ (c >> 8) }
        return c ^ 0xFFFFFFFF
    }
}
