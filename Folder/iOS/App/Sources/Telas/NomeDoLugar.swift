import SwiftUI

/// O nome de uma pasta autorizada, pedido ao adicioná-la e mudado depois pelo
/// toque longo (início) ou pelo lápis (Configurações).
///
/// Várias pastas chegam com o mesmo nome — a pasta de cada app no iPhone se
/// chama "Documents" por dentro —, e só a pessoa sabe de onde é cada uma. O
/// nome é só do app: a pasta no disco continua com o dela.
struct PedirNomeDoLugar: ViewModifier {
    @Binding var lugar: Lugar?
    @EnvironmentObject private var lugares: Lugares
    @State private var texto = ""

    func body(content: Content) -> some View {
        content
            .alert("Nome da pasta", isPresented: Binding(get: { lugar != nil }, set: { if !$0 { lugar = nil } })) {
                TextField("Nome", text: $texto)
                Button("Salvar") {
                    if let l = lugar { lugares.renomear(l, texto) }
                    lugar = nil
                }
                .disabled(texto.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                Button("Cancelar", role: .cancel) { lugar = nil }
            } message: {
                Text("Um nome para reconhecer de onde é esta pasta. Ele só muda aqui no app; a pasta continua com o nome dela.")
            }
            .onChange(of: lugar) { _, l in if let l { texto = l.nome } }
    }
}

extension View {
    func pedirNomeDoLugar(_ lugar: Binding<Lugar?>) -> some View {
        modifier(PedirNomeDoLugar(lugar: lugar))
    }
}

/// Os nomes de várias pastas recém-adicionadas, numa tela só: marcadas de uma
/// vez no seletor, as pastas dos apps chegam quase todas como "Documents",
/// e um alerta por pasta seria uma fila de pop-ups.
struct NomesDasPastas: View {
    let pastas: [Lugar]
    @EnvironmentObject private var lugares: Lugares
    @Environment(\.dismiss) private var fechar
    @State private var nomes: [String: String] = [:]

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    ForEach(pastas) { l in
                        HStack(spacing: 12) {
                            Image(systemName: lugares.tipo(raiz: l.id).simbolo).foregroundStyle(Lx.ouro).frame(width: 24)
                            TextField("Nome", text: Binding(get: { nomes[l.id] ?? l.nome }, set: { nomes[l.id] = $0 }))
                                .autocorrectionDisabled()
                        }
                    }
                } footer: {
                    Text("Um nome para reconhecer de onde é cada pasta. Ele só muda aqui no app; a pasta continua com o nome dela.")
                }
            }
            .scrollContentBackground(.hidden)
            .background(Lx.superficie)
            .navigationTitle(Plural.pastas(pastas.count))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Salvar") {
                        for l in pastas { if let n = nomes[l.id] { lugares.renomear(l, n) } }
                        fechar()
                    }
                }
            }
        }
    }
}

struct GrupoDePastas: Identifiable {
    let id = UUID()
    let pastas: [Lugar]
}
