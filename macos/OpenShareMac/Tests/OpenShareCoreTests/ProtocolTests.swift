import XCTest
@testable import OpenShareCore

final class ProtocolTests: XCTestCase {
    func testPathSafety() {
        XCTAssertTrue(PathSafety.isSafe(relativePath: "a/b.txt"))
        XCTAssertFalse(PathSafety.isSafe(relativePath: "../a.txt"))
        XCTAssertFalse(PathSafety.isSafe(relativePath: "/abs/a.txt"))
        XCTAssertFalse(PathSafety.isSafe(relativePath: "a//b.txt"))
        XCTAssertFalse(PathSafety.isSafe(relativePath: ""))
    }

    func testChunkRoundTrip() {
        let c = ChunkHeader(fileId: "f1", chunkIndex: 7, endOfFile: true, crc32c: 0x1234, data: Data([1,2,3,4]))
        let enc = c.encode()
        let dec = ChunkHeader.decode(enc)
        XCTAssertEqual(dec?.fileId, "f1")
        XCTAssertEqual(dec?.chunkIndex, 7)
        XCTAssertEqual(dec?.endOfFile, true)
        XCTAssertEqual(dec?.data, Data([1,2,3,4]))
    }

    func testFrameEncodeDecodeJSON() throws {
        let hello = Hello(v: 1, deviceId: "d", deviceName: "n", platform: "macos", token: "t", caps: ["sha256"])
        let frame = try FrameCodec.encodeJSON(hello, type: .hello)
        XCTAssertGreaterThan(frame.count, 5)
        XCTAssertEqual(frame[4], FrameType.hello.rawValue)
        let payload = frame.dropFirst(5)
        let decoded = try FrameCodec.decodeJSON(Hello.self, from: Data(payload))
        XCTAssertEqual(decoded.token, "t")
    }
}
