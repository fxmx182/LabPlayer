import SwiftUI

/// Uma linha da lista. Toque abre (ou marca, selecionando); toque longo
/// seleciona — como no Android e no Meus Arquivos da Samsung.
struct LinhaDeArquivo: View {
    let e: FileEntry
    let selecionando: Bool
    let selecionado: Bool
    var subtitulo: String? = nil
    let aoTocar: () -> Void
    let aoSegurar: () -> Void

    var body: some View {
        HStack(spacing: 14) {
            if selecionando {
                Image(systemName: selecionado ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 22))
                    .foregroundStyle(selecionado ? Lx.ouro : Lx.tenue)
            }
            Miniatura(e: e, lado: 44)
            VStack(alignment: .leading, spacing: 2) {
                Text(e.nome).font(LxFonte.f(16, .medium)).foregroundStyle(Lx.texto).lineLimit(2)
                let sub = subtitulo ?? [Fmt.data(e.modificado), e.isDir ? "" : Fmt.tamanho(e.tamanho)]
                    .filter { !$0.isEmpty }.joined(separator: "  ·  ")
                if !sub.isEmpty {
                    Text(sub).font(LxFonte.f(12)).foregroundStyle(Lx.tenue).lineLimit(1)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 12).padding(.vertical, 9)
        .background {
            if selecionado {
                RoundedRectangle(cornerRadius: Lx.raioPequeno, style: .continuous).fill(Lx.ouro.opacity(0.12))
                    .overlay(RoundedRectangle(cornerRadius: Lx.raioPequeno, style: .continuous)
                        .strokeBorder(Lx.ouro.opacity(0.35), lineWidth: 0.5))
            }
        }
        .contentShape(Rectangle())
        .onTapGesture(perform: aoTocar)
        .onLongPressGesture(minimumDuration: 0.4) {
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            aoSegurar()
        }
        .padding(.horizontal, 10).padding(.vertical, 2)
    }
}

/// Uma célula da grade.
struct CelulaDeArquivo: View {
    let e: FileEntry
    let selecionando: Bool
    let selecionado: Bool
    let aoTocar: () -> Void
    let aoSegurar: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            Miniatura(e: e, raio: 14, preencher: true)
                .overlay(alignment: .topLeading) {
                    if selecionando {
                        Image(systemName: selecionado ? "checkmark.circle.fill" : "circle")
                            .font(.system(size: 20))
                            .foregroundStyle(selecionado ? Lx.ouro : .white)
                            .background(Circle().fill(Color.black.opacity(0.35)))
                            .padding(6)
                    }
                }
            Text(e.nome).font(LxFonte.f(12)).foregroundStyle(Lx.texto).lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 2)
        }
        .padding(6)
        .background {
            if selecionado {
                RoundedRectangle(cornerRadius: Lx.raioPequeno, style: .continuous).fill(Lx.ouro.opacity(0.12))
                    .overlay(RoundedRectangle(cornerRadius: Lx.raioPequeno, style: .continuous)
                        .strokeBorder(Lx.ouro.opacity(0.35), lineWidth: 0.5))
            }
        }
        .contentShape(Rectangle())
        .onTapGesture(perform: aoTocar)
        .onLongPressGesture(minimumDuration: 0.4) {
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            aoSegurar()
        }
    }
}

/// A trilha de navegação: volta a qualquer pasta do caminho num toque.
struct Trilha: View {
    let loc: Loc
    @EnvironmentObject private var nav: Navegacao
    @EnvironmentObject private var lugares: Lugares
    @EnvironmentObject private var servidores: Servidores

    var body: some View {
        let cadeia = Array(sequence(first: loc, next: { $0.pai })).reversed()
        ScrollViewReader { leitor in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 3) {
                    ForEach(Array(cadeia.enumerated()), id: \.offset) { i, l in
                        let ultimo = i == cadeia.count - 1
                        Button { if !ultimo { nav.pular(l) } } label: {
                            Text(nome(l))
                                .font(LxFonte.f(13, ultimo ? .semibold : .medium))
                                .foregroundStyle(ultimo ? Lx.ouro : Lx.apagado)
                                .lineLimit(1)
                                .padding(.horizontal, 10).padding(.vertical, 5)
                                .vidro(12, cor: ultimo ? Lx.ouro.opacity(0.10) : Lx.vidro,
                                       borda: ultimo ? Lx.ouro.opacity(0.28) : Lx.vidroBorda)
                        }
                        .buttonStyle(.plain)
                        .id(i)
                        if !ultimo {
                            Image(systemName: "chevron.right").font(.system(size: 10, weight: .semibold)).foregroundStyle(Lx.tenue)
                        }
                    }
                }
                .padding(.horizontal, 14).padding(.vertical, 4)
            }
            .onAppear { leitor.scrollTo(cadeia.count - 1, anchor: .trailing) }
            .onChange(of: loc) { _, _ in leitor.scrollTo(cadeia.count - 1, anchor: .trailing) }
            .padding(.bottom, 4)
            // A trilha fica presa no topo e a lista rola por baixo dela: sem
            // fundo, os nomes dos arquivos apareciam através dos botões. O
            // mesmo vidro fosco da barra de navegação ao rolar, com o fio de
            // baixo marcando onde a lista começa.
            .background {
                ZStack {
                    Rectangle().fill(.ultraThinMaterial)
                    Lx.fundo.opacity(0.55)
                }
                .environment(\.colorScheme, .dark)
                .ignoresSafeArea(edges: [.horizontal, .top])
            }
            .overlay(alignment: .bottom) { Rectangle().fill(Lx.vidroBorda).frame(height: 0.5) }
        }
    }

    private func nome(_ l: Loc) -> String {
        switch l {
        case .local(let r, let c): return c.isEmpty ? lugares.nome(raiz: r) : l.nome
        case .smb(let id, let s, _): return s.isEmpty ? (servidores.porId(id)?.nome ?? String(localized: "Servidor")) : l.nome
        }
    }
}

struct AcaoDeBarra: View {
    let simbolo: String
    let titulo: String
    var habilitada = true
    var body: some View {
        VStack(spacing: 3) {
            Image(systemName: simbolo).font(.system(size: 19))
            Text(titulo).font(LxFonte.f(11))
        }
        .foregroundStyle(habilitada ? Lx.texto : Lx.tenue)
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
        .contentShape(Rectangle())
    }
}

/// A barra de baixo enquanto há itens marcados.
struct BarraDeSelecao: View {
    let itens: [FileEntry]
    let mover: () -> Void
    let copiar: () -> Void
    let compartilhar: () -> Void
    let excluir: () -> Void
    let renomear: () -> Void
    let abrirCom: () -> Void
    let galeria: () -> Void
    let detalhes: () -> Void

    var body: some View {
        let temShare = itens.contains { $0.ehShare }
        let temArquivo = itens.contains { !$0.isDir }
        let temMidia = itens.contains { [.imagem, .video].contains(Mime.tipo($0)) }
        HStack(spacing: 0) {
            Button(action: mover) { AcaoDeBarra(simbolo: "folder", titulo: String(localized: "Mover"), habilitada: !temShare) }
                .disabled(temShare)
            Button(action: copiar) { AcaoDeBarra(simbolo: "doc.on.doc", titulo: String(localized: "Copiar")) }
            Button(action: compartilhar) { AcaoDeBarra(simbolo: "square.and.arrow.up", titulo: String(localized: "Compartilhar"), habilitada: temArquivo) }
                .disabled(!temArquivo)
            Button(action: excluir) { AcaoDeBarra(simbolo: "trash", titulo: String(localized: "Excluir"), habilitada: !temShare) }
                .disabled(temShare)
            Menu {
                Button(action: renomear) { Label("Renomear", systemImage: "pencil") }
                    .disabled(itens.count != 1 || temShare)
                Button(action: abrirCom) { Label("Abrir com", systemImage: "arrow.up.forward.app") }
                    .disabled(itens.count != 1 || itens.first?.isDir != false)
                if temMidia {
                    Button(action: galeria) { Label("Salvar na galeria", systemImage: "photo.on.rectangle") }
                }
                Button(action: detalhes) { Label("Detalhes", systemImage: "info.circle") }
            } label: {
                AcaoDeBarra(simbolo: "ellipsis", titulo: String(localized: "Mais"))
            }
        }
        .buttonStyle(.plain)
        .padding(.horizontal, 6)
        .background(Lx.superficie.ignoresSafeArea(edges: .bottom))
        .overlay(alignment: .top) { Rectangle().fill(Lx.vidroBorda).frame(height: 0.5) }
    }
}

/// "Mover 3 itens — abra a pasta de destino": o meio do caminho entre
/// escolher os itens e escolher onde colar.
struct BarraDeColar: View {
    let clip: Clip
    let habilitado: Bool
    let cancelar: () -> Void
    let colar: (() -> Void)?

    var body: some View {
        VStack(spacing: 0) {
            Text(Plural.colar(clip.itens.count, mover: clip.mover))
                .font(LxFonte.f(13)).foregroundStyle(Lx.apagado)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 20).padding(.top, 12)
            HStack {
                Button(action: cancelar) {
                    Text("Cancelar").font(LxFonte.f(15, .semibold)).frame(maxWidth: .infinity).padding(.vertical, 10)
                }
                Rectangle().fill(Lx.vidroBorda).frame(width: 0.5, height: 24)
                Button { colar?() } label: {
                    Text(clip.mover ? "Mover para cá" : "Copiar para cá")
                        .font(LxFonte.f(15, .bold))
                        .foregroundStyle(habilitado && colar != nil ? Lx.ouro : Lx.tenue)
                        .frame(maxWidth: .infinity).padding(.vertical, 10)
                }
                .disabled(!habilitado || colar == nil)
            }
            .padding(8)
        }
        .background(Lx.superficie.ignoresSafeArea(edges: .bottom))
        .overlay(alignment: .top) { Rectangle().fill(Lx.vidroBorda).frame(height: 0.5) }
    }
}

/// O progresso da cópia/movimentação/exclusão, por cima de qualquer tela.
/// Quando termina bem, **some sozinho** em 3,5 s, como no Android: um
/// "Concluído" não tem nada a decidir. Erro ou cancelamento ficam até o OK.
struct PainelDeOperacao: View {
    @EnvironmentObject private var ops: Ops
    @ObservedObject private var painel = PainelDaOperacao.shared

    var body: some View {
        ZStack {
            conteudo
        }
        .animation(.easeOut(duration: 0.2), value: painel.estado == nil)
    }

    @ViewBuilder private var conteudo: some View {
        if let op = painel.estado {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .center) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(!op.terminou ? op.titulo + "…" : (op.erro != nil ? String(localized: "Falhou") : String(localized: "Concluído")))
                            .font(LxFonte.f(15, .semibold)).foregroundStyle(Lx.texto)
                        Text(detalhe(op))
                            .font(LxFonte.f(12))
                            .foregroundStyle(op.erro != nil ? Lx.vermelho : Lx.apagado)
                            .lineLimit(3)
                    }
                    Spacer(minLength: 8)
                    if op.terminou {
                        Button("OK") { ops.dispensar() }.font(LxFonte.f(15, .bold))
                    } else {
                        Button("Cancelar") { ops.cancelar() }.font(LxFonte.f(15, .semibold))
                    }
                }
                if !op.terminou {
                    if op.total > 0 {
                        ProgressView(value: op.fracao).tint(Lx.ouro)
                    } else {
                        ProgressView().progressViewStyle(.linear).tint(Lx.ouro)
                    }
                }
            }
            .padding(.horizontal, 20).padding(.vertical, 14)
            .background(RoundedRectangle(cornerRadius: Lx.raioCartao, style: .continuous).fill(Lx.superficie))
            .vidro(Lx.raioCartao, cor: Lx.vidroForte)
            .frame(maxWidth: Escala.larguraMaxima)
            .padding(12)
            .transition(.move(edge: .bottom).combined(with: .opacity))
            .task(id: op.terminou) {
                guard op.terminou, op.erro == nil else { return }
                try? await Task.sleep(nanoseconds: 3_500_000_000)
                if !Task.isCancelled { withAnimation { ops.dispensar() } }
            }
        }
    }

    private func detalhe(_ op: OpEstado) -> String {
        if let e = op.erro { return e }
        var s = ""
        if op.itensTotal > 0 {
            let atual = min(op.itensFeitos + (op.terminou ? 0 : 1), op.itensTotal)
            s += String(localized: "\(atual) de \(op.itensTotal)")
        }
        if op.total > 0 { s += "  ·  " + String(localized: "\(Fmt.tamanho(op.feitos)) de \(Fmt.tamanho(op.total))") }
        if !op.terminou, !op.atual.isEmpty { s += "\n" + op.atual }
        return s
    }
}

/// "Baixando…" do servidor ou do iCloud antes de abrir, com Cancelar.
struct AvisoDePreparo: View {
    @EnvironmentObject private var abridor: Abridor
    @ObservedObject private var painel = PainelDePreparo.shared

    var body: some View {
        if let p = painel.preparo {
            ZStack {
                Color.black.opacity(0.45).ignoresSafeArea()
                VStack(spacing: 14) {
                    Text("Baixando…").font(LxFonte.f(16, .semibold)).foregroundStyle(Lx.texto)
                    Text(p.nome).font(LxFonte.f(13)).foregroundStyle(Lx.apagado).lineLimit(2).multilineTextAlignment(.center)
                    if let f = p.fracao {
                        ProgressView(value: f).tint(Lx.ouro)
                    } else {
                        ProgressView().tint(Lx.ouro)
                    }
                    Button("Cancelar") { abridor.cancelar() }.font(LxFonte.f(15, .semibold))
                }
                .padding(24)
                .frame(maxWidth: 320)
                .background(RoundedRectangle(cornerRadius: Lx.raioCartao, style: .continuous).fill(Lx.superficie))
                .vidro(Lx.raioCartao, cor: Lx.vidroForte)
            }
        }
    }
}

/// O "Toast" do rodapé.
struct AvisoCurto: View {
    @EnvironmentObject private var avisos: Avisos
    var body: some View {
        if let t = avisos.texto {
            Text(t)
                .font(LxFonte.f(14, .medium)).foregroundStyle(Lx.texto)
                .padding(.horizontal, 18).padding(.vertical, 12)
                .background(Capsule().fill(Lx.superficie))
                .overlay(Capsule().strokeBorder(Lx.vidroBorda, lineWidth: 0.5))
                .padding(.bottom, 90)
                .transition(.opacity)
        }
    }
}
