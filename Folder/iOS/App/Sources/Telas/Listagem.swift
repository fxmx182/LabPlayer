import SwiftUI
import PhotosUI
import UniformTypeIdentifiers

enum ModoDeLista: Hashable {
    case pasta(Loc)
    case categoria(Categoria)
    case busca(String, Loc?)
}

/// Pasta (do aparelho ou do servidor), categoria ou resultado de pesquisa —
/// tudo é uma lista de arquivos, como no `ListingScreen` do Android.
struct Listagem: View {
    let modo: ModoDeLista

    @EnvironmentObject private var nav: Navegacao
    @EnvironmentObject private var ops: Ops
    @EnvironmentObject private var prefs: Prefs
    @EnvironmentObject private var lugares: Lugares
    @EnvironmentObject private var servidores: Servidores
    @EnvironmentObject private var abridor: Abridor

    @State private var itens: [FileEntry]?
    @State private var erro: String?
    @State private var carregando = false
    @State private var recarga = 0
    @State private var sel: [Loc: FileEntry] = [:]
    @State private var filtro = ""
    /// A lista como aparece (filtrada, ordenada). Guardada, e não calculada no
    /// `body`: ordenar milhares de nomes a cada redesenho dava os soquinhos.
    @State private var mostrados: [FileEntry] = []

    @State private var pedindoNome: PedidoDeNome?
    @State private var nomeDigitado = ""
    @State private var confirmandoExclusao = false
    @State private var detalhes: ItensDeDetalhe?
    @State private var importandoArquivos = false
    @State private var importandoFotos = false
    @State private var erroDeAcao: String?

    private enum PedidoDeNome: Identifiable {
        case novaPasta
        case renomear(FileEntry)
        var id: String {
            switch self {
            case .novaPasta: return "nova"
            case .renomear(let e): return "ren-\(e.loc)"
            }
        }
    }

    private var pasta: Loc? { if case .pasta(let l) = modo { return l } else { return nil } }
    private var selecionando: Bool { !sel.isEmpty }
    private var podeCriar: Bool { pasta.map { !$0.ehRaizDoServidor } ?? false }

    var body: some View {
        conteudo
            .navigationTitle(selecionando ? Plural.selecionados(sel.count) : titulo)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(BrilhoDeFundo())
            .navigationBarTitleDisplayMode(.inline)
            .navigationBarBackButtonHidden(selecionando)
            .toolbar { barra }
            .searchable(text: $filtro, placement: .navigationBarDrawer(displayMode: .automatic),
                        prompt: Text(pasta != nil ? String(localized: "Pesquisar nesta pasta") : String(localized: "Pesquisar")))
            .safeAreaInset(edge: .top, spacing: 0) {
                if let pasta, !selecionando { Trilha(loc: pasta) }
            }
            .safeAreaInset(edge: .bottom, spacing: 0) { barraDeBaixo }
            .refreshable { await atualizar() }
            .onChange(of: itens) { _, _ in recalcular() }
            .onChange(of: filtro) { _, _ in recalcular() }
            .onChange(of: prefs.ordem) { _, _ in recalcular() }
            .onChange(of: prefs.crescente) { _, _ in recalcular() }
            .onChange(of: prefs.ocultos) { _, _ in recalcular() }
            .task(id: "\(modo)-\(ops.versao)-\(recarga)-\(lugares.versao)") { await carregar() }
            .alert(tituloDoPedido, isPresented: Binding(get: { pedindoNome != nil }, set: { if !$0 { pedindoNome = nil } })) {
                TextField("Nome", text: $nomeDigitado)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                Button(botaoDoPedido) { confirmarNome() }
                    .disabled(nomeInvalido)
                Button("Cancelar", role: .cancel) { pedindoNome = nil }
            }
            .confirmationDialog(Plural.excluirTitulo(sel.count), isPresented: $confirmandoExclusao, titleVisibility: .visible) {
                Button("Excluir", role: .destructive) {
                    ops.excluir(Array(sel.values))
                    sel.removeAll()
                }
            } message: {
                Text(sel.values.contains { $0.loc.ehSmb } ? String(localized: "Os arquivos serão apagados do servidor. Isso não pode ser desfeito.") : String(localized: "Isso não pode ser desfeito."))
            }
            .sheet(item: $detalhes) { d in
                Detalhes(itens: d.itens)
            }
            .fileImporter(isPresented: $importandoArquivos, allowedContentTypes: [.item], allowsMultipleSelection: true) { r in
                if case .success(let urls) = r, let pasta { importarArquivos(urls, para: pasta) }
            }
            .sheet(isPresented: $importandoFotos) {
                SeletorDeFotos { provedores in
                    importandoFotos = false
                    if let pasta, !provedores.isEmpty { importarFotos(provedores, para: pasta) }
                }
                .ignoresSafeArea()
            }
            .alert("Não foi possível concluir", isPresented: Binding(get: { erroDeAcao != nil }, set: { if !$0 { erroDeAcao = nil } })) {
                Button("OK", role: .cancel) {}
            } message: { Text(erroDeAcao ?? "") }
    }

    // MARK: - Título e barras

    private var titulo: String {
        switch modo {
        case .pasta(let l):
            switch l {
            case .local(let r, let c): return c.isEmpty ? lugares.nome(raiz: r) : l.nome
            case .smb(let id, let s, _): return s.isEmpty ? (servidores.porId(id)?.nome ?? String(localized: "Servidor")) : l.nome
            }
        case .categoria(let c): return c.titulo
        case .busca(let t, _): return "“\(t)”"
        }
    }

    @ToolbarContentBuilder private var barra: some ToolbarContent {
        if selecionando {
            ToolbarItem(placement: .topBarLeading) {
                Button { sel.removeAll() } label: { Image(systemName: "xmark") }
                    .accessibilityLabel(Text("Cancelar"))
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button(sel.count == mostrados.count ? String(localized: "Nenhum") : String(localized: "Tudo")) {
                    if sel.count == mostrados.count { sel.removeAll() }
                    else { for e in mostrados { sel[e.loc] = e } }
                }
            }
        } else {
            ToolbarItem(placement: .topBarTrailing) {
                Button { prefs.grade.toggle() } label: {
                    Image(systemName: prefs.grade ? "list.bullet" : "square.grid.2x2")
                }
                .accessibilityLabel(Text("Visualização"))
            }
            ToolbarItem(placement: .topBarTrailing) { menu }
        }
    }

    private var menu: some View {
        Menu {
            if podeCriar {
                Button { nomeDigitado = ""; pedindoNome = .novaPasta } label: { Label("Nova pasta", systemImage: "folder.badge.plus") }
                Button { importandoFotos = true } label: { Label("Importar da galeria", systemImage: "photo.on.rectangle") }
                Button { importandoArquivos = true } label: { Label("Importar arquivos", systemImage: "square.and.arrow.down") }
            }
            Button { if let p = mostrados.first { sel[p.loc] = p } } label: { Label("Selecionar", systemImage: "checkmark.circle") }
            Section("Ordenar por") {
                Picker("Ordenar por", selection: $prefs.ordem) {
                    ForEach(Ordem.allCases) { o in Text(o.titulo).tag(o) }
                }
                Toggle("Ordem crescente", isOn: $prefs.crescente)
                Toggle("Mostrar arquivos ocultos", isOn: $prefs.ocultos)
            }
            Button { recarga += 1 } label: { Label("Atualizar", systemImage: "arrow.clockwise") }
            if case .smb(let id, _, _)? = pasta {
                Button {
                    Task { await SmbFs.esquecer(id); recarga += 1 }
                } label: { Label("Desconectar e reconectar", systemImage: "bolt.horizontal") }
            }
            Button { nav.inicio() } label: { Label("Início", systemImage: "house") }
        } label: {
            Image(systemName: "ellipsis.circle")
        }
        .accessibilityLabel(Text("Mais"))
    }

    @ViewBuilder private var barraDeBaixo: some View {
        if selecionando {
            let itensSel = Array(sel.values)
            BarraDeSelecao(
                itens: itensSel,
                mover: { nav.clip = Clip(itens: itensSel, mover: true); sel.removeAll() },
                copiar: { nav.clip = Clip(itens: itensSel, mover: false); sel.removeAll() },
                compartilhar: { abridor.compartilhar(itensSel) },
                excluir: { confirmandoExclusao = true },
                renomear: {
                    if let e = itensSel.first { nomeDigitado = e.nome; pedindoNome = .renomear(e) }
                },
                abrirCom: { abridor.compartilhar(itensSel) },
                galeria: { abridor.salvarNaGaleria(itensSel); sel.removeAll() },
                detalhes: { detalhes = ItensDeDetalhe(itens: itensSel) }
            )
        } else if let c = nav.clip {
            let colar: (() -> Void)? = pasta.map { destino -> () -> Void in
                return {
                    ops.transferir(c.itens, para: destino, mover: c.mover)
                    nav.clip = nil
                }
            }
            BarraDeColar(clip: c, habilitado: podeCriar && !ops.ocupado, cancelar: { nav.clip = nil }, colar: colar)
        }
    }

    // MARK: - Conteúdo

    private func recalcular() {
        let novos = filtrar()
        if novos != mostrados { mostrados = novos }
    }

    private func filtrar() -> [FileEntry] {
        let base = itens ?? []
        let f = filtro.trimmingCharacters(in: .whitespaces)
        let filtrados = f.isEmpty ? base : base.filter { $0.nome.localizedCaseInsensitiveContains(f) }
        if case .pasta = modo { return prefs.ordenar(filtrados) }
        return filtrados.filter { prefs.ocultos || !$0.oculto }
    }

    @ViewBuilder private var conteudo: some View {
        if itens == nil && erro == nil {
            VStack(spacing: 16) {
                ProgressView().tint(Lx.ouro)
                if case .smb(let id, _, _)? = pasta {
                    Text(String(localized: "Conectando a \(servidores.porId(id)?.nome ?? String(localized: "Servidor"))…"))
                        .font(LxFonte.f(14)).foregroundStyle(Lx.apagado)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let erro {
            ScrollView {
                EstadoVazio(texto: String(localized: "Não foi possível abrir"), sub: erro,
                            simbolo: pasta?.ehSmb == true ? "wifi.slash" : "folder.badge.questionmark")
                Button("Tentar de novo") {
                    if case .smb(let id, _, _)? = pasta { Task { await SmbFs.esquecer(id); recarga += 1 } } else { recarga += 1 }
                }
                .font(LxFonte.f(15, .semibold))
            }
        } else if mostrados.isEmpty && !carregando {
            ScrollView {
                EstadoVazio(texto: filtro.isEmpty && !isBusca ? String(localized: "Nenhum arquivo") : String(localized: "Nada encontrado"))
                if let pasta, !filtro.isEmpty { botaoSubpastas(pasta) }
            }
        } else {
            ScrollView {
                if isBusca && carregando {
                    Text("Pesquisando…").font(LxFonte.f(13)).foregroundStyle(Lx.tenue)
                        .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 20).padding(.vertical, 8)
                }
                if let pasta, !filtro.isEmpty { botaoSubpastas(pasta) }
                if prefs.grade {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 104), spacing: 4, alignment: .top)], spacing: 4) {
                        ForEach(mostrados) { e in
                            CelulaDeArquivo(e: e, selecionando: selecionando, selecionado: sel[e.loc] != nil,
                                            aoTocar: { tocar(e) }, aoSegurar: { marcar(e) })
                        }
                    }
                    .padding(.horizontal, 10).padding(.vertical, 6)
                } else {
                    LazyVStack(spacing: 0) {
                        ForEach(mostrados) { e in
                            LinhaDeArquivo(e: e, selecionando: selecionando, selecionado: sel[e.loc] != nil,
                                           subtitulo: subtituloForaDaPasta(e),
                                           aoTocar: { tocar(e) }, aoSegurar: { marcar(e) })
                        }
                    }
                    .padding(.bottom, 90)
                }
            }
            .frame(maxWidth: Escala.larguraMaxima)
            .frame(maxWidth: .infinity)
        }
    }

    private var isBusca: Bool { if case .busca = modo { return true } else { return false } }

    private func botaoSubpastas(_ pasta: Loc) -> some View {
        Button {
            nav.abrir(.busca(filtro.trimmingCharacters(in: .whitespaces), dentro: pasta))
            filtro = ""
        } label: {
            Label(String(localized: "Pesquisar “\(filtro)” nas subpastas"), systemImage: "arrow.turn.down.right")
                .font(LxFonte.f(14, .semibold)).foregroundStyle(Lx.ouro)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 22).padding(.vertical, 10)
        }
    }

    /// Fora de uma pasta (categoria, pesquisa) cada linha diz de onde vem.
    private func subtituloForaDaPasta(_ e: FileEntry) -> String? {
        guard pasta == nil else { return nil }
        return [onde(e.loc.pai ?? e.loc), e.isDir ? "" : Fmt.tamanho(e.tamanho)]
            .filter { !$0.isEmpty }.joined(separator: "  ·  ")
    }

    private func onde(_ l: Loc) -> String {
        switch l {
        case .local(let r, let c): return lugares.nome(raiz: r) + (c.isEmpty ? "" : "/" + c)
        case .smb(let id, let s, let c):
            return (servidores.porId(id)?.nome ?? "?") + "/" + s + (c.isEmpty ? "" : "/" + c)
        }
    }

    // MARK: - Ações

    private func tocar(_ e: FileEntry) {
        if selecionando { marcar(e); return }
        if e.isDir { nav.abrir(.pasta(e.loc)) } else { abridor.abrir(e, irmaos: mostrados) }
    }

    private func marcar(_ e: FileEntry) {
        if sel[e.loc] != nil { sel[e.loc] = nil } else { sel[e.loc] = e }
    }

    /// Só troca a lista se ela mudou: atualizar uma pasta igual não pode
    /// redesenhar (e piscar) linha nenhuma.
    private func trocar(_ novos: [FileEntry]) {
        if novos != itens { itens = novos }
    }

    /// Puxar para atualizar. O indicador some aos trancos quando a ação volta
    /// na hora (pasta do iPhone lista em milissegundos), então ela dura pelo
    /// menos meio segundo — e o `carregando` não muda no meio, para nada na
    /// tela trocar de lugar enquanto o indicador recolhe.
    private func atualizar() async {
        let inicio = Date()
        await carregar(silencioso: true)
        let resto = 0.5 - Date().timeIntervalSince(inicio)
        if resto > 0 { try? await Task.sleep(nanoseconds: UInt64(resto * 1_000_000_000)) }
    }

    private func carregar(silencioso: Bool = false) async {
        if !silencioso { carregando = true }
        if erro != nil { erro = nil }
        defer { if carregando { carregando = false } }
        do {
            switch modo {
            case .pasta(let l):
                trocar(try await Arquivos.listar(l))
            case .categoria(let c):
                trocar(await Indice.shared.categoria(c))
            case .busca(let t, .none):
                trocar(await Indice.shared.pesquisar(t))
            case .busca(let t, let dentro?):
                // Varre as subpastas e mostra o que acha enquanto acha.
                var achados: [FileEntry] = []
                itens = []
                var fila: [(Loc, Int)] = [(dentro, 0)]
                while !fila.isEmpty {
                    try Task.checkCancellation()
                    let (l, nivel) = fila.removeFirst()
                    let filhos = (try? await Arquivos.listar(l)) ?? []
                    let bate = filhos.filter { $0.nome.localizedCaseInsensitiveContains(t) }
                    if !bate.isEmpty { achados += bate; itens = achados }
                    if nivel < 12 { fila += filhos.filter { $0.isDir && !$0.nome.hasPrefix(".") }.map { ($0.loc, nivel + 1) } }
                }
            }
        } catch is CancellationError {
        } catch {
            erro = error.localizedDescription
        }
        // O que foi apagado ou movido por outra tela não fica marcado.
        if let itens {
            if !sel.isEmpty {
                let existentes = Set(itens.map(\.loc))
                let ficam = sel.filter { existentes.contains($0.key) }
                if ficam.count != sel.count { sel = ficam }
            }
        }
    }

    // MARK: - Nome (nova pasta / renomear)

    private var tituloDoPedido: String {
        if case .renomear? = pedindoNome { return String(localized: "Renomear") }
        return String(localized: "Nova pasta")
    }

    private var botaoDoPedido: String {
        if case .renomear? = pedindoNome { return String(localized: "Renomear") }
        return String(localized: "Criar")
    }

    private var nomeInvalido: Bool {
        let n = nomeDigitado.trimmingCharacters(in: .whitespaces)
        return n.isEmpty || n.contains("/") || n.contains("\\") || n == "." || n == ".."
    }

    private func confirmarNome() {
        let nome = nomeDigitado.trimmingCharacters(in: .whitespaces)
        guard !nomeInvalido, let pedido = pedindoNome else { return }
        pedindoNome = nil
        Task {
            do {
                switch pedido {
                case .novaPasta:
                    if let pasta { _ = try await Arquivos.criarPasta(pasta, nome) }
                case .renomear(let e):
                    sel.removeAll()
                    if nome != e.nome { _ = try await Arquivos.renomear(e.loc, nome) }
                }
                await Indice.shared.invalidar()
                recarga += 1
            } catch {
                erroDeAcao = error.localizedDescription
            }
        }
    }

    // MARK: - Importar

    /// Arquivos escolhidos no seletor do sistema: passam pela pasta
    /// temporária e seguem para cá pela mesma operação de copiar — com
    /// progresso, e servindo também para pasta de servidor.
    private func importarArquivos(_ urls: [URL], para destino: Loc) {
        Task {
            do {
                let entradas = try await Importacao.trazer(urls)
                if !entradas.isEmpty { ops.transferir(entradas, para: destino, mover: true) }
            } catch { erroDeAcao = error.localizedDescription }
        }
    }

    private func importarFotos(_ provedores: [NSItemProvider], para destino: Loc) {
        Task {
            do {
                let entradas = try await Importacao.trazer(provedores)
                if !entradas.isEmpty { ops.transferir(entradas, para: destino, mover: true) }
            } catch { erroDeAcao = error.localizedDescription }
        }
    }
}

struct ItensDeDetalhe: Identifiable {
    let id = UUID()
    let itens: [FileEntry]
}
