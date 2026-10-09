import Foundation
import SwiftUI

/// Os lugares do aparelho que o app enxerga — a parte que o iOS muda por
/// inteiro em relação ao Android.
///
/// No Android o Folder pede "acesso a todos os arquivos" e vê o armazenamento
/// inteiro. O iOS não tem essa permissão: cada app vive na própria caixa, e
/// fora dela só enxerga o que a pessoa **escolhe no seletor do sistema**
/// (`UIDocumentPickerViewController`). Escolhida uma pasta — do iCloud Drive,
/// de "No iPhone", de um pendrive ou da pasta de outro app —, o iOS entrega uma
/// URL com escopo de segurança que vale para a árvore inteira, e um *bookmark*
/// que o app guarda para reabri-la nas próximas vezes sem perguntar de novo.
///
/// Então o "armazenamento" aqui é uma lista de raízes:
/// - `docs`: a pasta do próprio app (aparece no Arquivos como
///   "No iPhone › LibertyX Folder"). Sempre existe, não pede nada.
/// - uma por pasta autorizada, identificada por um UUID.
/// - `tmp`: escondida, onde chega o que vem da galeria antes de ir para o
///   destino — assim importar usa a mesma operação de copiar com progresso.
struct Lugar: Codable, Identifiable, Hashable {
    var id: String
    var nome: String
    var bookmark: Data?
    var adicionadoEm = Date()
}

enum TipoDeLugar {
    case app, icloud, externo, iphone, outroApp, pasta

    var simbolo: String {
        switch self {
        case .app: return "iphone"
        case .icloud: return "icloud.fill"
        case .externo: return "externaldrive.fill"
        case .iphone: return "iphone"
        case .outroApp: return "app.badge.fill"
        case .pasta: return "folder.fill"
        }
    }

    var descricao: String {
        switch self {
        case .app: return String(localized: "Pasta do app no iPhone")
        case .icloud: return String(localized: "iCloud Drive")
        case .externo: return String(localized: "Pendrive ou disco externo")
        case .iphone: return String(localized: "No iPhone")
        case .outroApp: return String(localized: "Pasta de outro app")
        case .pasta: return String(localized: "Pasta autorizada")
        }
    }

    /// Pelo caminho que o iOS entrega. Não há API que diga "isto é o iCloud":
    /// os caminhos de cada provedor são estáveis desde o iOS 13 e é o que o
    /// próprio app Arquivos expõe.
    static func de(_ url: URL) -> TipoDeLugar {
        let p = url.path
        if p.contains("/Mobile Documents/") { return .icloud }
        if p.contains("/LiveFiles/") || p.hasPrefix("/private/var/mobile/Library/LiveFiles") || p.hasPrefix("/Volumes/") { return .externo }
        if p.contains("/File Provider Storage") { return .iphone }
        if p.contains("/Containers/Data/Application/") { return .outroApp }
        return .pasta
    }
}

/// As URLs resolvidas de cada raiz, lidas de qualquer thread — o sistema de
/// arquivos trabalha fora da principal, e `Lugares` é da principal.
enum Raizes {
    private static let trava = NSLock()
    private static var urls: [String: URL] = [:]

    static func url(_ id: String) -> URL? {
        switch id {
        case Lugares.docs: return Lugares.urlDocs
        case Lugares.tmp: return Lugares.urlTmp
        default:
            trava.lock(); defer { trava.unlock() }
            return urls[id]
        }
    }

    static func definir(_ id: String, _ url: URL?) {
        trava.lock(); defer { trava.unlock() }
        urls[id] = url
    }

    /// A URL de um `Loc.local`.
    static func url(de loc: Loc) -> URL? {
        guard case .local(let r, let c) = loc, let base = url(r) else { return nil }
        return c.isEmpty ? base : base.appendingPathComponent(c)
    }
}

@MainActor
final class Lugares: ObservableObject {
    static let shared = Lugares()
    nonisolated static let docs = "docs"
    nonisolated static let tmp = "tmp"

    nonisolated static var urlDocs: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }
    nonisolated static var urlTmp: URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("importar", isDirectory: true)
    }

    /// As pastas autorizadas, na ordem em que aparecem no início.
    @Published private(set) var autorizados: [Lugar] = []
    /// As que não abriram desta vez: pendrive desligado, pasta apagada, ou o
    /// iOS invalidou o bookmark. Ficam na lista, mas pedindo nova autorização.
    @Published private(set) var indisponiveis: Set<String> = []
    /// Muda quando qualquer lugar entra ou sai — o índice das categorias recomeça.
    @Published private(set) var versao = 0

    private let chave = "folder.lugares"

    private init() {
        if let d = UserDefaults.standard.data(forKey: chave),
           let l = try? JSONDecoder().decode([Lugar].self, from: d) {
            autorizados = l
        }
        resolverTodos()
    }

    private func salvar() {
        if let d = try? JSONEncoder().encode(autorizados) { UserDefaults.standard.set(d, forKey: chave) }
        versao += 1
    }

    /// Reabre cada bookmark e **segura** o escopo enquanto o app viver: soltar
    /// e pegar de novo a cada leitura custaria uma chamada ao sistema por
    /// arquivo, e uma operação de cópia longa perderia o acesso no meio.
    func resolverTodos() {
        var fora: Set<String> = []
        for (i, l) in autorizados.enumerated() {
            guard Raizes.url(l.id) == nil else { continue }
            guard let b = l.bookmark else { fora.insert(l.id); continue }
            var velho = false
            guard let url = try? URL(resolvingBookmarkData: b, options: [], relativeTo: nil, bookmarkDataIsStale: &velho),
                  url.startAccessingSecurityScopedResource() else {
                fora.insert(l.id); continue
            }
            // Bookmark vencido ainda abre, mas pode parar a qualquer hora:
            // troca-se por um novo enquanto o acesso está valendo.
            if velho, let novo = try? url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil) {
                autorizados[i].bookmark = novo
            }
            Raizes.definir(l.id, url)
        }
        indisponiveis = fora
        if let d = try? JSONEncoder().encode(autorizados) { UserDefaults.standard.set(d, forKey: chave) }
    }

    /// Uma pasta escolhida no seletor. Escolher de novo uma que já está na
    /// lista (o mesmo caminho) só renova o acesso dela.
    @discardableResult
    func adicionar(_ url: URL) throws -> Lugar {
        guard url.startAccessingSecurityScopedResource() else {
            throw ErroDeArquivo.semAcesso(url.lastPathComponent)
        }
        let b = try url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil)
        let nome = Self.nomeDe(url)
        if let i = autorizados.firstIndex(where: { Raizes.url($0.id)?.standardizedFileURL == url.standardizedFileURL || ($0.nome == nome && indisponiveis.contains($0.id)) }) {
            autorizados[i].bookmark = b
            Raizes.definir(autorizados[i].id, url)
            indisponiveis.remove(autorizados[i].id)
            salvar()
            return autorizados[i]
        }
        let novo = Lugar(id: UUID().uuidString, nome: nome, bookmark: b)
        autorizados.append(novo)
        Raizes.definir(novo.id, url)
        salvar()
        return novo
    }

    func remover(_ l: Lugar) {
        if let u = Raizes.url(l.id) { u.stopAccessingSecurityScopedResource() }
        Raizes.definir(l.id, nil)
        autorizados.removeAll { $0.id == l.id }
        indisponiveis.remove(l.id)
        salvar()
    }

    func renomear(_ l: Lugar, _ nome: String) {
        guard let i = autorizados.firstIndex(where: { $0.id == l.id }) else { return }
        autorizados[i].nome = nome
        salvar()
    }

    func lugar(_ id: String) -> Lugar? { autorizados.first { $0.id == id } }

    /// O nome de uma raiz na trilha e no título da tela.
    func nome(raiz: String) -> String {
        switch raiz {
        case Self.docs: return String(localized: "No iPhone")
        case Self.tmp: return String(localized: "Importar")
        default: return lugar(raiz)?.nome ?? "?"
        }
    }

    func tipo(raiz: String) -> TipoDeLugar {
        if raiz == Self.docs { return .app }
        guard let u = Raizes.url(raiz) else { return .pasta }
        return TipoDeLugar.de(u)
    }

    /// Volume de um pendrive tem nome próprio ("KINGSTON"); a pasta raiz dele
    /// chega com o nome do volume. Para o resto, o nome da pasta.
    private static func nomeDe(_ url: URL) -> String {
        if let v = try? url.resourceValues(forKeys: [.volumeLocalizedNameKey, .isVolumeKey]),
           v.isVolume == true, let n = v.volumeLocalizedName { return n }
        let n = url.lastPathComponent
        return n.isEmpty ? String(localized: "Pasta") : n
    }

    /// Usado e total do volume de uma raiz — o do iPhone para a pasta do app,
    /// o do pendrive para um pendrive. Nuvem não tem volume que faça sentido.
    nonisolated static func espaco(_ url: URL) -> (usado: Int64, total: Int64)? {
        let v = try? url.resourceValues(forKeys: [.volumeTotalCapacityKey, .volumeAvailableCapacityForImportantUsageKey, .volumeAvailableCapacityKey])
        guard let total = v?.volumeTotalCapacity, total > 0 else { return nil }
        let livre = v?.volumeAvailableCapacityForImportantUsage ?? Int64(v?.volumeAvailableCapacity ?? 0)
        return (Int64(total) - livre, Int64(total))
    }
}

enum ErroDeArquivo: LocalizedError {
    case semAcesso(String)
    case nomeExiste
    case naoAchado
    case dentroDeSi(mover: Bool)
    case raizDoServidor
    case shareNaoApaga
    case cancelado
    case mensagem(String)

    var errorDescription: String? {
        switch self {
        case .semAcesso(let n): return String(localized: "Sem acesso a \(n)")
        case .nomeExiste: return String(localized: "Já existe um item com esse nome")
        case .naoAchado: return String(localized: "Arquivo não encontrado")
        case .dentroDeSi(let mover):
            return mover ? String(localized: "Não dá para mover uma pasta para dentro dela mesma")
                         : String(localized: "Não dá para copiar uma pasta para dentro dela mesma")
        case .raizDoServidor: return String(localized: "Não dá para criar pasta na raiz do servidor")
        case .shareNaoApaga: return String(localized: "Compartilhamentos não podem ser apagados")
        case .cancelado: return String(localized: "Cancelado")
        case .mensagem(let m): return m
        }
    }
}
