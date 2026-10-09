import AVFoundation
import UniformTypeIdentifiers

/// Faz o AVPlayer tocar um arquivo do servidor SMB sem baixá-lo antes — o
/// "assistir sem baixar" do Android, que lá é o `SmbProvider`.
///
/// O AVFoundation não fala SMB. Mas, dada uma URL de esquema que o sistema
/// desconhece, em vez de recusar ele pergunta a nós o que fazer, e a partir
/// daí somos o fornecedor de bytes: ele pede trechos por posição, nós lemos do
/// servidor e devolvemos. Vem do `SMBResourceLoader` do LibertyX Player
/// (removido de lá quando o VLC virou o motor), com as armadilhas já pagas.
///
/// Só os formatos que a Apple decodifica; MKV e companhia descem para a pasta
/// temporária e abrem no QuickLook — que também não toca MKV, e aí o "Abrir
/// com" leva ao LibertyX Player.
final class StreamSmb: NSObject, AVAssetResourceLoaderDelegate {
    static let esquema = "folder-smb"

    private let loc: Loc
    private let tipo: String

    /// Um leitor, uma leitura por vez: o AVPlayer pede vários trechos ao mesmo
    /// tempo, e duas leituras concorrentes na mesma abertura embaralhavam o
    /// estado — o vídeo dava erro alguns segundos depois de começar.
    private actor Serial {
        private let leitor: SmbLeitor
        let tamanho: UInt64
        init(_ l: SmbLeitor) { leitor = l; tamanho = l.tamanho }
        func ler(_ offset: UInt64, _ n: Int) async throws -> Data { try await leitor.ler(em: offset, n) }
        func fechar() async { await leitor.fechar() }
    }

    private var serial: Serial?
    private var abertura: Task<Serial, Error>?
    private var tarefas: [ObjectIdentifier: Task<Void, Never>] = [:]
    private let trava = NSLock()
    private let fila = DispatchQueue(label: "com.mauricio.libertyx.files.stream")

    private init(loc: Loc, tipo: String) {
        self.loc = loc
        self.tipo = tipo
    }

    static func uti(_ nome: String) -> String? {
        switch (nome as NSString).pathExtension.lowercased() {
        case "mp4": return UTType.mpeg4Movie.identifier
        case "m4v": return "com.apple.m4v-video"
        case "mov": return UTType.quickTimeMovie.identifier
        case "mp3": return UTType.mp3.identifier
        case "m4a": return UTType.mpeg4Audio.identifier
        case "aac": return "public.aac-audio"
        case "wav": return UTType.wav.identifier
        default: return nil
        }
    }

    static func toca(_ nome: String) -> Bool { uti(nome) != nil }

    static func criar(_ e: FileEntry) -> StreamSmb? {
        guard let t = uti(e.nome) else { return nil }
        return StreamSmb(loc: e.loc, tipo: t)
    }

    func asset() -> AVURLAsset? {
        var c = URLComponents()
        c.scheme = Self.esquema
        c.host = "servidor"
        c.path = "/" + loc.nome
        guard let url = c.url else { return nil }
        let a = AVURLAsset(url: url)
        a.resourceLoader.setDelegate(self, queue: fila)
        return a
    }

    func encerrar() {
        abertura?.cancel()
        trava.lock()
        tarefas.values.forEach { $0.cancel() }
        tarefas.removeAll()
        trava.unlock()
        if let s = serial { Task { await s.fechar() } }
        serial = nil
    }

    // MARK: - Delegado

    func resourceLoader(_ resourceLoader: AVAssetResourceLoader,
                        shouldWaitForLoadingOfRequestedResource pedido: AVAssetResourceLoadingRequest) -> Bool {
        let chave = ObjectIdentifier(pedido)
        let t = Task { [weak self] in
            await self?.atender(pedido)
            self?.esquecer(chave)
        }
        trava.lock(); tarefas[chave] = t; trava.unlock()
        return true
    }

    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, didCancel pedido: AVAssetResourceLoadingRequest) {
        let chave = ObjectIdentifier(pedido)
        trava.lock(); tarefas[chave]?.cancel(); tarefas[chave] = nil; trava.unlock()
    }

    private func esquecer(_ chave: ObjectIdentifier) {
        trava.lock(); tarefas[chave] = nil; trava.unlock()
    }

    private func abrir() async throws -> Serial {
        if let serial { return serial }
        if let abertura { return try await abertura.value }
        let l = loc
        let t = Task<Serial, Error> {
            guard let leitor = try await SmbFs.abrirLeitura(l) as? SmbLeitor else { throw ErroDeArquivo.naoAchado }
            return Serial(leitor)
        }
        abertura = t
        let s = try await t.value
        serial = s
        return s
    }

    private func atender(_ pedido: AVAssetResourceLoadingRequest) async {
        do {
            let s = try await abrir()
            let tamanho = s.tamanho
            // O AVPlayer pergunta primeiro o que é o arquivo. Sem tamanho e sem
            // aviso de que aceitamos trechos, ele desiste de buscar e trata
            // tudo como transmissão ao vivo.
            if let info = pedido.contentInformationRequest {
                info.contentType = tipo
                info.contentLength = Int64(tamanho)
                info.isByteRangeAccessSupported = true
            }
            guard let dados = pedido.dataRequest else { pedido.finishLoading(); return }
            let inicio = dados.currentOffset
            // Com `requestsAllDataToEndOfResource` o comprimento pedido não
            // vale nada: ele quer tudo daquele ponto em diante. Ler isso como
            // número trazia gigabytes numa leitura só — a tela preta do Player.
            let total: Int64 = dados.requestsAllDataToEndOfResource
                ? Int64(tamanho) - inicio
                : Int64(dados.requestedLength) - (inicio - dados.requestedOffset)
            guard total > 0, inicio < Int64(tamanho) else { pedido.finishLoading(); return }
            var pos = inicio
            let fim = min(Int64(tamanho), inicio + total)
            // Em pedaços, entregando a cada um: o vídeo começa com o que chegou.
            while pos < fim {
                if Task.isCancelled || pedido.isCancelled { return }
                let n = Int(min(512 * 1024, fim - pos))
                let trecho = try await s.ler(UInt64(pos), n)
                if trecho.isEmpty { break }
                if Task.isCancelled || pedido.isCancelled { return }
                dados.respond(with: trecho)
                pos += Int64(trecho.count)
            }
            pedido.finishLoading()
        } catch {
            guard !Task.isCancelled, !pedido.isCancelled else { return }
            pedido.finishLoading(with: error)
        }
    }
}
