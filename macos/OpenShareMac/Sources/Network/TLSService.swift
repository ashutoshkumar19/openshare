import Foundation
import Network
import OpenShareCore

final class FramedConnection {
    let connection: NWConnection
    private let queue = DispatchQueue(label: "openshare.connection")
    var onFrame: ((FrameType, Data) -> Void)?
    var onClose: ((Error?) -> Void)?

    init(connection: NWConnection) { self.connection = connection }

    func start() {
        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .failed(let err): self?.onClose?(err)
            case .cancelled: self?.onClose?(nil)
            default: break
            }
        }
        connection.start(queue: queue)
        receiveLoop()
    }

    func send(type: FrameType, payload: Data) {
        let frame = FrameCodec.encode(type: type, payload: payload)
        connection.send(content: frame, completion: .contentProcessed { _ in })
    }

    func sendJSON<T: Encodable>(_ value: T, type: FrameType) {
        guard let data = try? JSONEncoder().encode(value) else { return }
        send(type: type, payload: data)
    }

    func close() { connection.cancel() }

    private func receiveLoop() {
        connection.receive(minimumIncompleteLength: 4, maximumLength: 4) { [weak self] data, _, isComplete, err in
            guard let self else { return }
            if isComplete { self.onClose?(nil); return }
            guard err == nil, let data, data.count == 4 else { self.onClose?(err); return }
            let len = data.withUnsafeBytes { $0.load(as: UInt32.self).bigEndian }
            guard len >= 1 && len <= UInt32(ProtocolInfo.maxFramePayload + 1) else { self.close(); return }
            self.connection.receive(minimumIncompleteLength: Int(len), maximumLength: Int(len)) { body, _, isComplete2, err2 in
                if isComplete2 { self.onClose?(nil); return }
                guard err2 == nil, let body, body.count == Int(len) else { self.onClose?(err2); return }
                let rawType = body[body.startIndex]
                let payload = Data(body.dropFirst())
                guard let type = FrameType(rawValue: rawType) else { self.receiveLoop(); return }
                self.onFrame?(type, payload)
                self.receiveLoop()
            }
        }
    }
}

final class TLSService {
    private var listener: NWListener?
    var onAccepted: ((NWConnection) -> Void)?
    private(set) var port: UInt16?

    func listen(port: UInt16) throws {
        let tcp = NWProtocolTCP.Options()
        tcp.enableKeepalive = true
        tcp.keepaliveIdle = 10
        tcp.keepaliveInterval = 5
        tcp.keepaliveCount = 3
        tcp.noDelay = true
        let params = NWParameters(tls: nil, tcp: tcp)
        params.allowLocalEndpointReuse = true
        params.includePeerToPeer = true
        let l = try NWListener(using: params, on: NWEndpoint.Port(rawValue: port)!)
        self.listener = l
        l.stateUpdateHandler = { [weak self] state in
            if case .ready = state { self?.port = l.port?.rawValue ?? port }
        }
        l.newConnectionHandler = { [weak self] conn in self?.onAccepted?(conn) }
        l.start(queue: .global(qos: .userInitiated))
    }

    func connect(host: String, port: UInt16) -> NWConnection {
        let tcp = NWProtocolTCP.Options()
        tcp.enableKeepalive = true
        tcp.keepaliveIdle = 10
        tcp.keepaliveInterval = 5
        tcp.keepaliveCount = 3
        tcp.noDelay = true
        let ep = NWEndpoint.hostPort(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: port)!)
        let params = NWParameters(tls: nil, tcp: tcp)
        params.includePeerToPeer = true
        return NWConnection(to: ep, using: params)
    }

    func stop() { listener?.cancel(); listener = nil }
}
