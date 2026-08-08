import Foundation

/// De onde um vídeo vem.
///
/// Essa é a abstração central do projeto. O motor de playback nunca sabe se os
/// bytes vieram de um pendrive USB, de um share SMB do homelab ou de uma URL —
/// ele só pede blocos. Quando o motor FFmpeg entrar, cada caso vira um
/// `AVIOContext` com callbacks de read/seek, e nada acima disso muda.
enum MediaOrigin: Equatable, Hashable {
    /// Arquivo no sandbox do app, no iCloud, ou num volume externo (USB)
    /// alcançado via document picker. `bookmark` guarda o acesso com escopo
    /// de segurança para sobreviver a reinícios do app.
    case file(url: URL, bookmark: Data?)

    /// Compartilhamento SMB do homelab.
    case smb(share: SMBShareRef, path: String)

    /// HTTP(S) direto.
    case remote(url: URL)
}

struct SMBShareRef: Equatable, Hashable, Codable {
    var host: String
    var share: String
    var username: String
    /// Só o identificador; a senha vive no Keychain, nunca aqui.
    var credentialID: String?
}

/// Um item reproduzível, já resolvido para exibição.
struct MediaItem: Identifiable, Hashable {
    let id: UUID
    var title: String
    var origin: MediaOrigin
    var fileSize: Int64?
    var modifiedAt: Date?

    init(id: UUID = UUID(), title: String, origin: MediaOrigin,
         fileSize: Int64? = nil, modifiedAt: Date? = nil) {
        self.id = id
        self.title = title
        self.origin = origin
        self.fileSize = fileSize
        self.modifiedAt = modifiedAt
    }

    /// Extensões que consideramos vídeo ao varrer uma pasta.
    static let videoExtensions: Set<String> = [
        "mp4", "m4v", "mov", "mkv", "avi", "wmv", "flv", "webm", "ts", "m2ts",
        "mts", "mpg", "mpeg", "vob", "3gp", "ogv", "rmvb", "asf", "divx", "f4v"
    ]

    static func isVideo(_ url: URL) -> Bool {
        videoExtensions.contains(url.pathExtension.lowercased())
    }
}

extension MediaOrigin {
    /// Nome curto para exibir na lista.
    var displayName: String {
        switch self {
        case .file(let url, _):  return url.lastPathComponent
        case .smb(_, let path):  return (path as NSString).lastPathComponent
        case .remote(let url):   return url.lastPathComponent
        }
    }

    /// Identidade estável para retomar de onde parou. Precisa sobreviver a
    /// remontagens do pendrive (o caminho do sandbox muda), por isso usamos o
    /// nome do arquivo em vez da URL completa nos casos de arquivo.
    var resumeKey: String {
        switch self {
        case .file(let url, _):
            return "file:\(url.lastPathComponent)"
        case .smb(let share, let path):
            return "smb:\(share.host)/\(share.share)\(path)"
        case .remote(let url):
            return "url:\(url.absoluteString)"
        }
    }
}
