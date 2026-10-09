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
