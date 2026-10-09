import SwiftUI
import SMBClient

struct RascunhoDeServidor: Identifiable {
    let id = UUID()
    /// O servidor sendo editado; nulo = novo.
    var existente: UUID? = nil
    var nome = ""
    var host = ""
    var porta = 445
    var usuario = ""
    var senha = ""
    var dominio = ""
    var convidado = false
}

/// Servidores salvos, os achados na rede e o cadastro manual.
struct TelaDeRede: View {
    @EnvironmentObject private var nav: Navegacao
    @EnvironmentObject private var servidores: Servidores
    @EnvironmentObject private var descobertas: Descobertas
    @State private var rascunho: RascunhoDeServidor?
    @State private var remover: SMBServer?

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                if !servidores.lista.isEmpty {
                    TituloDeSecao(texto: "Salvos")
                    Cartao {
                        ForEach(servidores.lista) { s in
                            LinhaDeCartao(simbolo: "server.rack", titulo: s.nome, sub: s.subtitulo) {
                                nav.abrir(.pasta(.smb(servidor: s.id, share: "", caminho: "")))
                            }
                            .contextMenu {
                                Button { editar(s) } label: { Label("Editar", systemImage: "pencil") }
                                Button(role: .destructive) { remover = s } label: { Label("Remover", systemImage: "trash") }
                            }
                        }
                    }
                    Text("Toque e segure um servidor para editar ou remover.")
                        .font(LxFonte.f(12)).foregroundStyle(Lx.tenue)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 26).padding(.top, 8)
                }
                TituloDeSecao(texto: "Encontrados nesta rede")
                Cartao {
                    let salvos = Set(servidores.lista.map(\.host))
                    ForEach(descobertas.achados) { a in
                        let ja = salvos.contains(a.host)
                        LinhaDeCartao(simbolo: "desktopcomputer", titulo: a.nome,
                                      sub: (a.nome != a.host ? a.host : "SMB") + (ja ? String(localized: " · já salvo") : ""),
                                      cor: ja ? Lx.ouro : Lx.verde) {
                            if let s = servidores.lista.first(where: { $0.host == a.host }) {
                                nav.abrir(.pasta(.smb(servidor: s.id, share: "", caminho: "")))
                            } else {
                                rascunho = RascunhoDeServidor(nome: a.nome, host: a.host)
                            }
                        }
                    }
                    if descobertas.achados.isEmpty {
                        Text(descobertas.buscando ? String(localized: "Procurando computadores e NAS com compartilhamento…")
                                                  : String(localized: "Nada encontrado. Confira se está no mesmo Wi-Fi que o servidor, ou adicione pelo IP."))
                            .font(LxFonte.f(14)).foregroundStyle(Lx.apagado)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 20).padding(.vertical, 14)
                    }
                }
                Cartao {
                    LinhaDeCartao(simbolo: "plus", titulo: String(localized: "Adicionar manualmente"),
                                  sub: String(localized: "Pelo IP ou nome do servidor")) { rascunho = RascunhoDeServidor() }
                }
                .padding(.top, 16)
            }
            .frame(maxWidth: Escala.larguraMaxima)
            .frame(maxWidth: .infinity)
            .padding(.bottom, 100)
        }
        .background(BrilhoDeFundo())
        .navigationTitle("Servidores de rede")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                if descobertas.buscando {
                    ProgressView().tint(Lx.ouro)
                } else {
                    Button { descobertas.buscar() } label: { Image(systemName: "arrow.clockwise") }
                        .accessibilityLabel(Text("Buscar"))
                }
            }
        }
        .task { descobertas.buscar() }
        .sheet(item: $rascunho) { r in
            FormularioDeServidor(inicial: r) { s in
                rascunho = nil
                if r.existente == nil { nav.abrir(.pasta(.smb(servidor: s.id, share: "", caminho: ""))) }
            }
        }
        .confirmationDialog(remover.map { String(localized: "Remover \($0.nome)?") } ?? "",
                            isPresented: Binding(get: { remover != nil }, set: { if !$0 { remover = nil } }),
                            titleVisibility: .visible) {
            Button("Remover", role: .destructive) { if let s = remover { servidores.remover(s) }; remover = nil }
        } message: {
            Text("O servidor sai da lista e a senha é apagada do aparelho. Nada é apagado no servidor.")
        }
    }

    private func editar(_ s: SMBServer) {
        rascunho = RascunhoDeServidor(existente: s.id, nome: s.nome, host: s.host, porta: s.porta, usuario: s.usuario,
                                      senha: Chaveiro.ler(s.id) ?? "", dominio: s.dominio, convidado: s.convidado)
    }
}

/// Cadastro de servidor: só salva depois de conseguir conectar e listar os
/// compartilhamentos — a prova de verdade, e não só autenticar.
struct FormularioDeServidor: View {
    let inicial: RascunhoDeServidor
    let aoSalvar: (SMBServer) -> Void

    @EnvironmentObject private var servidores: Servidores
    @Environment(\.dismiss) private var fechar
    @State private var d = RascunhoDeServidor()
    @State private var verSenha = false
    @State private var avancado = false
    @State private var testando = false
    @State private var erro: String?
    @State private var pronto = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Endereço (IP ou nome)", text: $d.host)
                        .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                    TextField("Nome de exibição (opcional)", text: $d.nome)
                    Toggle("Entrar como convidado", isOn: $d.convidado).tint(Lx.ouro)
                }
                if !d.convidado {
                    Section {
                        TextField("Usuário", text: $d.usuario)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                        HStack {
                            Group {
                                if verSenha { TextField("Senha", text: $d.senha) } else { SecureField("Senha", text: $d.senha) }
                            }
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                            Button { verSenha.toggle() } label: {
                                Image(systemName: verSenha ? "eye.slash" : "eye").foregroundStyle(Lx.apagado)
                            }
                            .buttonStyle(.plain)
                        }
                    } footer: {
                        Text("A senha fica no Keychain do aparelho, não junto das outras configurações.")
                    }
                }
                Section {
                    DisclosureGroup("Avançado", isExpanded: $avancado) {
                        TextField("Porta", value: $d.porta, format: .number.grouping(.never)).keyboardType(.numberPad)
                        if !d.convidado {
                            TextField("Domínio / grupo de trabalho", text: $d.dominio)
                                .textInputAutocapitalization(.never).autocorrectionDisabled()
                        }
                    }
                }
                if testando {
                    Section { HStack(spacing: 10) { ProgressView(); Text("Conectando…") } }
                }
                if let erro {
                    Section { Text(erro).foregroundStyle(Lx.vermelho) }
                }
            }
            .scrollContentBackground(.hidden)
            .background(Lx.superficie)
            .navigationTitle(inicial.existente == nil ? String(localized: "Servidor SMB") : String(localized: "Editar servidor"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { fechar() }.disabled(testando) }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Conectar") { conectar() }
                        .disabled(d.host.trimmingCharacters(in: .whitespaces).isEmpty || testando)
                }
            }
            .interactiveDismissDisabled(testando)
        }
        .onAppear {
            guard !pronto else { return }
            pronto = true
            d = inicial
            avancado = inicial.porta != 445 || !inicial.dominio.isEmpty
        }
    }

    private func conectar() {
        testando = true
        erro = nil
        let s = SMBServer(id: inicial.existente ?? UUID(),
                          nome: d.nome.trimmingCharacters(in: .whitespaces).isEmpty ? d.host.trimmingCharacters(in: .whitespaces) : d.nome,
                          host: d.host.trimmingCharacters(in: .whitespaces),
                          porta: d.porta,
                          usuario: d.convidado ? "" : d.usuario.trimmingCharacters(in: .whitespaces),
                          dominio: d.convidado ? "" : d.dominio.trimmingCharacters(in: .whitespaces),
                          convidado: d.convidado)
        let senha = d.senha
        Task {
            do {
                try await Self.testar(s, senha: senha)
                servidores.gravar(s, senha: s.convidado ? nil : senha)
                testando = false
                aoSalvar(s)
            } catch {
                testando = false
                erro = SmbErro.traduzir(error).localizedDescription
            }
        }
    }

    /// Uma conexão avulsa, fora do pool: o servidor ainda não está salvo.
    private static func testar(_ s: SMBServer, senha: String) async throws {
        let c = s.porta == 445 ? SMBClient(host: s.host) : SMBClient(host: s.host, port: s.porta)
        defer { c.session.disconnect() }
        try await comPrazo(15) {
            if s.convidado {
                try await c.login(username: nil, password: nil)
            } else {
                try await c.login(username: s.usuario, password: senha, domain: s.dominio.isEmpty ? nil : s.dominio)
            }
        }
        _ = try await comPrazo(15) { try await c.listShares() }
    }
}
