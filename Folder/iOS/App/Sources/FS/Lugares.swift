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
    /// A pasta padrão, escolhida na configuração inicial: vem primeiro no
    /// início e recebe o que chega de outros apps. Substitui a pasta interna
    /// do app ("No iPhone"), que o dono pediu para tirar.
    @Published private(set) var padrao: String?
    private let chavePadrao = "folder.padrao"

    private let chave = "folder.lugares"

    private init() {
        padrao = UserDefaults.standard.string(forKey: chavePadrao)
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
            // Pendrive tirado e posto de novo volta montado noutro caminho: a
            // URL antiga não existe mais, e o bookmark acha a nova.
            if let atual = Raizes.url(l.id) {
                if FileManager.default.fileExists(atPath: atual.path) { continue }
                atual.stopAccessingSecurityScopedResource()
                Raizes.definir(l.id, nil)
            }
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
        // Publicar só o que mudou: cada publicação redesenha o início, e isto
        // roda a cada "puxar para atualizar" e a cada volta ao app.
        if fora != indisponiveis {
            indisponiveis = fora
            Task { await Indice.shared.aquecer() }
        }
        if let d = try? JSONEncoder().encode(autorizados) { UserDefaults.standard.set(d, forKey: chave) }
    }

    /// Uma pasta escolhida no seletor. Escolher de novo uma que já está na
    /// lista (o mesmo caminho) só renova o acesso dela.
    @discardableResult
    /// Várias pastas marcadas de uma vez no seletor. É o mais perto que o iOS
    /// deixa chegar de "todas as pastas dos apps": cada pasta de app tem a
    /// própria permissão — no Arquivos elas aparecem dentro de "No iPhone",
    /// mas moram cada uma na caixa do seu app, e escolher "No iPhone" não
    /// libera nenhuma delas. Devolve as que entraram agora (não as que só
    /// renovaram o acesso) e as mensagens de erro.
    func adicionarVarias(_ urls: [URL]) -> (novas: [Lugar], erros: [String]) {
        var novas: [Lugar] = [], erros: [String] = []
        for u in urls {
            let antes = Set(autorizados.map(\.id))
            do {
                let l = try adicionar(u)
                if !antes.contains(l.id) { novas.append(l) }
            } catch {
                erros.append("\(u.lastPathComponent): \(error.localizedDescription)")
            }
        }
        return (novas, erros)
    }

    /// `substituindo`: a pasta indisponível que a pessoa tocou para autorizar
    /// de novo — ela mantém o nome que tinha (renomeado ou não).
    func adicionar(_ url: URL, substituindo: Lugar? = nil) throws -> Lugar {
        guard url.startAccessingSecurityScopedResource() else {
            throw ErroDeArquivo.semAcesso(url.lastPathComponent)
        }
        let b = try url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil)
        if let i = autorizados.firstIndex(where: { $0.id == substituindo?.id || Raizes.url($0.id)?.standardizedFileURL == url.standardizedFileURL }) {
            autorizados[i].bookmark = b
            if let velha = Raizes.url(autorizados[i].id), velha.standardizedFileURL != url.standardizedFileURL {
                velha.stopAccessingSecurityScopedResource()
            }
            Raizes.definir(autorizados[i].id, url)
            indisponiveis.remove(autorizados[i].id)
            salvar()
            Task { await Indice.shared.aquecer() }
            return autorizados[i]
        }
        let novo = Lugar(id: UUID().uuidString, nome: nomeDe(url), bookmark: b)
        autorizados.append(novo)
        Raizes.definir(novo.id, url)
        // Varre todas as subpastas já, em segundo plano: quando a pessoa abrir
        // uma categoria, os arquivos dela já estão lá.
        Task { await Indice.shared.aquecer() }
        salvar()
        return novo
    }

    func remover(_ l: Lugar) {
        if let u = Raizes.url(l.id) { u.stopAccessingSecurityScopedResource() }
        Raizes.definir(l.id, nil)
        autorizados.removeAll { $0.id == l.id }
        indisponiveis.remove(l.id)
        if padrao == l.id { definirPadrao(nil) }
        salvar()
        Task { await Indice.shared.aquecer() }
    }

    func definirPadrao(_ l: Lugar?) {
        padrao = l?.id
        UserDefaults.standard.set(l?.id, forKey: chavePadrao)
        // A padrão vai para o topo da lista.
        if let l, let i = autorizados.firstIndex(where: { $0.id == l.id }), i != 0 {
            autorizados.insert(autorizados.remove(at: i), at: 0)
        }
        salvar()
        Task { await Indice.shared.aquecer() }
    }

    var lugarPadrao: Lugar? { padrao.flatMap { lugar($0) } }

    /// Onde guardar o que chega de outros apps: a pasta padrão, se abrir;
    /// senão, a pasta interna do app.
    var raizDeEntrada: String {
        if let p = padrao, Raizes.url(p) != nil, !indisponiveis.contains(p) { return p }
        return Self.docs
    }

    /// O nome é só do app: a pasta no disco continua com o nome dela.
    func renomear(_ l: Lugar, _ nome: String) {
        let n = nome.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !n.isEmpty, let i = autorizados.firstIndex(where: { $0.id == l.id }) else { return }
        autorizados[i].nome = n
        salvar()
    }

    func lugar(_ id: String) -> Lugar? { autorizados.first { $0.id == id } }

    /// O nome de uma raiz na trilha e no título da tela.
    func nome(raiz: String) -> String {
        switch raiz {
        case Self.docs: return String(localized: "Pasta interna do app")
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
    ///
    /// Pasta de outro app (e a de "No iPhone") chega chamada "Documents" ou
    /// "File Provider Storage": é o nome no disco, não o que o Arquivos mostra.
    /// O nome de exibição do sistema costuma ser o certo (o nome do app); se
    /// ele também for genérico, sai o tipo do lugar. Mesmo assim pode cair
    /// num nome repetido — por isso o app pede um nome ao adicionar, e dá
    /// para renomear depois.
    private func nomeDe(_ url: URL) -> String {
        let v = try? url.resourceValues(forKeys: [.volumeLocalizedNameKey, .isVolumeKey, .localizedNameKey])
        if v?.isVolume == true, let n = v?.volumeLocalizedName, !n.isEmpty { return semRepetir(n) }
        let genericos: Set<String> = ["documents", "file provider storage", "com~apple~clouddocs", "mobile documents", ""]
        let candidatos = [v?.localizedName, url.lastPathComponent]
        if let n = candidatos.compactMap({ $0 }).first(where: { !genericos.contains($0.lowercased()) }) {
            return semRepetir(n)
        }
        let tipo = TipoDeLugar.de(url)
        return semRepetir(tipo == .pasta ? String(localized: "Pasta") : tipo.descricao)
    }

    /// "Documents", "Documents 2", "Documents 3"…
    private func semRepetir(_ n: String) -> String {
        let usados = Set(autorizados.map { $0.nome.lowercased() })
        guard usados.contains(n.lowercased()) else { return n }
        var i = 2
        while usados.contains("\(n) \(i)".lowercased()) { i += 1 }
        return "\(n) \(i)"
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
