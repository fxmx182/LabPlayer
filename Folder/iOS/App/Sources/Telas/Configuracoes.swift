import SwiftUI

/// Configurações. No Android do celular elas são quase só a versão (a pasta
/// segura e o Pro ficaram de fora da loja); aqui entram as pastas autorizadas
/// — o que no iOS faz as vezes da permissão de acesso aos arquivos — e as
/// licenças.
struct Configuracoes: View {
    @EnvironmentObject private var lugares: Lugares
    @State private var remover: Lugar?
    @State private var licenca: Licenca?
    @State private var escolhendo = false
    @State private var erro: String?

    struct Licenca: Identifiable {
        let id: String
        let titulo: String
        let arquivo: String
    }

    private let licencas = [
        Licenca(id: "smb", titulo: "SMBClient — MIT", arquivo: "MIT"),
        Licenca(id: "manrope", titulo: "Manrope — SIL OFL 1.1", arquivo: "OFL-1.1"),
    ]

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                TituloDeSecao(texto: "Pastas autorizadas")
                Cartao {
                    if lugares.autorizados.isEmpty {
                        Text("Nenhuma pasta autorizada ainda.")
                            .font(LxFonte.f(14)).foregroundStyle(Lx.tenue)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 20).padding(.vertical, 12)
                    }
                    ForEach(lugares.autorizados) { l in
                        HStack(spacing: 0) {
                            LinhaDeCartao(simbolo: lugares.tipo(raiz: l.id).simbolo, titulo: l.nome,
                                          sub: lugares.indisponiveis.contains(l.id)
                                            ? String(localized: "Indisponível — toque para autorizar de novo")
                                            : lugares.tipo(raiz: l.id).descricao) {
                                if lugares.indisponiveis.contains(l.id) { escolhendo = true }
                            }
                            Button { remover = l } label: {
                                Image(systemName: "minus.circle.fill").font(.system(size: 20)).foregroundStyle(Lx.vermelho)
                            }
                            .padding(.trailing, 18)
                            .accessibilityLabel(Text("Remover acesso"))
                        }
                    }
                    LinhaDeCartao(simbolo: "plus", titulo: String(localized: "Adicionar pasta"),
                                  sub: String(localized: "iCloud Drive, No iPhone, pendrive ou outro app"), cor: Lx.verde) {
                        escolhendo = true
                    }
                }
                Text("O iOS não tem a permissão de \"todos os arquivos\" do Android: cada app só vê a própria pasta e as que a pessoa escolhe no seletor do sistema. Cada pasta escolhida vale com todas as subpastas, e o acesso fica salvo até você removê-lo aqui. Remover o acesso não apaga nada.")
                    .font(LxFonte.f(12)).foregroundStyle(Lx.tenue)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 26).padding(.top, 10)

                TituloDeSecao(texto: "Sobre")
                Cartao {
                    HStack(spacing: 16) {
                        Image("Marca").resizable().scaledToFit().frame(width: 42, height: 42)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("LibertyX Folder \(AppBuild.versao)").font(LxFonte.f(16, .semibold)).foregroundStyle(Lx.texto)
                            Text("Build \(AppBuild.commit)").font(LxFonte.f(12)).foregroundStyle(Lx.tenue)
                        }
                        Spacer()
                    }
                    .padding(.horizontal, 20).padding(.vertical, 12)
                    ForEach(licencas) { l in
                        LinhaDeCartao(simbolo: "doc.plaintext", titulo: l.titulo, sub: String(localized: "Licença de código aberto")) {
                            licenca = l
                        }
                    }
                }
            }
            .frame(maxWidth: Escala.larguraMaxima)
            .frame(maxWidth: .infinity)
            .padding(.bottom, 60)
        }
        .background(BrilhoDeFundo())
        .navigationTitle("Configurações")
        .navigationBarTitleDisplayMode(.inline)
        .fileImporter(isPresented: $escolhendo, allowedContentTypes: [.folder]) { r in
            switch r {
            case .success(let u): do { try lugares.adicionar(u) } catch { erro = error.localizedDescription }
            case .failure(let e): erro = e.localizedDescription
            }
        }
        .confirmationDialog(remover.map { String(localized: "Remover o acesso a \($0.nome)?") } ?? "",
                            isPresented: Binding(get: { remover != nil }, set: { if !$0 { remover = nil } }),
                            titleVisibility: .visible) {
            Button("Remover acesso", role: .destructive) { if let r = remover { lugares.remover(r) }; remover = nil }
        } message: {
            Text("Os arquivos continuam onde estão; o app só deixa de ver esta pasta.")
        }
        .sheet(item: $licenca) { l in
            NavigationStack {
                ScrollView {
                    Text(texto(l.arquivo)).font(.system(size: 12, design: .monospaced)).foregroundStyle(Lx.apagado)
                        .frame(maxWidth: .infinity, alignment: .leading).padding(16)
                        .textSelection(.enabled)
                }
                .background(Lx.superficie)
                .navigationTitle(l.titulo)
                .navigationBarTitleDisplayMode(.inline)
            }
        }
        .alert("Não foi possível concluir", isPresented: Binding(get: { erro != nil }, set: { if !$0 { erro = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(erro ?? "") }
    }

    private func texto(_ nome: String) -> String {
        guard let u = Bundle.main.url(forResource: nome, withExtension: "txt"),
              let t = try? String(contentsOf: u, encoding: .utf8) else { return "" }
        return t
    }
}
