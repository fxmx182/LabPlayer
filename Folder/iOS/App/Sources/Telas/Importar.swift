import SwiftUI
import PhotosUI
import UniformTypeIdentifiers

/// O seletor de fotos do sistema. Não pede permissão nenhuma: a pessoa escolhe
/// e o app recebe só o escolhido — o jeito do iOS de trazer fotos e vídeos da
/// galeria para uma pasta (no Android eles já estão no armazenamento).
struct SeletorDeFotos: UIViewControllerRepresentable {
    let aoEscolher: ([NSItemProvider]) -> Void

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var c = PHPickerConfiguration()
        c.selectionLimit = 0
        // O arquivo original (HEIC, MOV), sem conversão para JPEG.
        c.preferredAssetRepresentationMode = .current
        let p = PHPickerViewController(configuration: c)
        p.delegate = context.coordinator
        return p
    }

    func updateUIViewController(_ c: PHPickerViewController, context: Context) {}
    func makeCoordinator() -> Coordenador { Coordenador(aoEscolher) }

    final class Coordenador: NSObject, PHPickerViewControllerDelegate {
        let aoEscolher: ([NSItemProvider]) -> Void
        init(_ f: @escaping ([NSItemProvider]) -> Void) { aoEscolher = f }
        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            aoEscolher(results.map(\.itemProvider))
        }
    }
}

/// Traz arquivos de fora para a raiz escondida `tmp`, de onde a operação de
/// copiar os leva ao destino.
enum Importacao {
    private static func pastaNova() throws -> (URL, String) {
        let sub = UUID().uuidString
        let u = Lugares.urlTmp.appendingPathComponent(sub, isDirectory: true)
        try FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return (u, sub)
    }

    private static func entrada(_ u: URL, sub: String) -> FileEntry? {
        LocalFs.entrada(u, em: .local(raiz: Lugares.tmp, caminho: sub))
    }

    /// Arquivos do seletor do sistema (`fileImporter`): cópia com o escopo de
    /// segurança aberto só durante a cópia.
    static func trazer(_ urls: [URL]) async throws -> [FileEntry] {
        let (pasta, sub) = try pastaNova()
        var r: [FileEntry] = []
        for u in urls {
            try Task.checkCancellation()
            let escopo = u.startAccessingSecurityScopedResource()
            defer { if escopo { u.stopAccessingSecurityScopedResource() } }
            try await LocalFs.garantirBaixado(u)
            let destino = pasta.appendingPathComponent(u.lastPathComponent)
            try FileManager.default.copyItem(at: u, to: destino)
            if let e = entrada(destino, sub: sub) { r.append(e) }
        }
        return r
    }

    /// Fotos e vídeos da galeria. O sistema entrega cada um num arquivo
    /// temporário que some quando a chamada volta — por isso a cópia é feita
    /// ali dentro, antes de retomar.
    static func trazer(_ provedores: [NSItemProvider]) async throws -> [FileEntry] {
        let (pasta, sub) = try pastaNova()
        var r: [FileEntry] = []
        for p in provedores {
            try Task.checkCancellation()
            let tipos = p.registeredTypeIdentifiers.compactMap { UTType($0) }
            guard let tipo = tipos.first(where: { $0.conforms(to: .movie) }) ?? tipos.first(where: { $0.conforms(to: .image) }) ?? tipos.first else { continue }
            let url: URL = try await withCheckedThrowingContinuation { cont in
                _ = p.loadFileRepresentation(forTypeIdentifier: tipo.identifier) { temp, erro in
                    guard let temp else { cont.resume(throwing: erro ?? ErroDeArquivo.naoAchado); return }
                    var nome = (p.suggestedName ?? temp.deletingPathExtension().lastPathComponent)
                    let ext = temp.pathExtension.isEmpty ? (tipo.preferredFilenameExtension ?? "") : temp.pathExtension
                    if !ext.isEmpty, (nome as NSString).pathExtension.isEmpty { nome += "." + ext }
                    let destino = pasta.appendingPathComponent(nome)
                    do {
                        try FileManager.default.copyItem(at: temp, to: destino)
                        cont.resume(returning: destino)
                    } catch {
                        // Duas fotos com o mesmo nome: a segunda ganha um número.
                        let outro = pasta.appendingPathComponent(String(UUID().uuidString.prefix(6)) + "-" + nome)
                        do { try FileManager.default.copyItem(at: temp, to: outro); cont.resume(returning: outro) }
                        catch { cont.resume(throwing: error) }
                    }
                }
            }
            if let e = entrada(url, sub: sub) { r.append(e) }
        }
        return r
    }
}

/// Tamanho, conteúdo, data, tipo e local — o `DetailsDialog` do Android.
struct Detalhes: View {
    let itens: [FileEntry]
    @EnvironmentObject private var lugares: Lugares
    @EnvironmentObject private var servidores: Servidores
    @Environment(\.dismiss) private var fechar
    @State private var tamanho: Int64?
    @State private var contagem = (arquivos: 0, pastas: 0)

    var body: some View {
        NavigationStack {
            List {
                linha("Tamanho", tamanho.map { t in Fmt.tamanho(t) + (t >= 1024 ? " " + String(localized: "(\(Fmt.bytes(t)) bytes)") : "") }
                                 ?? String(localized: "Calculando…"))
                if itens.contains(where: \.isDir), tamanho != nil {
                    let pastas = max(0, contagem.pastas - itens.filter(\.isDir).count)
                    linha("Contém", Plural.arquivos(contagem.arquivos) + ", " + Plural.pastas(pastas))
                }
                if let um = itens.first, itens.count == 1 {
                    if let d = um.modificado { linha("Modificado", Fmt.data(d)) }
                    if !um.isDir { linha("Tipo", Mime.descricao(um.nome)) }
                    linha("Local", local(um.loc))
                }
            }
            .scrollContentBackground(.hidden)
            .background(Lx.superficie)
            .navigationTitle(itens.count == 1 ? itens[0].nome : Plural.itens(itens.count))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("OK") { fechar() } } }
        }
        .presentationDetents([.medium, .large])
        .task {
            var c = (arquivos: 0, pastas: 0)
            var t: Int64 = 0
            for e in itens { t += (try? await Arquivos.tamanhoTotal(e, contagem: &c)) ?? 0 }
            contagem = c
            tamanho = t
        }
    }

    private func linha(_ k: LocalizedStringKey, _ v: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(k).font(LxFonte.f(12, .semibold)).foregroundStyle(Lx.tenue)
            Text(v).font(LxFonte.f(15)).foregroundStyle(Lx.texto).textSelection(.enabled)
        }
        .listRowBackground(Lx.vidro)
    }

    private func local(_ l: Loc) -> String {
        switch l {
        case .local(let r, let c):
            return lugares.nome(raiz: r) + (c.isEmpty ? "" : "/" + c)
        case .smb(let id, let s, let c):
            let h = servidores.porId(id)?.hostVisivel ?? "?"
            return "smb://\(h)/\(s)" + (c.isEmpty ? "" : "/" + c)
        }
    }
}
