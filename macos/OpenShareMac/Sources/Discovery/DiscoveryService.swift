import Foundation
import OpenShareCore

struct PeerDevice: Identifiable, Hashable {
    let id: String
    let name: String
    let host: String
    let port: UInt16
}

final class DiscoveryService: NSObject, NetServiceDelegate, NetServiceBrowserDelegate {
    private var service: NetService?
    private var browser: NetServiceBrowser?
    private var resolving: [NetService] = []
    private var onFound: (([PeerDevice]) -> Void)?
    private var found: [String: PeerDevice] = [:]
    private var txt: [String: Data] = [:]

    func startAdvertising(name: String, port: Int) {
        stop()
        let svc = NetService(domain: ProtocolInfo.serviceDomain, type: ProtocolInfo.serviceType, name: name, port: Int32(port))
        svc.includesPeerToPeer = true
        let record: [String: String] = ["v": "1", "name": name, "port": "\(port)", "caps": "files,folders,sha256"]
        svc.setTXTRecord(NetService.data(fromTXTRecord: record.mapValues { Data($0.utf8) }))
        svc.delegate = self
        svc.publish(options: [])
        service = svc
    }

    func startBrowsing(onFound: @escaping ([PeerDevice]) -> Void) {
        stop()
        self.onFound = onFound
        let b = NetServiceBrowser()
        b.includesPeerToPeer = true
        b.delegate = self
        b.searchForServices(ofType: ProtocolInfo.serviceType, inDomain: ProtocolInfo.serviceDomain)
        browser = b
    }

    func stop() {
        service?.stop(); service = nil
        browser?.stop(); browser = nil
        resolving.removeAll(); found.removeAll(); onFound = nil
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        service.delegate = self
        resolving.append(service)
        service.resolve(withTimeout: 5)
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didRemove service: NetService, moreComing: Bool) {
        found.removeValue(forKey: service.name)
        onFound?(Array(found.values))
    }

    func netServiceDidResolveAddress(_ sender: NetService) {
        let host = resolveHost(sender) ?? sender.hostName ?? ""
        guard !host.isEmpty else { return }
        let port = UInt16(sender.port)
        let peer = PeerDevice(id: sender.name, name: sender.name, host: host, port: port)
        found[sender.name] = peer
        onFound?(Array(found.values).sorted { $0.name < $1.name })
    }

    private func resolveHost(_ service: NetService) -> String? {
        guard let addresses = service.addresses else { return nil }
        for data in addresses {
            var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            data.withUnsafeBytes { ptr in
                guard let sa = ptr.baseAddress?.assumingMemoryBound(to: sockaddr.self) else { return }
                if sa.pointee.sa_family == sa_family_t(AF_INET) || sa.pointee.sa_family == sa_family_t(AF_INET6) {
                    getnameinfo(sa, socklen_t(data.count), &hostname, socklen_t(hostname.count), nil, 0, NI_NUMERICHOST)
                }
            }
            let host = String(cString: hostname)
            if !host.isEmpty && !host.contains(":") { return host } // prefer IPv4 for simplicity
        }
        // fallback IPv6 if any
        for data in addresses {
            var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            data.withUnsafeBytes { ptr in
                guard let sa = ptr.baseAddress?.assumingMemoryBound(to: sockaddr.self) else { return }
                getnameinfo(sa, socklen_t(data.count), &hostname, socklen_t(hostname.count), nil, 0, NI_NUMERICHOST)
            }
            let host = String(cString: hostname)
            if !host.isEmpty { return host }
        }
        return nil
    }
}
