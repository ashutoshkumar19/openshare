import SwiftUI

enum OSTheme {
    static let brand = Color(red: 0.10, green: 0.45, blue: 0.95)
    static let brandAlt = Color(red: 0.42, green: 0.28, blue: 0.96)
    static let teal = Color(red: 0.05, green: 0.68, blue: 0.62)
    static let bg = Color(nsColor: .windowBackgroundColor)
    static let surface = Color(nsColor: .controlBackgroundColor)
    static let surfaceElevated = Color(nsColor: .underPageBackgroundColor)
    static let border = Color.primary.opacity(0.09)
    static let muted = Color.secondary
    static let success = Color(red: 0.12, green: 0.70, blue: 0.42)
    static let warning = Color(red: 0.95, green: 0.62, blue: 0.10)
    static let danger = Color(red: 0.90, green: 0.28, blue: 0.32)

    static func stateColor(_ state: SessionState) -> Color {
        switch state {
        case .idle: return .secondary
        case .discovering, .pairing, .awaitingAccept: return warning
        case .transferring, .verifying: return brand
        case .complete: return success
        case .failed, .cancelled: return danger
        }
    }

    static func stateLabel(_ state: SessionState) -> String {
        switch state {
        case .idle: return "Idle"
        case .discovering: return "Discovering"
        case .pairing: return "Connecting"
        case .awaitingAccept: return "Awaiting Accept"
        case .transferring: return "Transferring"
        case .verifying: return "Verifying"
        case .complete: return "Complete"
        case .failed: return "Failed"
        case .cancelled: return "Cancelled"
        }
    }

    static func formatBytes(_ bytes: Int64) -> String {
        let b = Double(max(bytes, 0))
        let kb = 1024.0, mb = kb * 1024, gb = mb * 1024
        switch b {
        case gb...: return String(format: "%.2f GB", b / gb)
        case mb...: return String(format: "%.1f MB", b / mb)
        case kb...: return String(format: "%.1f KB", b / kb)
        default: return "\(Int64(b)) B"
        }
    }
}

struct SurfaceCard<Content: View>: View {
    var padding: CGFloat = 18
    @ViewBuilder let content: Content
    var body: some View {
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(OSTheme.border, lineWidth: 1))
    }
}

struct SectionHeader: View {
    let title: String
    let subtitle: String?
    let icon: String
    init(_ title: String, subtitle: String? = nil, icon: String) {
        self.title = title; self.subtitle = subtitle; self.icon = icon
    }
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(OSTheme.brand)
                .frame(width: 28, height: 28)
                .background(OSTheme.brand.opacity(0.12), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 15, weight: .semibold))
                if let subtitle { Text(subtitle).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true) }
            }
            Spacer(minLength: 0)
        }
    }
}

struct PrimaryAction: ButtonStyle {
    var color: Color = OSTheme.brand
    var filled: Bool = true
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(filled ? .white : color)
            .padding(.horizontal, 14).padding(.vertical, 9)
            .background(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(filled ? color : color.opacity(0.12))
            )
            .opacity(configuration.isPressed ? 0.82 : 1)
    }
}

struct StatusBadge: View {
    let state: SessionState
    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(OSTheme.stateColor(state)).frame(width: 7, height: 7)
            Text(OSTheme.stateLabel(state)).font(.caption.weight(.semibold))
        }
        .padding(.horizontal, 10).padding(.vertical, 5)
        .background(OSTheme.stateColor(state).opacity(0.12), in: Capsule())
        .foregroundStyle(OSTheme.stateColor(state))
    }
}

struct EmptyState: View {
    let icon: String
    let text: String
    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: icon).foregroundStyle(.secondary)
            Text(text).font(.callout).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.primary.opacity(0.04), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}

struct InfoNote: View {
    let text: String
    var icon: String = "info.circle"
    var tint: Color = OSTheme.brand
    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: icon).foregroundStyle(tint).font(.caption).padding(.top, 1)
            Text(text).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .padding(10)
        .background(tint.opacity(0.08), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}
