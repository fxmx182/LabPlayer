import SwiftUI
import QuickLook
import AVKit
import Photos
import UniformTypeIdentifiers

/// Abrir, compartilhar e salvar na galeria — o `Open` do Android.
///
/// No Android o arquivo vai para outro app por uma URI (`FileProvider`,
/// `SmbProvider`), e o app escolhido lê direto. No iOS quem mostra é o próprio
/// app: o **QuickLook** (o visualizador do Arquivos — foto, vídeo, áudio, PDF,
/// Office, texto) e, para "Abrir com", a folha de compartilhar do sistema, que
/// lista os apps que aceitam o arquivo.
///
/// Do servidor, vídeo e áudio que a Apple decodifica tocam **sem baixar**: o
/// AVPlayer pede trechos por posição e nós lemos do SMB (`StreamSmb`). O resto
/// desce para uma pasta temporária com progresso, e então abre.
@MainActor
final class Abridor: ObservableObject {
    static let shared = Abridor()

    struct Preparo: Equatable {
        var nome: String
        var fracao: Double?
    }

    /// O aviso "Baixando…" por cima da tela, com Cancelar.
    @Published private(set) var preparo: Preparo?
    @Published var erro: String?

    private var tarefa: Task<Void, Never>?
    private var fonteQL: FonteQL?
    private var stream: StreamSmb?

    private init() {}

    func cancelar() { tarefa?.cancel() }

    // MARK: - Abrir

    func abrir(_ e: FileEntry, irmaos: [FileEntry] = []) {
        guard tarefa == nil else { return }
        if case .smb = e.loc, StreamSmb.toca(e.nome), let leitorURL = StreamSmb.criar(e) {
            tocar(leitorURL)
            return
        }
        tarefa = Task {
            defer { self.tarefa = nil; self.preparo = nil }
            do {
                switch e.loc {
                case .local:
                    // As fotos e vídeos ao lado viram páginas no visualizador:
                    // desliza-se de um para o outro, como na galeria.
                    let visiveis = irmaos.filter { !$0.isDir && !$0.naNuvem && $0.loc != e.loc }
                    let todos = ([e] + visiveis).sorted { a, b in
                        let ia = irmaos.firstIndex(of: a) ?? 0, ib = irmaos.firstIndex(of: b) ?? 0
                        return ia < ib
                    }
                    if e.naNuvem, let u = Raizes.url(de: e.loc) {
                        self.preparo = Preparo(nome: e.nome, fracao: nil)
                        try await LocalFs.garantirBaixado(u)
                    }
                    let urls = todos.compactMap { Raizes.url(de: $0.loc) }
                    let i = todos.firstIndex(of: e) ?? 0
                    self.mostrar(urls, indice: i)
                case .smb:
                    let u = try await self.baixar([e]).first
                    if let u { self.mostrar([u], indice: 0) }
                }
            } catch is CancellationError {
            } catch {
                self.erro = error.localizedDescription
            }
        }
    }

    private func mostrar(_ urls: [URL], indice: Int) {
        guard !urls.isEmpty, let topo = UIApplication.shared.topo else { return }
        let fonte = FonteQL(urls)
        fonteQL = fonte
        let ql = QLPreviewController()
        ql.dataSource = fonte
        ql.currentPreviewItemIndex = min(indice, urls.count - 1)
        topo.present(ql, animated: true)
    }

    private func tocar(_ s: StreamSmb) {
        guard let topo = UIApplication.shared.topo, let asset = s.asset() else { return }
        stream = s
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)
        let tela = TelaDoPlayer()
        tela.player = AVPlayer(playerItem: AVPlayerItem(asset: asset))
        tela.aoFechar = { [weak self] in
            self?.stream?.encerrar()
            self?.stream = nil
        }
        topo.present(tela, animated: true) { tela.player?.play() }
    }

    // MARK: - Compartilhar / Abrir com

    func compartilhar(_ itens: [FileEntry]) {
        let arquivos = itens.filter { !$0.isDir }
        guard !arquivos.isEmpty else { erro = String(localized: "Pastas não podem ser compartilhadas"); return }
        guard tarefa == nil else { return }
        tarefa = Task {
            defer { self.tarefa = nil; self.preparo = nil }
            do {
                let urls = try await self.materializar(arquivos)
                guard let topo = UIApplication.shared.topo, !urls.isEmpty else { return }
                let folha = UIActivityViewController(activityItems: urls, applicationActivities: nil)
                if let pop = folha.popoverPresentationController {
                    pop.sourceView = topo.view
                    pop.sourceRect = CGRect(x: topo.view.bounds.midX, y: topo.view.bounds.maxY - 80, width: 1, height: 1)
                }
                topo.present(folha, animated: true)
            } catch is CancellationError {
            } catch {
                self.erro = error.localizedDescription
            }
        }
    }

    // MARK: - Galeria

    func salvarNaGaleria(_ itens: [FileEntry]) {
        let midias = itens.filter { [.imagem, .video].contains(Mime.tipo($0)) }
        guard !midias.isEmpty, tarefa == nil else { return }
        tarefa = Task {
            defer { self.tarefa = nil; self.preparo = nil }
            do {
                let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
                guard status == .authorized || status == .limited else {
                    self.erro = String(localized: "Sem permissão para salvar na galeria. Libere em Ajustes › LibertyX Folder › Fotos.")
                    return
                }
                let urls = try await self.materializar(midias)
                try await PHPhotoLibrary.shared().performChanges {
                    for (u, e) in zip(urls, midias) {
                        let pedido = PHAssetCreationRequest.forAsset()
                        pedido.addResource(with: Mime.tipo(e) == .video ? .video : .photo, fileURL: u, options: nil)
                    }
                }
                self.erro = nil
                Avisos.shared.mostrar(String(localized: "Salvo na galeria"))
            } catch is CancellationError {
            } catch {
                self.erro = error.localizedDescription
            }
        }
    }

    // MARK: - Trazer para o aparelho

    /// URLs locais dos arquivos: os do aparelho como estão (baixando do iCloud
    /// se preciso), os do servidor numa pasta temporária.
    private func materializar(_ itens: [FileEntry]) async throws -> [URL] {
        var urls: [URL] = []
        var doServidor: [FileEntry] = []
        for e in itens {
            switch e.loc {
            case .local:
                guard let u = Raizes.url(de: e.loc) else { continue }
                if e.naNuvem {
                    preparo = Preparo(nome: e.nome, fracao: nil)
                    try await LocalFs.garantirBaixado(u)
                }
                urls.append(u)
            case .smb:
                doServidor.append(e)
            }
        }
        urls += try await baixar(doServidor)
        return urls
    }

    /// Baixa do servidor para `Caches/abertos`, que se limpa a cada abertura
    /// do app. Cada arquivo numa pasta própria, com o nome de verdade: é o
    /// nome que o QuickLook e a folha de compartilhar mostram.
    private func baixar(_ itens: [FileEntry]) async throws -> [URL] {
        guard !itens.isEmpty else { return [] }
        let total = max(itens.reduce(Int64(0)) { $0 + $1.tamanho }, 1)
        var feitos: Int64 = 0
        var urls: [URL] = []
        for e in itens {
            preparo = Preparo(nome: e.nome, fracao: Double(feitos) / Double(total))
            let pasta = Self.pastaDeAbertos.appendingPathComponent(UUID().uuidString, isDirectory: true)
            try FileManager.default.createDirectory(at: pasta, withIntermediateDirectories: true)
            let destino = pasta.appendingPathComponent(e.nome)
            FileManager.default.createFile(atPath: destino.path, contents: nil)
            let h = try FileHandle(forWritingTo: destino)
            let leitor = try await Arquivos.abrirLeitura(e.loc)
            do {
                while true {
                    try Task.checkCancellation()
                    let d = try await leitor.ler(1 << 20)
                    if d.isEmpty { break }
                    try h.write(contentsOf: d)
                    feitos += Int64(d.count)
                    preparo = Preparo(nome: e.nome, fracao: Double(feitos) / Double(total))
                }
                try h.close()
                await leitor.fechar()
            } catch {
                try? h.close()
                await leitor.fechar()
                try? FileManager.default.removeItem(at: pasta)
                throw error
            }
            urls.append(destino)
        }
        return urls
    }

    nonisolated static var pastaDeAbertos: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("abertos", isDirectory: true)
    }

    /// Na abertura do app: o que foi baixado só para ver não fica ocupando espaço.
    nonisolated static func limparAbertos() {
        try? FileManager.default.removeItem(at: pastaDeAbertos)
        try? FileManager.default.removeItem(at: Lugares.urlTmp)
    }
}

/// O QuickLook segura a fonte de dados como `weak`: ela mora no `Abridor`.
final class FonteQL: NSObject, QLPreviewControllerDataSource {
    let urls: [URL]
    init(_ urls: [URL]) { self.urls = urls }
    func numberOfPreviewItems(in controller: QLPreviewController) -> Int { urls.count }
    func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem {
        urls[index] as NSURL
    }
}

/// O player do sistema, avisando quando sai — é a hora de fechar a conexão.
final class TelaDoPlayer: AVPlayerViewController {
    var aoFechar: (() -> Void)?
    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        if isBeingDismissed || presentingViewController == nil {
            player?.pause()
            aoFechar?()
            aoFechar = nil
        }
    }
}

extension UIApplication {
    /// A tela do topo, para apresentar o QuickLook e a folha de compartilhar
    /// por cima do SwiftUI.
    var topo: UIViewController? {
        let cena = connectedScenes.compactMap { $0 as? UIWindowScene }.first { $0.activationState == .foregroundActive }
            ?? connectedScenes.compactMap { $0 as? UIWindowScene }.first
        var v = cena?.windows.first { $0.isKeyWindow }?.rootViewController
        while let p = v?.presentedViewController { v = p }
        return v
    }
}

/// Um aviso curto no rodapé ("Salvo na galeria") — o Toast do Android.
@MainActor
final class Avisos: ObservableObject {
    static let shared = Avisos()
    @Published private(set) var texto: String?
    private var tarefa: Task<Void, Never>?
    private init() {}

    func mostrar(_ t: String) {
        texto = t
        tarefa?.cancel()
        tarefa = Task {
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            if !Task.isCancelled { self.texto = nil }
        }
    }
}
