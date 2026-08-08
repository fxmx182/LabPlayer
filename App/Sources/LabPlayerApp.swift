import SwiftUI

@main
struct LabPlayerApp: App {
    @StateObject private var bookmarks = BookmarkStore()

    var body: some Scene {
        WindowGroup {
            LibraryView()
                .environmentObject(bookmarks)
                .preferredColorScheme(.dark)
        }
    }
}
