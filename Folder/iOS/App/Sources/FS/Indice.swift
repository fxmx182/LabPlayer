import Foundation

enum Categoria: String, CaseIterable, Identifiable, Hashable {
    case imagens, videos, audio, documentos, downloads, compactados
    var id: String { rawValue }

    var titulo: String {
        switch self {
        case .imagens: return String(localized: "Imagens")
        case .videos: return String(localized: "Vídeos")
        case .audio: return String(localized: "Áudio")
        case .documentos: return String(localized: "Documentos")
        case .downloads: return String(localized: "Downloads")
        case .compactados: return String(localized: "Compactados")
        }
    }

    var simbolo: String {
        switch self {
        case .imagens: return "photo.fill"
        case .videos: return "play.rectangle.fill"
        case .audio: return "music.note"
        case .documentos: return "doc.text.fill"
        case .downloads: return "arrow.down.circle.fill"
        case .compactados: return "doc.zipper"
        }
    }

    var cor: UInt32 {
        switch self {
        case .imagens: return 0xC08BFF
        case .videos: return 0xFF6E8A
        case .audio: return 0x35D0BE
        case .documentos: return 0x5B9CFF
        case .downloads: return 0xFBC501
        case .compactados: return 0xD9A15C
        }
    }

    func contem(_ e: FileEntry) -> Bool {
        guard !e.isDir else { return false }
        switch self {
        case .imagens: return Mime.tipo(e) == .imagem
        case .videos: return Mime.tipo(e) == .video
        case .audio: return Mime.tipo(e) == .audio
        case .documentos: return [.pdf, .doc, .planilha, .slides].contains(Mime.tipo(e)) || ["txt", "epub", "md"].contains(e.ext)
        case .compactados: return Mime.tipo(e) == .compactado
        case .downloads: return false
        }
    }
}

/// As categorias, os recentes e a pesquisa no aparelho.
///
/// No Android tudo isso sai do MediaStore, o índice que o sistema mantém de
/// todo arquivo do aparelho. O iOS não tem índice que um app possa consultar
/// — o Spotlight dos arquivos é do app Arquivos —, e de todo modo o app só
/// enxerga os lugares autorizados. Então o índice é nosso: uma varredura dos
/// lugares, refeita quando um lugar entra ou sai, quando uma operação termina
/// ou quando a pessoa puxa a tela para atualizar.
actor Indice {
    static let shared = Indice()

    private var arquivos: [FileEntry] = []
    private var pastas: [FileEntry] = []
    private var pronto = false
    private var varrendo: Task<Void, Never>?
    private var geracao = 0

    /// Teto da varredura: num iCloud de 200 mil arquivos o índice não pode
    /// segurar o app por minutos. Fundo de 12 níveis, como a busca do Android.
    private let maxItens = 30_000
    private let maxNivel = 12

    func invalidar() {
        geracao += 1
        pronto = false
        varrendo?.cancel()
        varrendo = nil
    }

    private func garantir() async {
        if pronto { return }
        if let varrendo { await varrendo.value; if pronto { return } }
        let g = geracao
        let t = Task { await self.varrer(g) }
        varrendo = t
        await t.value
    }

    private func varrer(_ g: Int) async {
        let raizes = [Lugares.docs] + (await MainActor.run { Lugares.shared.autorizados.map(\.id) })
        var achados: [FileEntry] = [], dirs: [FileEntry] = []
        var fila: [(Loc, Int)] = raizes.map { (.local(raiz: $0, caminho: ""), 0) }
        while !fila.isEmpty, achados.count + dirs.count < maxItens {
            if Task.isCancelled { return }
            let (dir, nivel) = fila.removeFirst()
            guard let filhos = try? await LocalFs.listar(dir) else { continue }
            for f in filhos where !f.oculto {
                if f.isDir {
                    dirs.append(f)
                    if nivel < maxNivel { fila.append((f.loc, nivel + 1)) }
                } else {
                    achados.append(f)
                }
            }
        }
        guard g == geracao else { return }
        arquivos = achados
        pastas = dirs
        pronto = true
        varrendo = nil
    }

    func categoria(_ c: Categoria) async -> [FileEntry] {
        await garantir()
        return arquivos.filter { c.contem($0) }.sorted { ($0.modificado ?? .distantPast) > ($1.modificado ?? .distantPast) }
    }

    func recentes(_ limite: Int = 30) async -> [FileEntry] {
        await garantir()
        return Array(arquivos.sorted { ($0.modificado ?? .distantPast) > ($1.modificado ?? .distantPast) }.prefix(limite))
    }

    func pesquisar(_ termo: String, limite: Int = 500) async -> [FileEntry] {
        await garantir()
        let t = termo.trimmingCharacters(in: .whitespaces)
        guard !t.isEmpty else { return [] }
        let bate = (pastas + arquivos).filter { $0.nome.localizedCaseInsensitiveContains(t) }
        return Array(bate.sorted { ($0.modificado ?? .distantPast) > ($1.modificado ?? .distantPast) }.prefix(limite))
    }
}
