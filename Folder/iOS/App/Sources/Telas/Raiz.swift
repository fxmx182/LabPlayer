import SwiftUI

/// Cada tela da pilha. O SwiftUI guarda a rolagem de cada uma: o "voltar" cai
/// onde estava, como no Android.
enum Rota: Hashable {
    case pasta(Loc)
    case categoria(Categoria)
    /// `dentro` nulo = pesquisa no aparelho (o índice); senão, varre a pasta e
    /// as subpastas.
    case busca(String, dentro: Loc?)
    case rede
    case configuracoes
}

/// Itens escolhidos em "Mover"/"Copiar", esperando a pasta de destino.
struct Clip: Equatable {
    var itens: [FileEntry]
    var mover: Bool
}

@MainActor
final class Navegacao: ObservableObject {
    static let shared = Navegacao()
    @Published var caminho: [Rota] = []
    @Published var clip: Clip?
    private init() {}

    func abrir(_ r: Rota) { caminho.append(r) }
    func inicio() { caminho.removeAll() }

    /// A trilha: troca a pasta atual por uma do mesmo caminho, sem empilhar.
    func pular(_ loc: Loc) {
        while case .pasta(let l)? = caminho.last, l != loc, l.dentro(de: loc) {
            caminho.removeLast()
        }
        if caminho.last != .pasta(loc) { caminho.append(.pasta(loc)) }
    }
}

struct Raiz: View {
    // A raiz observa só o que ela mesma desenha: o caminho da pilha (nav) e o
    // erro de abrir (abridor). Os outros ela apenas entrega às telas — se os
    // observasse, cada achado da busca na rede redesenharia a pilha inteira.
    @ObservedObject private var nav = Navegacao.shared
    @ObservedObject private var abridor = Abridor.shared
    private let ops = Ops.shared
    private let avisos = Avisos.shared
    private let lugares = Lugares.shared
    private let servidores = Servidores.shared
    private let descobertas = Descobertas.shared
    private let prefs = Prefs.shared
    @Environment(\.scenePhase) private var fase

    var body: some View {
        ZStack(alignment: .bottom) {
            NavigationStack(path: $nav.caminho) {
                Inicio()
                    .navigationDestination(for: Rota.self) { r in
                        switch r {
                        case .pasta(let l): Listagem(modo: .pasta(l))
                        case .categoria(let c): Listagem(modo: .categoria(c))
                        case .busca(let t, let d): Listagem(modo: .busca(t, d))
                        case .rede: TelaDeRede()
                        case .configuracoes: Configuracoes()
                        }
                    }
            }
            PainelDeOperacao()
                .padding(.bottom, nav.clip != nil ? 96 : 60)
            AvisoCurto()
            AvisoDePreparo()
        }
        .background(BrilhoDeFundo())
        .environmentObject(nav)
        .environmentObject(ops)
        .environmentObject(abridor)
        .environmentObject(avisos)
        .environmentObject(lugares)
        .environmentObject(servidores)
        .environmentObject(descobertas)
        .environmentObject(prefs)
        .alert("Não foi possível abrir", isPresented: Binding(get: { abridor.erro != nil }, set: { if !$0 { abridor.erro = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(abridor.erro ?? "")
        }
        .onChange(of: fase) { _, nova in
            // Um pendrive ligado com o app em segundo plano: ao voltar, os
            // lugares que estavam fora tentam abrir de novo.
            if nova == .active {
                lugares.resolverTodos()
                Task { await Indice.shared.invalidar() }
            }
        }
        .onOpenURL { url in Recebidos.receber(url) }
    }
}

/// O que chega de outro app ("Abrir com LibertyX Folder", "Compartilhar")
/// vai para a pasta Recebidos, e o app abre nela.
enum Recebidos {
    static let nome = "Recebidos"

    @MainActor
    static func receber(_ url: URL) {
        guard url.isFileURL else { return }
        let pasta = Lugares.urlDocs.appendingPathComponent(nome, isDirectory: true)
        try? FileManager.default.createDirectory(at: pasta, withIntermediateDirectories: true)
        let escopo = url.startAccessingSecurityScopedResource()
        defer { if escopo { url.stopAccessingSecurityScopedResource() } }
        let existentes = Set(((try? FileManager.default.contentsOfDirectory(atPath: pasta.path)) ?? []).map { $0.lowercased() })
        let destino = pasta.appendingPathComponent(Ops.nomeLivre(url.lastPathComponent, isDir: false, existentes))
        // O que vem pela caixa de entrada (Documents/Inbox) já é uma cópia
        // nossa: move. O que é aberto no lugar pertence a outro app: copia.
        let daCaixa = url.path.contains("/Documents/Inbox/")
        do {
            if daCaixa { try FileManager.default.moveItem(at: url, to: destino) }
            else { try FileManager.default.copyItem(at: url, to: destino) }
        } catch {
            Abridor.shared.erro = error.localizedDescription
            return
        }
        Task { await Indice.shared.invalidar() }
        Navegacao.shared.inicio()
        Navegacao.shared.abrir(.pasta(.local(raiz: Lugares.docs, caminho: nome)))
        Avisos.shared.mostrar(String(localized: "Recebido: \(destino.lastPathComponent)"))
    }
}
