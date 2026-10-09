import SwiftUI
import UniformTypeIdentifiers

/// A primeira abertura: escolher a pasta padrão.
///
/// No lugar da pasta interna do app ("No iPhone › LibertyX Folder"), que o
/// iOS cria sozinho e o dono pediu para tirar: a pessoa escolhe qual pasta é a
/// casa do app — uma do iCloud Drive, de "No iPhone", de um pendrive. Ela vem
/// primeiro no início, recebe o que chega de outros apps, e o app varre todas
/// as subpastas dela para as categorias e a pesquisa. Dá para trocar depois em
/// Configurações.
struct ConfiguracaoInicial: View {
    let concluir: () -> Void

    @EnvironmentObject private var lugares: Lugares
    @ObservedObject private var progresso = ProgressoDoIndice.shared
    @State private var escolhendo = false
    @State private var escolhida: Lugar?
    @State private var nome = ""
    @State private var erro: String?

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                Image("Marca").resizable().scaledToFit().frame(width: 88, height: 88)
                    .padding(.top, 56)
                Text("Escolha a pasta padrão")
                    .font(LxFonte.f(26, .heavy)).tracking(-0.5).foregroundStyle(Lx.texto)
                    .multilineTextAlignment(.center)
                    .padding(.top, 22)
                Text("Ela vem primeiro na tela inicial, recebe o que você mandar de outros apps, e o LibertyX Folder varre todas as subpastas dela para montar as categorias e a pesquisa. Pode ser uma pasta do iCloud Drive, de No iPhone ou de um pendrive. Dá para trocar depois em Configurações.")
                    .font(LxFonte.f(15)).foregroundStyle(Lx.apagado)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 12).padding(.horizontal, 8)

                if let l = escolhida {
                    escolhidaView(l)
                } else {
                    Button { escolhendo = true } label: {
                        Text("Escolher pasta").font(LxFonte.f(17, .bold)).foregroundStyle(Color(hex: 0x1A1200))
                            .frame(maxWidth: .infinity).padding(.vertical, 15)
                            .background(Capsule().fill(Lx.ouro))
                    }
                    .padding(.top, 32)
                    Button("Agora não") { concluir() }
                        .font(LxFonte.f(15, .semibold)).foregroundStyle(Lx.apagado)
                        .padding(.top, 14)
                }
                if let erro {
                    Text(erro).font(LxFonte.f(13)).foregroundStyle(Lx.vermelho)
                        .multilineTextAlignment(.center).padding(.top, 16)
                }
            }
            .padding(.horizontal, 28)
            .frame(maxWidth: 520)
            .frame(maxWidth: .infinity)
            .padding(.bottom, 40)
        }
        .background(BrilhoDeFundo())
        .fileImporter(isPresented: $escolhendo, allowedContentTypes: [.folder]) { r in
            switch r {
            case .success(let url):
                do {
                    // Adicionar já começa a varredura, em segundo plano,
                    // enquanto a pessoa dá o nome.
                    let l = try lugares.adicionar(url)
                    escolhida = l
                    nome = l.nome
                    erro = nil
                } catch { erro = error.localizedDescription }
            case .failure(let e):
                erro = e.localizedDescription
            }
        }
    }

    private func escolhidaView(_ l: Lugar) -> some View {
        VStack(spacing: 14) {
            cartao(l).padding(.top, 28).padding(.bottom, 14)
            Button {
                lugares.renomear(l, nome)
                lugares.definirPadrao(lugares.lugar(l.id) ?? l)
                concluir()
            } label: {
                Text("Começar").font(LxFonte.f(17, .bold)).foregroundStyle(Color(hex: 0x1A1200))
                    .frame(maxWidth: .infinity).padding(.vertical, 15)
                    .background(Capsule().fill(Lx.ouro))
            }
            .disabled(nome.trimmingCharacters(in: .whitespaces).isEmpty)
            Button("Escolher outra") { escolhendo = true }
                .font(LxFonte.f(15, .semibold)).foregroundStyle(Lx.apagado)
        }
    }

    private func cartao(_ l: Lugar) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 14) {
                Selo(cor: Lx.ouro, simbolo: lugares.tipo(raiz: l.id).simbolo)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Nome da pasta").font(LxFonte.f(12, .semibold)).foregroundStyle(Lx.tenue)
                    TextField("Nome", text: $nome)
                        .font(LxFonte.f(17, .semibold)).foregroundStyle(Lx.texto)
                        .submitLabel(.done)
                }
            }
            Text(lugares.tipo(raiz: l.id).descricao).font(LxFonte.f(12)).foregroundStyle(Lx.tenue)
            if progresso.varrendo {
                HStack(spacing: 8) {
                    ProgressView().tint(Lx.ouro).scaleEffect(0.8)
                    Text(String(localized: "Varrendo subpastas…  \(progresso.itens.formatted()) itens"))
                        .font(LxFonte.f(12)).foregroundStyle(Lx.apagado)
                }
            }
        }
        .padding(18)
        .vidro(cor: Lx.ouro.opacity(0.06), borda: Lx.ouro.opacity(0.30))
    }
}
