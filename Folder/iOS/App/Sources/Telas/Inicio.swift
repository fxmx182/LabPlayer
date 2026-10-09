import SwiftUI
import UniformTypeIdentifiers

struct Inicio: View {
    @EnvironmentObject private var nav: Navegacao
    @EnvironmentObject private var ops: Ops
    @EnvironmentObject private var lugares: Lugares
    @EnvironmentObject private var servidores: Servidores
    @EnvironmentObject private var descobertas: Descobertas
    @EnvironmentObject private var abridor: Abridor

    @State private var termo = ""
    @State private var espacoDoApp: (usado: Int64, total: Int64)?
    @State private var rascunho: RascunhoDeServidor?
    @State private var escolhendoPasta = false
    @State private var paraDownloads = false
    @State private var explicarDownloads = false
    @State private var remover: Lugar?
    @State private var erro: String?
    @AppStorage("dica.pastas.fechada") private var dicaFechada = false

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                cabecalho
                busca
                TituloDeSecao(texto: "Categorias")
                categorias
                TituloDeSecao(texto: "Armazenamento")
                armazenamento
                if lugares.autorizados.isEmpty && !dicaFechada { dicaDePastas }
                tituloDaRede
                rede
            }
            .frame(maxWidth: Escala.larguraMaxima)
            .frame(maxWidth: .infinity)
            .padding(.bottom, 110)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(BrilhoDeFundo())
        .refreshable { await atualizar() }
        .toolbar(.hidden, for: .navigationBar)
        .safeAreaInset(edge: .bottom) {
            if let c = nav.clip {
                BarraDeColar(clip: c, habilitado: false, cancelar: { nav.clip = nil }, colar: nil)
            }
        }
        .task(id: "\(ops.versao)-\(lugares.versao)") { await carregar() }
        .task { if descobertas.achados.isEmpty { descobertas.buscar() } }
        .fileImporter(isPresented: $escolhendoPasta, allowedContentTypes: [.folder]) { r in
            switch r {
            case .success(let url):
                do {
                    let l = try lugares.adicionar(url)
                    if paraDownloads { nav.abrir(.pasta(.local(raiz: l.id, caminho: ""))) }
                } catch { erro = error.localizedDescription }
            case .failure(let e):
                erro = e.localizedDescription
            }
            paraDownloads = false
        }
        .sheet(item: $rascunho) { r in
            FormularioDeServidor(inicial: r) { s in
                rascunho = nil
                if r.existente == nil { nav.abrir(.pasta(.smb(servidor: s.id, share: "", caminho: ""))) }
            }
        }
        .alert("Downloads", isPresented: $explicarDownloads) {
            Button("Escolher pasta") { paraDownloads = true; escolhendoPasta = true }
            Button("Cancelar", role: .cancel) {}
        } message: {
            Text("O iOS não deixa um app abrir a pasta Downloads sozinho. Escolha ela uma vez — fica em iCloud Drive ou em No iPhone — e o atalho passa a abri-la direto.")
        }
        .confirmationDialog(remover.map { String(localized: "Remover o acesso a \($0.nome)?") } ?? "",
                            isPresented: Binding(get: { remover != nil }, set: { if !$0 { remover = nil } }),
                            titleVisibility: .visible) {
            Button("Remover acesso", role: .destructive) { if let r = remover { lugares.remover(r) }; remover = nil }
        } message: {
            Text("Os arquivos continuam onde estão; o app só deixa de ver esta pasta.")
        }
        .alert("Não foi possível abrir", isPresented: Binding(get: { erro != nil }, set: { if !$0 { erro = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(erro ?? "") }
    }

    /// Só o espaço do iPhone, e fora da principal: perguntar a capacidade do
    /// volume pode levar dezenas de milissegundos, e na principal isso é um
    /// solavanco no meio da rolagem.
    private func carregar() async {
        let e = await Task.detached(priority: .userInitiated) { Lugares.espaco(Lugares.urlDocs) }.value
        if e?.usado != espacoDoApp?.usado || e?.total != espacoDoApp?.total { espacoDoApp = e }
    }

    /// Puxar para atualizar: reabre as pastas autorizadas (o pendrive que
    /// acabou de ser ligado), recalcula o espaço e procura servidores de novo.
    /// Fica pelo menos meio segundo: se a ação volta na hora, o indicador do
    /// sistema recolhe aos trancos em vez de deslizar.
    private func atualizar() async {
        let inicio = Date()
        lugares.resolverTodos()
        await carregar()
        descobertas.buscar()
        let resto = 0.5 - Date().timeIntervalSince(inicio)
        if resto > 0 { try? await Task.sleep(nanoseconds: UInt64(resto * 1_000_000_000)) }
    }

    // MARK: - Partes

    private var cabecalho: some View {
        HStack(spacing: 12) {
            // A marca na própria tela, e não só no ícone: o mesmo desenho.
            Image("Marca").resizable().scaledToFit().frame(width: 40, height: 40)
            (Text("LibertyX").foregroundColor(Lx.texto) + Text(" Folder").foregroundColor(Lx.ouro))
                .font(LxFonte.titulao)
                .tracking(-0.8)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            Spacer()
            Button { nav.abrir(.configuracoes) } label: {
                Image(systemName: "gearshape.fill")
                    .font(.system(size: 18))
                    .foregroundStyle(Lx.apagado)
                    .frame(width: 44, height: 44)
                    .vidro(22)
            }
            .accessibilityLabel(Text("Configurações"))
        }
        .padding(.leading, 20).padding(.trailing, 14)
        .padding(.top, 24).padding(.bottom, 18)
    }

    private var busca: some View {
        HStack(spacing: 12) {
            Image(systemName: "magnifyingglass").foregroundStyle(Lx.apagado)
            TextField("Pesquisar no aparelho", text: $termo)
                .font(LxFonte.f(16))
                .submitLabel(.search)
                .autocorrectionDisabled()
                .onSubmit {
                    let t = termo.trimmingCharacters(in: .whitespaces)
                    if !t.isEmpty { nav.abrir(.busca(t, dentro: nil)) }
                }
        }
        .padding(.horizontal, 16).padding(.vertical, 15)
        .vidro(28)
        .padding(.horizontal, 16)
    }

    private var categorias: some View {
        let todas = Categoria.allCases
        let linhas = stride(from: 0, to: todas.count, by: 4).map { Array(todas[$0..<min($0 + 4, todas.count)]) }
        return Cartao {
            ForEach(linhas, id: \.self) { linha in
                HStack(spacing: 0) {
                    ForEach(linha) { c in
                        Button { tocar(c) } label: {
                            VStack(spacing: 8) {
                                Selo(cor: Color(hex: c.cor), simbolo: c.simbolo, tamanho: 50, raio: 16)
                                Text(c.titulo).font(LxFonte.f(12)).foregroundStyle(Lx.apagado).lineLimit(1)
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                    ForEach(0..<(4 - linha.count), id: \.self) { _ in Spacer().frame(maxWidth: .infinity) }
                }
                .padding(.horizontal, 8)
            }
        }
    }

    /// Downloads não é uma categoria: é uma pasta que o app não tem como achar
    /// sozinho no iOS. Com ela autorizada, o atalho abre direto.
    private func tocar(_ c: Categoria) {
        guard c == .downloads else { nav.abrir(.categoria(c)); return }
        let dl = lugares.autorizados.first { l in
            !lugares.indisponiveis.contains(l.id) &&
            (Raizes.url(l.id)?.lastPathComponent.lowercased() == "downloads" || l.nome.lowercased() == "downloads")
        }
        if let dl { nav.abrir(.pasta(.local(raiz: dl.id, caminho: ""))) } else { explicarDownloads = true }
    }

    private var armazenamento: some View {
        Cartao {
            linhaDoApp
            ForEach(lugares.autorizados) { l in
                let fora = lugares.indisponiveis.contains(l.id)
                let tipo = lugares.tipo(raiz: l.id)
                LinhaDeCartao(simbolo: fora ? "exclamationmark.triangle.fill" : tipo.simbolo,
                              titulo: l.nome,
                              sub: fora ? String(localized: "Indisponível — toque para autorizar de novo") : subtitulo(l, tipo),
                              cor: fora ? Lx.vermelho : Lx.ouro) {
                    if fora { escolhendoPasta = true } else { nav.abrir(.pasta(.local(raiz: l.id, caminho: ""))) }
                }
                .contextMenu {
                    Button(role: .destructive) { remover = l } label: { Label("Remover acesso", systemImage: "minus.circle") }
                }
            }
            LinhaDeCartao(simbolo: "plus", titulo: String(localized: "Adicionar pasta"),
                          sub: String(localized: "iCloud Drive, No iPhone, pendrive ou outro app"),
                          cor: Lx.verde) { escolhendoPasta = true }
        }
    }

    private var linhaDoApp: some View {
        Button { nav.abrir(.pasta(.local(raiz: Lugares.docs, caminho: ""))) } label: {
            HStack(spacing: 16) {
                Selo(cor: Lx.ouro, simbolo: "iphone")
                VStack(alignment: .leading, spacing: 3) {
                    Text("No iPhone").font(LxFonte.f(16, .medium)).foregroundStyle(Lx.texto)
                    if let e = espacoDoApp {
                        Text(String(localized: "\(Fmt.tamanho(e.usado)) de \(Fmt.tamanho(e.total))"))
                            .font(LxFonte.f(12)).foregroundStyle(Lx.tenue)
                        BarraDeUso(usado: e.usado, total: e.total).padding(.top, 3)
                    } else {
                        Text("Pasta do app no iPhone").font(LxFonte.f(12)).foregroundStyle(Lx.tenue)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 20).padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func subtitulo(_ l: Lugar, _ tipo: TipoDeLugar) -> String {
        if tipo == .externo, let u = Raizes.url(l.id), let e = Lugares.espaco(u) {
            return tipo.descricao + "  ·  " + String(localized: "\(Fmt.tamanho(e.total - e.usado)) livres")
        }
        return tipo.descricao
    }

    /// A explicação do jeito do iOS, enquanto nenhuma pasta foi autorizada.
    private var dicaDePastas: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top) {
                Image(systemName: "lock.shield.fill").font(.system(size: 22)).foregroundStyle(Lx.ouro)
                Text("O iOS guarda os arquivos de cada app numa caixa fechada")
                    .font(LxFonte.f(15, .semibold)).foregroundStyle(Lx.texto)
                Spacer(minLength: 0)
                Button { withAnimation { dicaFechada = true } } label: {
                    Image(systemName: "xmark").font(.system(size: 12, weight: .bold)).foregroundStyle(Lx.tenue)
                }
            }
            Text("Para o LibertyX Folder ver outras pastas — iCloud Drive, No iPhone, um pendrive ou a pasta de outro app —, escolha cada uma uma vez no seletor do sistema. O acesso fica salvo e vale para todas as subpastas.")
                .font(LxFonte.f(13)).foregroundStyle(Lx.apagado)
                .fixedSize(horizontal: false, vertical: true)
            Button { escolhendoPasta = true } label: {
                Text("Escolher pasta").font(LxFonte.f(15, .bold)).foregroundStyle(Color(hex: 0x1A1200))
                    .padding(.horizontal, 18).padding(.vertical, 10)
                    .background(Capsule().fill(Lx.ouro))
            }
            .padding(.top, 2)
        }
        .padding(18)
        .vidro(cor: Lx.ouro.opacity(0.06), borda: Lx.ouro.opacity(0.30))
        .padding(.horizontal, 16).padding(.top, 14)
    }

    private var tituloDaRede: some View {
        HStack {
            TituloDeSecao(texto: "Rede")
            Spacer()
            if descobertas.buscando {
                ProgressView().tint(Lx.ouro).scaleEffect(0.8).padding(.top, 14).padding(.trailing, 18)
            } else {
                Button { descobertas.buscar() } label: {
                    Image(systemName: "arrow.clockwise").foregroundStyle(Lx.apagado)
                }
                .accessibilityLabel(Text("Buscar de novo"))
                .padding(.top, 14).padding(.trailing, 20)
            }
        }
    }

    private var rede: some View {
        Cartao {
            ForEach(servidores.lista) { s in
                LinhaDeCartao(simbolo: "server.rack", titulo: s.nome, sub: s.subtitulo) {
                    nav.abrir(.pasta(.smb(servidor: s.id, share: "", caminho: "")))
                }
            }
            let salvos = Set(servidores.lista.map { Descobertas.semInterface($0.host) })
            ForEach(descobertas.achados.filter { !salvos.contains($0.host) }) { a in
                LinhaDeCartao(simbolo: "desktopcomputer", titulo: a.nome,
                              sub: a.nome != a.host ? String(localized: "\(a.host) · encontrado na rede") : String(localized: "Encontrado na rede"),
                              cor: Lx.verde) {
                    rascunho = RascunhoDeServidor(nome: a.nome, host: a.host)
                }
            }
            if servidores.lista.isEmpty && descobertas.achados.isEmpty {
                Text(descobertas.buscando ? String(localized: "Procurando servidores SMB na rede…") : String(localized: "Nenhum servidor encontrado"))
                    .font(LxFonte.f(14)).foregroundStyle(Lx.tenue)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 20).padding(.vertical, 12)
            }
            LinhaDeCartao(simbolo: "plus", titulo: String(localized: "Servidores de rede"),
                          sub: String(localized: "Procurar e adicionar servidor SMB")) { nav.abrir(.rede) }
        }
    }
}
