import SwiftUI
import OpenShareCore

struct ContentView: View {
    @EnvironmentObject var state: AppState
    @State private var showScanner = false
    @State private var sidebarSelection: Panel = .transfer

    enum Panel: String, CaseIterable, Identifiable {
        case transfer = "Transfer"
        case hotspot = "Hotspot & QR"
        case activity = "Activity"
        var id: String { rawValue }
        var icon: String {
            switch self { case .transfer: return "arrow.left.arrow.right.circle"; case .hotspot: return "qrcode.viewfinder"; case .activity: return "waveform.path.ecg" }
        }
    }

    var body: some View {
        NavigationSplitView {
            sidebar
                .navigationSplitViewColumnWidth(min: 210, ideal: 230, max: 260)
        } detail: {
            detail
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(background)
        }
        .navigationSplitViewStyle(.balanced)
        .frame(minWidth: 720, minHeight: 560)
        .sheet(isPresented: $showScanner) {
            QRScannerSheet { scanned in
                state.ingestScannedPayload(scanned)
                showScanner = false
            }
        }
        .onChange(of: state.mode) { m in
            if m == .hotspot { sidebarSelection = .hotspot }
            else if sidebarSelection == .hotspot { sidebarSelection = .transfer }
        }
    }

    private var background: some View {
        ZStack {
            OSTheme.bg
            LinearGradient(colors: [OSTheme.brand.opacity(0.08), .clear, OSTheme.brandAlt.opacity(0.06)], startPoint: .topLeading, endPoint: .bottomTrailing)
        }.ignoresSafeArea()
    }

    private var sidebar: some View {
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 10) {
                    Group {
                        if let img = NSImage(named: NSImage.applicationIconName), img.isValid {
                            Image(nsImage: img).resizable().interpolation(.high)
                        } else {
                            Image(systemName: "square.dashed").resizable()
                        }
                    }
                    .frame(width: 36, height: 36)
                    .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
                    VStack(alignment: .leading, spacing: 1) {
                        Text("OpenShare").font(.system(size: 17, weight: .bold, design: .rounded))
                        Text("Local sharing").font(.caption).foregroundStyle(.secondary)
                    }
                }
                .padding(.horizontal, 14).padding(.top, 14).padding(.bottom, 8)

                List(Panel.allCases, selection: $sidebarSelection) { p in
                    Label(p.rawValue, systemImage: p.icon).tag(p)
                }
                .listStyle(.sidebar)
            }
            Spacer(minLength: 0)
            VStack(alignment: .leading, spacing: 8) {
                Divider()
                HStack(spacing: 8) {
                    Image(systemName: "desktopcomputer").foregroundStyle(.secondary)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(state.localDeviceName).font(.caption.weight(.medium)).lineLimit(1)
                        Text(state.mode.rawValue).font(.caption2).foregroundStyle(.secondary)
                    }
                    Spacer()
                }
                StatusBadge(state: state.sessionState)
            }
            .padding(14)
        }
    }

    @ViewBuilder
    private var detail: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                topBar
                switch sidebarSelection {
                case .transfer: transferPanels
                case .hotspot: hotspotPanels
                case .activity: activityPanels
                }
            }
            .padding(22)
            .frame(maxWidth: 980, alignment: .leading)
            .frame(maxWidth: .infinity, alignment: .topLeading)
        }
    }

    private var topBar: some View {
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .center) { titleBlock; Spacer(); actionButtons }
            VStack(alignment: .leading, spacing: 10) { titleBlock; actionButtons }
        }
    }

    private var titleBlock: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(sidebarSelection.rawValue).font(.system(size: 24, weight: .bold, design: .rounded))
            Text(subtitleForPanel).font(.subheadline).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
        }
    }

    private var subtitleForPanel: String {
        switch sidebarSelection {
        case .transfer: return "Send and receive files or folders over your local Wi‑Fi network."
        case .hotspot: return "Pair devices with a OpenShare-compatible QR payload for hotspot sessions."
        case .activity: return "Connection state, progress, and session details."
        }
    }

    private var actionButtons: some View {
        HStack(spacing: 10) {
            if state.sessionState == .transferring || state.sessionState == .awaitingAccept || state.sessionState == .verifying {
                Button { state.cancelTransfer() } label: { Label("Cancel", systemImage: "xmark") }
                    .buttonStyle(PrimaryAction(color: .secondary, filled: false))
            }
            Button {
                state.sessionState == .idle ? state.start() : state.stop()
            } label: {
                Label(state.sessionState == .idle ? "Start Session" : "Stop Session",
                      systemImage: state.sessionState == .idle ? "play.fill" : "stop.fill")
            }
            .buttonStyle(PrimaryAction(color: state.sessionState == .idle ? OSTheme.brand : OSTheme.danger))
            .keyboardShortcut(.defaultAction)
        }
    }

    private var transferPanels: some View {
        VStack(alignment: .leading, spacing: 16) {
            setupCard
            statusCard
            if state.role == .send { sendCard }
            if let offer = state.incomingOffer { incomingCard(offer) }
            if state.mode == .sameWifi && state.role == .send && state.sessionState != .idle { devicesCard }
            if shouldShowProgress { progressCard }
        }
    }

    private var hotspotPanels: some View {
        VStack(alignment: .leading, spacing: 16) {
            setupCard
            statusCard
            hotspotCard
            if shouldShowProgress { progressCard }
        }
    }

    private var activityPanels: some View {
        VStack(alignment: .leading, spacing: 16) {
            statusCard
            if shouldShowProgress { progressCard } else { SurfaceCard { EmptyState(icon: "chart.bar", text: "No active transfer. Start a session to see progress and activity here.") } }
            setupSummaryCard
        }
    }

    private var setupCard: some View {
        SurfaceCard {
            VStack(alignment: .leading, spacing: 14) {
                SectionHeader("Session Setup", subtitle: "Configure mode and role before starting", icon: "slider.horizontal.3")
                ViewThatFits(in: .horizontal) {
                    HStack(alignment: .top, spacing: 16) { modePicker; rolePicker }
                    VStack(alignment: .leading, spacing: 12) { modePicker; rolePicker }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    private var modePicker: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Mode").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
            Picker("", selection: $state.mode) { ForEach(TransferMode.allCases) { Text($0.rawValue).tag($0) } }
                .pickerStyle(.segmented)
                .labelsHidden()
                .frame(maxWidth: 420, alignment: .leading)
            Text(state.mode == .sameWifi ? "Both devices on the same Wi‑Fi network." : "Pair via hotspot and OpenShare QR payload.")
                .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: 420, alignment: .leading)
    }

    private var rolePicker: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Role").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
            Picker("", selection: $state.role) { ForEach(Role.allCases) { Text($0.rawValue).tag($0) } }
                .pickerStyle(.segmented)
                .labelsHidden()
                .frame(maxWidth: 280, alignment: .leading)
            Text(state.role == .send ? "Select items and connect to a receiver." : "Wait for offers and choose where to save.")
                .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: 280, alignment: .leading)
    }

    private var statusCard: some View {
        SurfaceCard {
            VStack(alignment: .leading, spacing: 12) {
                SectionHeader("Status", icon: "antenna.radiowaves.left.and.right")
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: iconForState(state.sessionState))
                        .font(.title3).foregroundStyle(OSTheme.stateColor(state.sessionState)).frame(width: 24)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(state.status).font(.body).textSelection(.enabled).fixedSize(horizontal: false, vertical: true)
                        Text("Offline • explicit receiver accept • one-to-one").font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 0)
                    StatusBadge(state: state.sessionState)
                }
            }
        }
    }

    private var sendCard: some View {
        SurfaceCard {
            VStack(alignment: .leading, spacing: 12) {
                SectionHeader("Send Items", subtitle: "Files and folders you want to share", icon: "paperplane")
                HStack(spacing: 10) {
                    Button { state.chooseFiles() } label: { Label("Choose Files or Folders", systemImage: "plus.circle") }
                        .buttonStyle(PrimaryAction(color: OSTheme.brand, filled: false))
                    Text("\(state.selectedURLs.count) selected").font(.subheadline).foregroundStyle(.secondary)
                    Spacer(minLength: 0)
                }
                if state.selectedURLs.isEmpty {
                    EmptyState(icon: "doc.on.doc", text: "No items selected. Choose files or folders to enable sending.")
                } else {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 180), spacing: 8)], alignment: .leading, spacing: 8) {
                        ForEach(state.selectedURLs.prefix(8), id: \.self) { url in
                            HStack(spacing: 8) {
                                Image(systemName: url.hasDirectoryPath ? "folder.fill" : "doc.fill").foregroundStyle(OSTheme.brand)
                                Text(url.lastPathComponent).font(.caption).lineLimit(1)
                                Spacer(minLength: 0)
                            }
                            .padding(8).background(Color.primary.opacity(0.04), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                        }
                    }
                    if state.selectedURLs.count > 8 { Text("+ \(state.selectedURLs.count - 8) more").font(.caption).foregroundStyle(.secondary) }
                }
            }
        }
    }

    private func incomingCard(_ offer: IncomingOffer) -> some View {
        SurfaceCard {
            VStack(alignment: .leading, spacing: 12) {
                SectionHeader("Incoming Transfer", subtitle: "Review before accepting", icon: "tray.and.arrow.down")
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: "arrow.down.doc.fill").font(.title2).foregroundStyle(OSTheme.brand)
                    VStack(alignment: .leading, spacing: 4) {
                        Text("From \(offer.from)").font(.headline)
                        Text("\(offer.offer.fileCount) files • \(OSTheme.formatBytes(offer.offer.totalBytes))").font(.subheadline).foregroundStyle(.secondary)
                        Text(offer.offer.rootName).font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 0)
                }
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 10) {
                        Button { state.acceptIncoming() } label: { Label("Accept & Choose Destination", systemImage: "checkmark.circle.fill") }.buttonStyle(PrimaryAction(color: OSTheme.success))
                        Button("Decline") { state.declineIncoming() }.buttonStyle(PrimaryAction(color: OSTheme.danger, filled: false))
                    }
                    VStack(alignment: .leading, spacing: 8) {
                        Button { state.acceptIncoming() } label: { Label("Accept & Choose Destination", systemImage: "checkmark.circle.fill") }.buttonStyle(PrimaryAction(color: OSTheme.success))
                        Button("Decline") { state.declineIncoming() }.buttonStyle(PrimaryAction(color: OSTheme.danger, filled: false))
                    }
                }
            }
        }
    }

    private var devicesCard: some View {
        SurfaceCard {
            VStack(alignment: .leading, spacing: 12) {
                SectionHeader("Nearby Devices", subtitle: "Receivers discovered on this network", icon: "dot.radiowaves.left.and.right")
                if state.peers.isEmpty {
                    EmptyState(icon: "magnifyingglass", text: "Searching for receivers… Make sure the receiver started a session on the same Wi‑Fi and AP isolation is disabled.")
                } else {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 260), spacing: 10)], spacing: 10) {
                        ForEach(state.peers) { peer in
                            HStack(spacing: 10) {
                                Image(systemName: "laptopcomputer").foregroundStyle(OSTheme.brand)
                                    .frame(width: 30, height: 30).background(OSTheme.brand.opacity(0.12), in: RoundedRectangle(cornerRadius: 8))
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(peer.name).font(.subheadline.weight(.semibold)).lineLimit(1)
                                    Text("\(peer.host):\(peer.port)").font(.caption).foregroundStyle(.secondary).lineLimit(1)
                                }
                                Spacer(minLength: 0)
                                Button("Connect") { state.connect(to: peer) }
                                    .buttonStyle(PrimaryAction(color: OSTheme.brand))
                                    .disabled(state.selectedURLs.isEmpty)
                            }
                            .padding(10)
                            .background(Color.primary.opacity(0.04), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                        }
                    }
                    if state.selectedURLs.isEmpty { InfoNote(text: "Select files or folders before connecting.", icon: "exclamationmark.circle") }
                }
            }
        }
    }

    private var hotspotCard: some View {
        SurfaceCard {
            VStack(alignment: .leading, spacing: 12) {
                SectionHeader("Hotspot & QR Pairing", subtitle: "Scan → join Wi‑Fi → connect", icon: "qrcode.viewfinder")
                if state.mode != .hotspot {
                    InfoNote(text: "Hotspot pairing is off. Select Hotspot mode above to scan/connect with an OpenShare QR payload.", icon: "wifi.slash")
                } else if state.role == .receive {
                    InfoNote(text: "macOS hotspot hosting is limited in this app. Recommended: use an Android device as hotspot host/receiver (it shows the OpenShare QR + Wi‑Fi name/password), or use Same Wi‑Fi mode on both devices.", icon: "info.circle")
                } else {
                    if state.selectedURLs.isEmpty {
                        InfoNote(text: "Select files or folders in Transfer before starting the transfer.", icon: "exclamationmark.circle")
                    }
                    HStack(spacing: 10) {
                        Button { showScanner = true } label: { Label("Scan Host QR (Camera)", systemImage: "camera.viewfinder") }
                            .buttonStyle(PrimaryAction(color: OSTheme.brand, filled: state.scannedHotspot == nil))
                        if state.scannedHotspot != nil {
                            Button { state.scannedHotspot = nil; state.hotspotPayloadText = ""; state.status = "Scan cleared. Scan the host OpenShare QR again when ready." } label: { Label("Clear", systemImage: "xmark.circle") }
                                .buttonStyle(PrimaryAction(color: .secondary, filled: false))
                        }
                    }
                    if let p = state.scannedHotspot {
                        VStack(alignment: .leading, spacing: 10) {
                            InfoNote(text: "QR scanned. Join the host hotspot Wi‑Fi in macOS System Settings, then click Connect & Start Transfer.", icon: "wifi")
                            VStack(alignment: .leading, spacing: 8) {
                                credentialRow(label: "Wi‑Fi name (SSID)", value: p.ssid)
                                credentialRow(label: "Password", value: p.passphrase ?? "(not available)")
                                HStack(spacing: 10) {
                                    Button { copyToPasteboard(p.ssid) } label: { Label("Copy SSID", systemImage: "doc.on.doc") }
                                        .buttonStyle(PrimaryAction(color: OSTheme.brand, filled: false))
                                    if let pass = p.passphrase, !pass.isEmpty {
                                        Button { copyToPasteboard(pass) } label: { Label("Copy Password", systemImage: "key.fill") }
                                            .buttonStyle(PrimaryAction(color: OSTheme.brand, filled: false))
                                    }
                                    Button { openWifiSettings() } label: { Label("Open Wi‑Fi Settings", systemImage: "gearshape") }
                                        .buttonStyle(PrimaryAction(color: .secondary, filled: false))
                                }
                                Text("Host: \(p.name) • \(p.host):\(p.port)").font(.caption).foregroundStyle(.secondary)
                            }
                            .padding(12)
                            .background(Color.primary.opacity(0.04), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                            .overlay(RoundedRectangle(cornerRadius: 12).stroke(OSTheme.border, lineWidth: 1))
                            HStack(spacing: 10) {
                                Button { state.connectScannedHotspot() } label: { Label("Connect & Start Transfer", systemImage: "link.circle.fill") }
                                    .buttonStyle(PrimaryAction(color: OSTheme.brand))
                                    .disabled(state.role == .send && state.selectedURLs.isEmpty)
                                if state.role == .send && state.selectedURLs.isEmpty {
                                    Text("Select files/folders first.").font(.caption).foregroundStyle(.secondary)
                                }
                            }
                            Text("Scanning reads the OpenShare session (host/port/token). Join the hotspot Wi‑Fi first, then connect.").font(.caption).foregroundStyle(.secondary)
                        }
                    } else if state.mode == .hotspot {
                        InfoNote(text: "1) Select files/folders. 2) Scan the OpenShare QR shown on the Android host. 3) Copy password & join its hotspot Wi‑Fi in System Settings. 4) Click Connect & Start Transfer.", icon: "info.circle")
                    }
                }
                if state.mode == .hotspot {
                    DisclosureGroup("Advanced: payload details") {
                        VStack(alignment: .leading, spacing: 6) {
                            if !state.hotspotPayloadText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && state.hotspotPayloadText.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("{") {
                                HStack(alignment: .top, spacing: 14) { QRCodeImageView(text: state.hotspotPayloadText); payloadPreview }
                            }
                            Text("Payload (JSON)").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                            TextEditor(text: $state.hotspotPayloadText)
                                .font(.system(size: 12, design: .monospaced))
                                .scrollContentBackground(.hidden)
                                .padding(8)
                                .frame(minHeight: 80, idealHeight: 90, maxHeight: 140)
                                .background(Color.primary.opacity(0.04), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                                .overlay(RoundedRectangle(cornerRadius: 10).stroke(OSTheme.border, lineWidth: 1))
                            Button { state.connectHotspotFromPayload() } label: { Label("Parse & Connect from Payload", systemImage: "link") }
                                .buttonStyle(PrimaryAction(color: OSTheme.brand, filled: false))
                                .disabled(state.hotspotPayloadText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || (state.role == .send && state.selectedURLs.isEmpty))
                        }.padding(.top, 6)
                    }
                }
            }
        }
    }

    private func credentialRow(label: String, value: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text(label + ":").font(.caption.weight(.semibold)).foregroundStyle(.secondary).frame(width: 130, alignment: .leading)
            Text(value).font(.system(.body, design: .monospaced)).textSelection(.enabled)
            Spacer(minLength: 0)
        }
    }

    private func copyToPasteboard(_ text: String) {
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.setString(text, forType: .string)
        state.status = "Copied to clipboard."
    }

    private func openWifiSettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.wifi-settings-extension") {
            NSWorkspace.shared.open(url)
        } else if let url = URL(string: "x-apple.systempreferences:com.apple.preference.network") {
            NSWorkspace.shared.open(url)
        }
    }

    private var payloadPreview: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Payload Preview").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
            ScrollView { Text(state.hotspotPayloadText).font(.system(size: 10, design: .monospaced)).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading) }
                .frame(minHeight: 120, maxHeight: 170)
                .padding(8)
                .background(Color.primary.opacity(0.04), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var progressCard: some View {
        SurfaceCard {
            let total = max(state.progress.totalBytes, 1)
            let frac = min(max(Double(state.progress.bytesSent) / Double(total), 0), 1)
            VStack(alignment: .leading, spacing: 12) {
                SectionHeader("Transfer Progress", icon: "chart.bar.fill")
                HStack {
                    Text(state.progress.fileName.isEmpty ? (state.sessionState == .verifying ? "Verifying files…" : "Transferring…") : state.progress.fileName)
                        .font(.subheadline.weight(.medium)).lineLimit(2).fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 8)
                    Text("\(state.progress.filesDone)/\(state.progress.filesTotal) files").font(.caption).foregroundStyle(.secondary)
                }
                ProgressView(value: frac).tint(OSTheme.brand)
                ViewThatFits(in: .horizontal) {
                    HStack { Text("\(OSTheme.formatBytes(state.progress.bytesSent)) / \(OSTheme.formatBytes(state.progress.totalBytes))"); Spacer(); Text("\(Int(frac * 100))%").fontWeight(.semibold).foregroundStyle(OSTheme.brand) }
                    VStack(alignment: .leading, spacing: 2) { Text("\(OSTheme.formatBytes(state.progress.bytesSent)) / \(OSTheme.formatBytes(state.progress.totalBytes))"); Text("\(Int(frac * 100))%").fontWeight(.semibold).foregroundStyle(OSTheme.brand) }
                }.font(.caption).foregroundStyle(.secondary)
                HStack(spacing: 12) {
                    if state.sessionState == .complete, let t = state.totalDuration {
                        Label("Total time: \(state.formatDuration(t))", systemImage: "clock.badge.checkmark")
                    } else if state.transferStart != nil {
                        Label("Elapsed: \(state.formatDuration(state.elapsed))", systemImage: "clock")
                    }
                    Spacer()
                }.font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    private var setupSummaryCard: some View {
        SurfaceCard {
            VStack(alignment: .leading, spacing: 10) {
                SectionHeader("Session Details", icon: "info.circle")
                keyValue("Mode", state.mode.rawValue)
                keyValue("Role", state.role.rawValue)
                keyValue("Device", state.localDeviceName)
                keyValue("State", OSTheme.stateLabel(state.sessionState))
            }
        }
    }

    private func keyValue(_ k: String, _ v: String) -> some View {
        HStack { Text(k).foregroundStyle(.secondary); Spacer(); Text(v).fontWeight(.medium) }.font(.callout)
    }

    private var shouldShowProgress: Bool {
        state.progress.totalBytes > 0 || state.sessionState == .transferring || state.sessionState == .verifying
    }

    private func iconForState(_ state: SessionState) -> String {
        switch state {
        case .idle: return "pause.circle.fill"
        case .discovering: return "dot.radiowaves.left.and.right"
        case .pairing: return "link.circle.fill"
        case .awaitingAccept: return "hourglass"
        case .transferring: return "arrow.left.arrow.right.circle.fill"
        case .verifying: return "checkmark.shield.fill"
        case .complete: return "checkmark.circle.fill"
        case .failed: return "exclamationmark.triangle.fill"
        case .cancelled: return "xmark.circle.fill"
        }
    }
}
