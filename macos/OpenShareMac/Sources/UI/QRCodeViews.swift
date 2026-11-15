import SwiftUI
import CoreImage
import CoreImage.CIFilterBuiltins
import AVFoundation
import Vision

struct QRCodeImageView: View {
    let text: String
    var body: some View {
        if let img = generate(text: text) {
            Image(nsImage: img)
                .interpolation(.none)
                .resizable()
                .scaledToFit()
                .frame(width: 160, height: 160)
                .padding(10)
                .background(Color.white)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 14).stroke(OSTheme.border, lineWidth: 1))
                .shadow(color: .black.opacity(0.06), radius: 8, y: 3)
        } else {
            Text("Unable to render QR").font(.caption).foregroundStyle(.secondary)
        }
    }
    private func generate(text: String) -> NSImage? {
        let ctx = CIContext()
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let out = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)),
              let cg = ctx.createCGImage(out, from: out.extent) else { return nil }
        return NSImage(cgImage: cg, size: NSSize(width: out.extent.width, height: out.extent.height))
    }
}

final class QRScannerModel: NSObject, ObservableObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    @Published var scanned: String? = nil
    @Published var error: String? = nil
    let session = AVCaptureSession()
    private var configured = false
    private let videoQueue = DispatchQueue(label: "openshare.qr.video")
    private var handling = false

    func configure() {
        guard !configured else { return }
        configured = true
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: setup()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] ok in
                DispatchQueue.main.async { if ok { self?.setup() } else { self?.error = "Camera access denied. Enable in System Settings › Privacy & Security › Camera." } }
            }
        default:
            error = "Camera access denied. Enable in System Settings › Privacy & Security › Camera."
        }
    }

    private func setup() {
        session.beginConfiguration()
        session.sessionPreset = .high
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else { error = "No camera available."; session.commitConfiguration(); return }
        session.addInput(input)
        let output = AVCaptureVideoDataOutput()
        output.alwaysDiscardsLateVideoFrames = true
        output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        guard session.canAddOutput(output) else { error = "Cannot scan."; session.commitConfiguration(); return }
        session.addOutput(output)
        output.setSampleBufferDelegate(self, queue: videoQueue)
        session.commitConfiguration()
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in self?.session.startRunning() }
    }

    func stop() { if session.isRunning { session.stopRunning() } }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard !handling else { return }
        guard scanned == nil, let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let request = VNDetectBarcodesRequest { [weak self] req, _ in
            guard let self = self, !self.handling else { return }
            guard let results = req.results as? [VNBarcodeObservation],
                  let payload = results.first(where: { $0.symbology == .qr })?.payloadStringValue ?? results.first?.payloadStringValue,
                  !payload.isEmpty else { return }
            self.handling = true
            DispatchQueue.main.async {
                if self.scanned == nil {
                    self.scanned = payload
                    self.stop()
                }
            }
        }
        request.symbologies = [.qr]
        let handler = VNImageRequestHandler(cvPixelBuffer: pixelBuffer, orientation: .up, options: [:])
        try? handler.perform([request])
    }
}

struct CameraPreviewView: NSViewRepresentable {
    let session: AVCaptureSession
    func makeNSView(context: Context) -> NSView {
        let v = NSView(frame: .zero)
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        v.layer = layer
        v.wantsLayer = true
        return v
    }
    func updateNSView(_ nsView: NSView, context: Context) {
        (nsView.layer as? AVCaptureVideoPreviewLayer)?.frame = nsView.bounds
        (nsView.layer as? AVCaptureVideoPreviewLayer)?.session = session
    }
}

struct QRScannerSheet: View {
    @StateObject private var model = QRScannerModel()
    let onResult: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 4) {
                Text("Scan QR Code").font(.system(size: 20, weight: .semibold, design: .rounded))
                Text("Point your camera at the OpenShare hotspot payload QR.").font(.callout).foregroundStyle(.secondary)
            }
            ZStack {
                CameraPreviewView(session: model.session)
                    .frame(maxWidth: .infinity).frame(height: 300)
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Color.white.opacity(0.25), lineWidth: 1)
                RoundedRectangle(cornerRadius: 14).stroke(OSTheme.brand, lineWidth: 2).frame(width: 190, height: 190).opacity(0.9)
                if let e = model.error {
                    VStack(spacing: 8) { Image(systemName: "camera.fill").foregroundStyle(.secondary); Text(e).font(.callout).multilineTextAlignment(.center) }
                        .padding(16).background(.ultraThinMaterial).clipShape(RoundedRectangle(cornerRadius: 12)).padding()
                }
            }
            HStack {
                Button("Cancel") { model.stop(); dismiss() }.buttonStyle(PrimaryAction(color: .secondary, filled: false))
                Spacer()
                if model.scanned != nil {
                    Label("QR detected", systemImage: "checkmark.circle.fill").foregroundStyle(OSTheme.success).font(.caption.weight(.medium))
                }
            }
        }
        .padding(20)
        .frame(minWidth: 380, idealWidth: 480, maxWidth: 520)
        .onAppear { model.configure() }
        .onDisappear { model.stop() }
        .onChange(of: model.scanned) { val in if let val { onResult(val); dismiss() } }
    }
}
