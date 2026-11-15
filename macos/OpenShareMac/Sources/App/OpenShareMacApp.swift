import SwiftUI

@main
struct OpenShareMacApp: App {
    @StateObject private var appState = AppState()

    var body: some Scene {
        WindowGroup("OpenShare") {
            ContentView()
                .environmentObject(appState)
                .frame(minWidth: 760, minHeight: 520)
        }
    }
}
