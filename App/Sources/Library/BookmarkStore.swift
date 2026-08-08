import Foundation

/// Guarda as pastas que o usuário autorizou (pendrive USB, iCloud, etc).
///
/// O iOS só deixa o app ler fora do próprio sandbox por meio de um bookmark
/// com escopo de segurança. O caminho de um pendrive muda a cada remontagem,
/// então persistir a URL não adianta — o bookmark é o que sobrevive.
struct SavedFolder: Codable, Identifiable, Hashable {
    var id: UUID = UUID()
    var name: String
    var bookmark: Data
    var addedAt: Date = Date()
}

@MainActor
final class BookmarkStore: ObservableObject {

    @Published private(set) var folders: [SavedFolder] = []

    private let defaultsKey = "labplayer.savedFolders"

    init() { load() }

    private func load() {
        guard let data = UserDefaults.standard.data(forKey: defaultsKey),
              let decoded = try? JSONDecoder().decode([SavedFolder].self, from: data) else { return }
        folders = decoded
    }

    private func persist() {
        guard let data = try? JSONEncoder().encode(folders) else { return }
        UserDefaults.standard.set(data, forKey: defaultsKey)
    }

    func add(url: URL) throws {
        // O escopo precisa estar ativo no momento de criar o bookmark.
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }

        let bookmark = try url.bookmarkData(options: [],
                                            includingResourceValuesForKeys: nil,
                                            relativeTo: nil)
        let folder = SavedFolder(name: url.lastPathComponent, bookmark: bookmark)

        // Reabrir a mesma pasta substitui em vez de duplicar.
        folders.removeAll { $0.name == folder.name }
        folders.insert(folder, at: 0)
        persist()
    }

    func remove(_ folder: SavedFolder) {
        folders.removeAll { $0.id == folder.id }
        persist()
    }

    /// Resolve um bookmark de volta para uma URL utilizável. O chamador é
    /// responsável por `stopAccessingSecurityScopedResource()`.
    func resolve(_ folder: SavedFolder) -> URL? {
        var stale = false
        guard let url = try? URL(resolvingBookmarkData: folder.bookmark,
                                 options: [],
                                 relativeTo: nil,
                                 bookmarkDataIsStale: &stale) else { return nil }
        guard url.startAccessingSecurityScopedResource() else { return nil }
        return url
    }
}
