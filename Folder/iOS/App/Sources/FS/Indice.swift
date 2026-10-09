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

/// O andamento da varredura, para o início mostrar "Varrendo subpastas…".
/// Muda a cada poucas centenas de itens: só o aviso o observa.
@MainActor
final class ProgressoDoIndice: ObservableObject {
    static let shared = ProgressoDoIndice()
    @Published var varrendo = false
    @Published var itens = 0
    private init() {}
}

/// Muda quando uma varredura termina — as categorias e a pesquisa recarregam.
/// À parte do progresso para as listas não redesenharem no meio da varredura.
@MainActor
final class VersaoDoIndice: ObservableObject {
    static let shared = VersaoDoIndice()
    @Published var versao = 0
    private init() {}
}

/// As categorias e a pesquisa no aparelho.
///
/// No Android tudo isso sai do MediaStore, o índice que o sistema mantém de
/// todo arquivo do aparelho. O iOS não tem índice que um app possa consultar,
/// e de todo modo o app só enxerga os lugares autorizados. Então o índice é
/// nosso: a varredura de **todas** as subpastas de cada pasta autorizada, sem
/// limite de profundidade nem de quantidade (pedido do dono: "todas sem
/// exceção"). Pastas ocultas também entram; o que está dentro delas conta como
/// oculto e só aparece com "Mostrar arquivos ocultos".
///
/// A varredura roda em segundo plano assim que uma pasta é adicionada. Quando
/// algo muda (uma cópia terminou, a pessoa puxou para atualizar), as telas
/// continuam com o índice anterior enquanto o novo é montado, e recarregam
/// quando ele fica pronto — a categoria nunca fica presa esperando.
actor Indice {
    static let shared = Indice()

    private var arquivos: [FileEntry] = []
    private var pastas: [FileEntry] = []
    /// As raízes da última varredura completa; nulo = nunca varreu.
    private var raizesVarridas: [String]?
    private var sujo = false
    private var varrendo: Task<Void, Never>?
    private var ultima = Date.distantPast

    /// As pastas autorizadas que estão abrindo. A pasta interna do app entra
    /// só se não houver pasta padrão (ela deixou de aparecer no início).
    static func raizesAtuais() async -> [String] {
        await MainActor.run {
            let l = Lugares.shared
            let ids = l.autorizados.filter { !l.indisponiveis.contains($0.id) }.map(\.id)
            return l.padrao == nil ? [Lugares.docs] + ids : ids
        }
    }

    /// Algo mudou nos arquivos: varre de novo em segundo plano.
    func invalidar() {
        sujo = true
        if varrendo == nil { Task { await self.aquecer() } }
    }

    /// Ao voltar ao app: arquivos podem ter chegado pelo Arquivos ou pelo
    /// AirDrop. Mas varrer tudo a cada volta gastaria bateria à toa num acervo
    /// grande — no máximo a cada dois minutos.
    func renovarSeVelho() {
        if Date().timeIntervalSince(ultima) > 120 { invalidar() }
    }

    /// Começa a varredura se o índice está velho ou as pastas mudaram. Não
    /// espera ela acabar.
    func aquecer() async {
        guard varrendo == nil else { return }
        let atuais = await Self.raizesAtuais()
        guard varrendo == nil else { return }
        if raizesVarridas == atuais && !sujo { return }
        comecar(atuais)
    }

    /// Puxar para atualizar numa categoria: varre agora e espera.
    func varrerAgora() async {
        sujo = true
        await aquecer()
        while let v = varrendo { await v.value }
    }

    private func comecar(_ raizes: [String]) {
        sujo = false
        varrendo = Task { await self.varrer(raizes) }
    }

    private func varrer(_ raizes: [String]) async {
        let progresso = ProgressoDoIndice.shared
        await MainActor.run { progresso.varrendo = true; progresso.itens = 0 }
        var achados: [FileEntry] = [], dirs: [FileEntry] = []
        // Fila por índice, e não removeFirst: com dezenas de milhares de
        // pastas, tirar do começo do array a cada volta custa caro.
        var fila: [(Loc, Bool)] = raizes.map { (.local(raiz: $0, caminho: ""), false) }
        var i = 0
        var ultimoAviso = 0
        while i < fila.count {
            let (dir, ocultoPai) = fila[i]
            i += 1
            guard let filhos = try? await LocalFs.listar(dir) else { continue }
            for var f in filhos {
                f.oculto = ocultoPai || f.oculto
                if f.isDir {
                    dirs.append(f)
                    fila.append((f.loc, f.oculto))
                } else {
                    achados.append(f)
                }
            }
            let n = achados.count + dirs.count
            if n - ultimoAviso >= 500 {
                ultimoAviso = n
                await MainActor.run { progresso.itens = n }
            }
        }
        arquivos = achados
        pastas = dirs
        raizesVarridas = raizes
        ultima = Date()
        varrendo = nil
        let total = achados.count + dirs.count
        await MainActor.run {
            progresso.itens = total
            progresso.varrendo = false
            VersaoDoIndice.shared.versao += 1
        }
        // Mudou algo enquanto varria (outra pasta entrou, uma cópia acabou)?
        let agora = await Self.raizesAtuais()
        if sujo || agora != raizes { comecar(agora) }
    }

    /// Para as consultas: com índice das mesmas pastas, responde já (mesmo
    /// que uma varredura nova esteja a caminho); com pastas diferentes — a
    /// primeira vez, ou uma pasta entrou —, espera a varredura delas.
    private func garantir() async {
        let atuais = await Self.raizesAtuais()
        if raizesVarridas == atuais {
            if sujo && varrendo == nil { comecar(atuais) }
            return
        }
        if varrendo == nil { comecar(atuais) }
        while let v = varrendo {
            await v.value
            if raizesVarridas == atuais { return }
        }
    }

    func categoria(_ c: Categoria) async -> [FileEntry] {
        await garantir()
        return arquivos.filter { c.contem($0) }.sorted { ($0.modificado ?? .distantPast) > ($1.modificado ?? .distantPast) }
    }

    func pesquisar(_ termo: String, limite: Int = 500) async -> [FileEntry] {
        await garantir()
        let t = termo.trimmingCharacters(in: .whitespaces)
        guard !t.isEmpty else { return [] }
        let bate = (pastas + arquivos).filter { $0.nome.localizedCaseInsensitiveContains(t) }
        return Array(bate.sorted { ($0.modificado ?? .distantPast) > ($1.modificado ?? .distantPast) }.prefix(limite))
    }
}
