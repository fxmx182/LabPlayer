import Foundation
import SwiftUI
import UniformTypeIdentifiers

/// Onde um arquivo mora — num lugar do aparelho ou num servidor SMB.
///
/// Tudo que as telas e as operações sabem de um arquivo passa por aqui, para
/// que copiar do servidor para o iPhone e do iPhone para o servidor seja o
/// mesmo código (o `Loc` do Android).
///
/// A diferença para o Android está no `.local`: lá o app vê o armazenamento
/// inteiro, por caminho absoluto. Aqui o iOS só mostra ao app a própria pasta e
/// as pastas que a pessoa autorizou no seletor do sistema — cada uma é uma
/// **raiz** (`Lugares`), acima da qual não se sobe. O caminho é relativo a ela.
enum Loc: Hashable {
    /// `raiz`: o id do lugar (`Lugares.docs` ou o de uma pasta autorizada);
    /// `caminho` usa `/` e é relativo à raiz, vazio = a própria raiz.
    case local(raiz: String, caminho: String)
    /// `share` vazio = a lista de compartilhamentos do servidor; `caminho`
    /// usa `/` e é relativo ao share.
    case smb(servidor: UUID, share: String, caminho: String)

    var nome: String {
        switch self {
        case .local(_, let c): return c.isEmpty ? "" : (c as NSString).lastPathComponent
        case .smb(_, let s, let c): return c.isEmpty ? s : (c as NSString).lastPathComponent
        }
    }

    var pai: Loc? {
        switch self {
        case .local(let r, let c):
            guard !c.isEmpty else { return nil }
            return .local(raiz: r, caminho: Self.semUltimo(c))
        case .smb(let id, let s, let c):
            if s.isEmpty { return nil }
            if c.isEmpty { return .smb(servidor: id, share: "", caminho: "") }
            return .smb(servidor: id, share: s, caminho: Self.semUltimo(c))
        }
    }

    func filho(_ nome: String) -> Loc {
        switch self {
        case .local(let r, let c):
            return .local(raiz: r, caminho: c.isEmpty ? nome : c + "/" + nome)
        case .smb(let id, let s, let c):
            if s.isEmpty { return .smb(servidor: id, share: nome, caminho: "") }
            return .smb(servidor: id, share: s, caminho: c.isEmpty ? nome : c + "/" + nome)
        }
    }

    var ehSmb: Bool { if case .smb = self { return true } else { return false } }

    /// A lista de compartilhamentos: lá não se cria, cola nem apaga nada.
    var ehRaizDoServidor: Bool {
        if case .smb(_, let s, _) = self { return s.isEmpty }
        return false
    }

    /// Mesmo lugar físico, para mover sem copiar os bytes: a mesma raiz do
    /// aparelho, ou o mesmo share do mesmo servidor.
    func mesmoLugar(_ outro: Loc) -> Bool {
        switch (self, outro) {
        case (.local(let a, _), .local(let b, _)): return a == b
        case (.smb(let a, let s1, _), .smb(let b, let s2, _)): return a == b && s1 == s2 && !s1.isEmpty
        default: return false
        }
    }

    /// `self` é `outro` ou está dentro dele.
    func dentro(de outro: Loc) -> Bool {
        var p: Loc? = self
        while let atual = p {
            if atual == outro { return true }
            p = atual.pai
        }
        return false
    }

    private static func semUltimo(_ c: String) -> String {
        guard let i = c.lastIndex(of: "/") else { return "" }
        return String(c[..<i])
    }
}

struct FileEntry: Identifiable, Hashable {
    var loc: Loc
    var nome: String
    var isDir: Bool
    var tamanho: Int64 = 0
    var modificado: Date? = nil
    var oculto: Bool = false
    /// Só para compartilhamentos SMB: não se pode apagar nem renomear.
    var ehShare: Bool = false
    /// Arquivo do iCloud que ainda não está no aparelho — baixa ao abrir.
    var naNuvem: Bool = false

    var id: Loc { loc }
    var ext: String { isDir ? "" : (nome as NSString).pathExtension.lowercased() }
}

enum Tipo {
    case pasta, imagem, video, audio, pdf, doc, planilha, slides, texto, compactado, codigo, outro
}

/// Tipo, símbolo e cor de cada arquivo — o `Mime` do Android, com os símbolos
/// do SF Symbols no lugar dos ícones do Material.
enum Mime {
    static func tipo(_ e: FileEntry) -> Tipo {
        if e.isDir { return .pasta }
        return tipo(ext: e.ext)
    }

    static func tipo(ext: String) -> Tipo {
        switch ext {
        case "pdf": return .pdf
        case "doc", "docx", "odt", "rtf", "hwp", "pages": return .doc
        case "xls", "xlsx", "ods", "csv", "numbers": return .planilha
        case "ppt", "pptx", "odp", "key": return .slides
        case "zip", "rar", "7z", "tar", "gz", "bz2", "xz": return .compactado
        case "rmvb", "flv", "wmv", "vob", "mkv", "ts", "m2ts", "webm", "avi": return .video
        case "flac", "opus", "ogg", "ape", "dsf": return .audio
        case "kt", "java", "py", "js", "ts", "html", "css", "json", "xml", "sh", "c", "cpp", "yml", "yaml", "swift": return .codigo
        case "txt", "md", "log", "srt", "ass", "vtt": return .texto
        default: break
        }
        guard let t = UTType(filenameExtension: ext) else { return .outro }
        if t.conforms(to: .image) { return .imagem }
        if t.conforms(to: .movie) || t.conforms(to: .video) { return .video }
        if t.conforms(to: .audio) { return .audio }
        if t.conforms(to: .text) { return .texto }
        if t.conforms(to: .archive) { return .compactado }
        return .outro
    }

    static func simbolo(_ e: FileEntry) -> String {
        switch tipo(e) {
        case .pasta: return e.ehShare ? "folder.fill.badge.person.crop" : "folder.fill"
        case .imagem: return "photo.fill"
        case .video: return "film.fill"
        case .audio: return "music.note"
        case .pdf: return "doc.richtext.fill"
        case .doc: return "doc.text.fill"
        case .planilha: return "tablecells.fill"
        case .slides: return "rectangle.on.rectangle.angled.fill"
        case .texto: return "text.alignleft"
        case .compactado: return "doc.zipper"
        case .codigo: return "chevron.left.forwardslash.chevron.right"
        case .outro: return "doc.fill"
        }
    }

    /// A cor de cada tipo, escolhida para o fundo escuro. A pasta é a cor da
    /// marca, que é também a do ícone do app.
    static func cor(_ e: FileEntry) -> Color {
        switch tipo(e) {
        case .pasta: return Lx.ouro
        case .imagem: return Color(hex: 0xC08BFF)
        case .video: return Color(hex: 0xFF6E8A)
        case .audio: return Color(hex: 0x35D0BE)
        case .pdf: return Color(hex: 0xFF6B5E)
        case .doc: return Color(hex: 0x5B9CFF)
        case .planilha: return Color(hex: 0x46C97E)
        case .slides: return Color(hex: 0xFFA24D)
        case .texto: return Color(hex: 0xA8B3C2)
        case .compactado: return Color(hex: 0xD9A15C)
        case .codigo: return Color(hex: 0x8A96FF)
        case .outro: return Color(hex: 0x9EA3AD)
        }
    }

    /// A descrição do tipo para os Detalhes ("Imagem JPEG", "Documento PDF").
    static func descricao(_ nome: String) -> String {
        let ext = (nome as NSString).pathExtension
        if let t = UTType(filenameExtension: ext), let d = t.localizedDescription { return d }
        return ext.isEmpty ? String(localized: "Arquivo") : ext.uppercased()
    }
}
