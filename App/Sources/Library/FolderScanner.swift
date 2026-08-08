import Foundation

/// Lista o conteúdo de uma pasta autorizada, separando subpastas de vídeos.
enum FolderScanner {

    struct Listing {
        var directories: [URL] = []
        var videos: [MediaItem] = []
    }

    static func scan(_ directory: URL) -> Listing {
        var listing = Listing()

        let keys: [URLResourceKey] = [.isDirectoryKey, .fileSizeKey, .contentModificationDateKey]
        guard let entries = try? FileManager.default.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: keys,
            options: [.skipsHiddenFiles]
        ) else { return listing }

        for url in entries {
            let values = try? url.resourceValues(forKeys: Set(keys))

            if values?.isDirectory == true {
                listing.directories.append(url)
            } else if MediaItem.isVideo(url) {
                listing.videos.append(MediaItem(
                    title: url.lastPathComponent,
                    origin: .file(url: url, bookmark: nil),
                    fileSize: values?.fileSize.map(Int64.init),
                    modifiedAt: values?.contentModificationDate
                ))
            }
        }

        // Ordem natural: "Ep 2" antes de "Ep 10".
        listing.directories.sort { $0.lastPathComponent.localizedStandardCompare($1.lastPathComponent) == .orderedAscending }
        listing.videos.sort { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
        return listing
    }

    static func humanSize(_ bytes: Int64?) -> String? {
        guard let bytes else { return nil }
        let f = ByteCountFormatter()
        f.countStyle = .file
        return f.string(fromByteCount: bytes)
    }
}
