import Foundation
import Network
import SMBClient

/// O cliente SMB de uma sessão, legível de fora do ator — é o que o
/// "Cancelar" usa para derrubar uma conexão presa no meio de uma leitura, sem
/// esperar a vez no ator (que está justamente ocupado esperando a rede).
final class CaixaDoCliente: @unchecked Sendable {
    private let trava = NSLock()
    private var c: SMBClient?
    func guardar(_ novo: SMBClient?) { trava.lock(); c = novo; trava.unlock() }
    func derrubar() {
        trava.lock(); let x = c; trava.unlock()
        x?.session.disconnect()
    }
}

/// Uma conexão SMB viva com **um compartilhamento** de um servidor.
///
/// Uma por share, e não uma por servidor: a sessão do SMBClient tem uma árvore
/// montada por vez, e uma cópia de um share para outro do mesmo servidor
/// trocaria a árvore debaixo do arquivo que ainda está sendo lido. A lista de
/// compartilhamentos é a sessão de share vazio.
///
/// **Dedicada** = a conexão de uma transferência só (cópia, download, vídeo
/// tocando). Ela não é dividida com a navegação: na primeira versão era, e um
/// erro qualquer ao navegar ou ao gerar uma miniatura derrubava a conexão
/// compartilhada — a cópia em andamento morria com "ConnectionError erro 2"
/// (o `.cancelled` do SMBClient), sem ter tido erro nenhum ela mesma.
actor SmbSessao {
    let servidor: SMBServer
    let share: String
    let dedicada: Bool
    nonisolated let caixa = CaixaDoCliente()
    private var cliente: SMBClient?
    private var conectando: Task<SMBClient, Error>?

    init(servidor: SMBServer, share: String, dedicada: Bool = false) {
        self.servidor = servidor
        self.share = share
        self.dedicada = dedicada
    }

    private func conectado() async throws -> SMBClient {
        if let cliente { return cliente }
        if let conectando { return try await conectando.value }
        let s = servidor, sh = share
        let t = Task<SMBClient, Error> {
            let c = s.porta == 445 ? SMBClient(host: s.host) : SMBClient(host: s.host, port: s.porta)
            // Convidado entra com usuário e senha nulos, não vazios — o
            // servidor trata os dois casos de forma diferente.
            let senha = s.convidado ? nil : Chaveiro.ler(s.id)
            do {
                try await comPrazo(15) {
                    if s.convidado {
                        try await c.login(username: nil, password: nil)
                    } else {
                        try await c.login(username: s.usuario, password: senha,
                                          domain: s.dominio.isEmpty ? nil : s.dominio)
                    }
                }
                if !sh.isEmpty { _ = try await comPrazo(15) { try await c.connectShare(sh) } }
            } catch {
                c.session.disconnect()
                throw error
            }
            return c
        }
        conectando = t
        do {
            let c = try await t.value
            cliente = c
            caixa.guardar(c)
            conectando = nil
            return c
        } catch {
            conectando = nil
            throw SmbErro.traduzir(error)
        }
    }

    /// Roda uma operação na conexão, com prazo.
    ///
    /// Sem prazo, uma resposta que nunca chega (Wi-Fi que pisca, servidor que
    /// engasga) prendia a cópia para sempre — e prendia junto o "Cancelar",
    /// que só é olhado entre um bloco e outro. Prazo vencido ou conexão caída
    /// derrubam a conexão: a próxima operação reconecta do zero. Recusa do
    /// servidor (senha, permissão, nome repetido) não derruba nada.
    func usar<T>(prazo: Double = 60, _ op: @escaping (SMBClient) async throws -> T) async throws -> T {
        let c = try await conectado()
        let dedicada = self.dedicada
        do {
            return try await withTaskCancellationHandler {
                try await comPrazo(prazo) { try await op(c) }
            } onCancel: {
                // Só a conexão de uma transferência cai com o Cancelar: a da
                // navegação é de todo mundo, e uma tela fechando cancela
                // tarefas o tempo todo.
                if dedicada { c.session.disconnect() }
            }
        } catch {
            if SmbErro.ehDeConexao(error) || (dedicada && error is CancellationError) {
                cliente = nil
                caixa.guardar(nil)
                c.session.disconnect()
            }
            throw SmbErro.traduzir(error)
        }
    }

    /// Fecha a conexão. O logoff educado tem prazo curto: numa conexão presa
    /// ele também ficaria preso.
    func encerrar() async {
        conectando?.cancel()
        conectando = nil
        if let c = cliente {
            _ = try? await comPrazo(3) { try await c.logoff() }
            c.session.disconnect()
        }
        cliente = nil
        caixa.guardar(nil)
    }
}

/// O sistema de arquivos do SMB — o `SmbFs` do Android.
enum SmbFs {
    private actor Pool {
        var sessoes: [String: SmbSessao] = [:]
        func sessao(_ s: SMBServer, _ share: String) -> SmbSessao {
            let k = s.id.uuidString + "/" + share
            if let x = sessoes[k], x.servidor == s { return x }
            let nova = SmbSessao(servidor: s, share: share)
            sessoes[k] = nova
            return nova
        }
        func esquecer(_ id: UUID) -> [SmbSessao] {
            let p = id.uuidString + "/"
            let fora = sessoes.filter { $0.key.hasPrefix(p) }
            for k in fora.keys { sessoes[k] = nil }
            return Array(fora.values)
        }
    }
    private static let pool = Pool()

    /// Fecha as conexões de navegação de um servidor ("Desconectar e
    /// reconectar", ou ao editar e remover o servidor). As dedicadas das
    /// transferências seguem.
    static func esquecer(_ id: UUID) async {
        for s in await pool.esquecer(id) { await s.encerrar() }
    }

    static func sessao(_ id: UUID, _ share: String) async throws -> SmbSessao {
        guard let s = CopiaDosServidores.servidor(id) else { throw SmbErro.removido }
        return await pool.sessao(s, share)
    }

    static func sessaoDedicada(_ id: UUID, _ share: String) throws -> SmbSessao {
        guard let s = CopiaDosServidores.servidor(id) else { throw SmbErro.removido }
        return SmbSessao(servidor: s, share: share, dedicada: true)
    }

    /// O caminho no formato do SMB: barra invertida, sem barra no começo, e
    /// os acentos compostos (um "é" decomposto não casa com o do servidor).
    static func caminhoSmb(_ c: String) -> String {
        c.trimmingCharacters(in: CharacterSet(charactersIn: "/\\"))
            .replacingOccurrences(of: "/", with: "\\")
            .precomposedStringWithCanonicalMapping
    }

    static func listar(_ loc: Loc) async throws -> [FileEntry] {
        guard case .smb(let id, let share, let caminho) = loc else { return [] }
        let sessao = try await sessao(id, share)
        if share.isEmpty {
            let shares = try await sessao.usar { try await $0.listShares() }
            // IPC$ e afins não guardam arquivo; mostrá-los só confunde.
            return shares
                .filter { !$0.type.contains(.ipc) && !$0.type.contains(.printQueue) && !$0.name.hasSuffix("$") }
                .map { FileEntry(loc: loc.filho($0.name), nome: $0.name, isDir: true, ehShare: true) }
        }
        let c = caminhoSmb(caminho)
        let arquivos = try await sessao.usar(prazo: 120) { try await $0.listDirectory(path: c) }
        return arquivos
            .filter { $0.name != "." && $0.name != ".." }
            .map { f in
                FileEntry(loc: loc.filho(f.name), nome: f.name, isDir: f.isDirectory,
                          tamanho: f.isDirectory ? 0 : Int64(f.size), modificado: f.lastWriteTime,
                          oculto: f.isHidden || f.name.hasPrefix("."))
            }
    }

    static func criarPasta(_ pai: Loc, _ nome: String) async throws -> Loc {
        guard case .smb(let id, let share, _) = pai, !share.isEmpty else { throw ErroDeArquivo.raizDoServidor }
        let novo = pai.filho(nome)
        guard case .smb(_, _, let c) = novo else { return novo }
        let s = try await sessao(id, share)
        let caminho = caminhoSmb(c)
        try await s.usar { try await $0.createDirectory(path: caminho) }
        return novo
    }

    static func apagar(_ e: FileEntry) async throws {
        guard case .smb(let id, let share, let c) = e.loc else { return }
        if c.isEmpty || e.ehShare { throw ErroDeArquivo.shareNaoApaga }
        let s = try await sessao(id, share)
        let caminho = caminhoSmb(c)
        if e.isDir {
            // Pasta grande se apaga arquivo por arquivo: prazo largo.
            try await s.usar(prazo: 900) { try await $0.deleteDirectory(path: caminho) }
        } else {
            try await s.usar { try await $0.deleteFile(path: caminho) }
        }
    }

    static func existe(_ loc: Loc) async -> Bool {
        guard case .smb(let id, let share, let c) = loc, !share.isEmpty, !c.isEmpty,
              let s = try? await sessao(id, share) else { return false }
        let caminho = caminhoSmb(c)
        let arquivo = (try? await s.usar { try await $0.existFile(path: caminho) }) ?? false
        if arquivo { return true }
        return (try? await s.usar { try await $0.existDirectory(path: caminho) }) ?? false
    }

    static func renomear(_ loc: Loc, _ nome: String) async throws -> Loc {
        guard case .smb(let id, let share, let c) = loc, !c.isEmpty, let pai = loc.pai else { throw ErroDeArquivo.shareNaoApaga }
        let novo = pai.filho(nome)
        guard case .smb(_, _, let c2) = novo else { return novo }
        if nome.lowercased() != loc.nome.lowercased(), await existe(novo) { throw ErroDeArquivo.nomeExiste }
        let s = try await sessao(id, share)
        let de = caminhoSmb(c), para = caminhoSmb(c2)
        try await s.usar { try await $0.move(from: de, to: para) }
        return novo
    }

    /// Mover dentro do mesmo share é renomear com outro caminho: nenhum byte
    /// atravessa a rede.
    static func moverRapido(_ de: Loc, _ para: Loc) async -> Bool {
        guard de.mesmoLugar(para), case .smb(let id, let share, let c1) = de, case .smb(_, _, let c2) = para,
              let s = try? await sessao(id, share) else { return false }
        let a = caminhoSmb(c1), b = caminhoSmb(c2)
        do {
            try await s.usar { try await $0.move(from: a, to: b) }
            return true
        } catch { return false }
    }

    /// `dedicada`: numa conexão só dela, que reconecta e continua de onde
    /// parou se a rede cair. É o caso de toda transferência; as miniaturas
    /// usam a conexão da navegação.
    static func abrirLeitura(_ loc: Loc, dedicada: Bool = true) async throws -> SmbLeitor {
        guard case .smb(let id, let share, let c) = loc else { throw ErroDeArquivo.naoAchado }
        let s = dedicada ? try sessaoDedicada(id, share) : try await sessao(id, share)
        return try await SmbLeitor.abrir(sessao: s, caminho: caminhoSmb(c))
    }

    static func abrirGravacao(_ loc: Loc) async throws -> Gravador {
        guard case .smb(let id, let share, let c) = loc, !share.isEmpty else { throw ErroDeArquivo.raizDoServidor }
        let s = try sessaoDedicada(id, share)
        let caminho = caminhoSmb(c)
        let fileId = try await SmbGravador.abrirArquivo(s, caminho, criar: true)
        return SmbGravador(sessao: s, caminho: caminho, fileId: fileId)
    }
}

/// Quantas vezes uma transferência reconecta antes de desistir, e quanto
/// espera entre uma e outra (1 s, 2 s, 4 s, 8 s): o bastante para o Wi-Fi que
/// pisca ou o HD do servidor que estava dormindo, e pouco para quem desligou
/// o servidor de verdade.
private let tentativas = 4
private func espera(_ i: Int) async throws {
    try await Task.sleep(nanoseconds: UInt64(1 << (i - 1)) * 1_000_000_000)
}

/// Leitura aos pedaços, por posição: nunca o arquivo inteiro na memória.
/// Numa conexão dedicada, se a rede cair no meio, reconecta, reabre o arquivo
/// e continua do mesmo byte.
final class SmbLeitor: Leitor {
    private var sessao: SmbSessao
    private let caminho: String
    private var leitor: FileReader
    let tamanho: UInt64
    private var posicao: UInt64 = 0

    private init(sessao: SmbSessao, caminho: String, leitor: FileReader, tamanho: UInt64) {
        self.sessao = sessao
        self.caminho = caminho
        self.leitor = leitor
        self.tamanho = tamanho
    }

    static func abrir(sessao: SmbSessao, caminho: String) async throws -> SmbLeitor {
        let (l, t) = try await abrirArquivo(sessao, caminho)
        return SmbLeitor(sessao: sessao, caminho: caminho, leitor: l, tamanho: t)
    }

    private static func abrirArquivo(_ s: SmbSessao, _ caminho: String) async throws -> (FileReader, UInt64) {
        let l = try await s.usar { c -> FileReader in c.fileReader(path: caminho) }
        let t = try await s.usar(prazo: 30) { _ in try await l.fileSize }
        return (l, t)
    }

    func ler(_ max: Int) async throws -> Data {
        let d = try await ler(em: posicao, max)
        posicao += UInt64(d.count)
        return d
    }

    func ler(em offset: UInt64, _ n: Int) async throws -> Data {
        // Pedir além do fim faz o servidor responder "fim de arquivo" como
        // erro; parar antes é mais simples que tratar a resposta.
        guard offset < tamanho else { return Data() }
        let q = UInt32(min(UInt64(n), tamanho - offset))
        var tentativa = 0
        while true {
            let l = leitor
            do {
                let d = try await sessao.usar(prazo: 30) { _ in try await l.read(offset: offset, length: q) }
                // Nada antes do fim do arquivo não é fim: é a conexão que
                // morreu sem avisar. Tratar como fim cortaria o arquivo.
                if d.isEmpty { throw SmbErro.caiu }
                return d
            } catch {
                tentativa += 1
                guard sessao.dedicada, SmbErro.ehDeConexao(error), tentativa <= tentativas, !Task.isCancelled else { throw error }
                try await espera(tentativa)
                // Conexão nova, arquivo aberto de novo: o identificador do
                // arquivo morreu junto com a conexão antiga.
                await sessao.encerrar()
                sessao = SmbSessao(servidor: sessao.servidor, share: sessao.share, dedicada: true)
                if let novo = try? await Self.abrirArquivo(sessao, caminho) { leitor = novo.0 }
            }
        }
    }

    func fechar() async {
        let l = leitor
        _ = try? await sessao.usar(prazo: 5) { _ in try await l.close() }
        if sessao.dedicada { await sessao.encerrar() }
    }

    /// O Cancelar de quem não está dentro de uma tarefa (o player de vídeo).
    func derrubar() { if sessao.dedicada { sessao.caixa.derrubar() } }
}

/// Gravação aos pedaços: o `FileWriter` do SMBClient só sabe mandar um
/// arquivo inteiro de uma vez, sem como cancelar no meio de 10 GB. Se a rede
/// cair, reabre o arquivo pela metade e regrava a partir do último pedaço
/// confirmado.
final class SmbGravador: Gravador {
    private var sessao: SmbSessao
    private let caminho: String
    private var fileId: Data
    private var posicao: UInt64 = 0
    private var fechado = false

    init(sessao: SmbSessao, caminho: String, fileId: Data) {
        self.sessao = sessao
        self.caminho = caminho
        self.fileId = fileId
    }

    /// `criar`: arquivo novo (falha se já existir — nunca sobrescreve);
    /// senão, abre o que ficou pela metade para continuar.
    static func abrirArquivo(_ s: SmbSessao, _ caminho: String, criar: Bool) async throws -> Data {
        let r = try await s.usar(prazo: 30) { cli in
            try await cli.session.create(
                desiredAccess: [.readData, .writeData, .appendData, .readAttributes, .writeAttributes, .readControl, .delete],
                fileAttributes: [.archive, .normal],
                shareAccess: [.read, .write, .delete],
                createDisposition: criar ? .create : .open,
                createOptions: [],
                name: caminho)
        }
        return r.fileId
    }

    func gravar(_ dados: Data) async throws {
        var i = 0
        var tentativa = 0
        while i < dados.count {
            let p = posicao, id = fileId
            do {
                let n = try await sessao.usar(prazo: 30) { c -> Int in
                    let max = Int(c.session.maxWriteSize > 0 ? min(c.session.maxWriteSize, 1 << 20) : 1 << 16)
                    let pedaco = Data(dados[dados.startIndex + i ..< dados.startIndex + min(i + max, dados.count)])
                    try await c.session.write(data: pedaco, fileId: id, offset: p)
                    return pedaco.count
                }
                i += n
                posicao += UInt64(n)
                tentativa = 0
            } catch {
                tentativa += 1
                guard SmbErro.ehDeConexao(error), tentativa <= tentativas, !Task.isCancelled else { throw error }
                try await espera(tentativa)
                await sessao.encerrar()
                sessao = SmbSessao(servidor: sessao.servidor, share: sessao.share, dedicada: true)
                if let novo = try? await Self.abrirArquivo(sessao, caminho, criar: false) { fileId = novo }
            }
        }
    }

    func concluir() async throws {
        guard !fechado else { return }
        fechado = true
        let id = fileId
        try await sessao.usar(prazo: 30) { c in _ = try await c.session.close(fileId: id) }
        await sessao.encerrar()
    }

    /// Arquivo pela metade no destino é pior que nenhum. Depois de um
    /// Cancelar a conexão dedicada caiu, então o apagar vai por uma nova.
    func descartar() async {
        if !fechado {
            fechado = true
            let id = fileId
            _ = try? await sessao.usar(prazo: 5) { c in try await c.session.close(fileId: id) }
        }
        await sessao.encerrar()
        let c = caminho
        let limpeza = SmbSessao(servidor: sessao.servidor, share: sessao.share, dedicada: true)
        _ = try? await limpeza.usar(prazo: 15) { cli in try await cli.deleteFile(path: c) }
        await limpeza.encerrar()
    }
}

enum SmbErro: LocalizedError {
    case removido, senha, negado, host, porta, tempo, caiu, emUso, naoVazia, shareNaoAchado
    case recusou(String)

    var errorDescription: String? {
        switch self {
        case .removido: return String(localized: "Servidor removido")
        case .senha: return String(localized: "Usuário ou senha incorretos")
        case .negado: return String(localized: "Acesso negado")
        case .host: return String(localized: "Servidor não encontrado. Confira o endereço.")
        case .porta: return String(localized: "Sem resposta na porta 445. O servidor está ligado e na mesma rede?")
        case .tempo: return String(localized: "O servidor demorou demais para responder")
        case .caiu: return String(localized: "A conexão com o servidor caiu. Confira o Wi-Fi e tente de novo.")
        case .emUso: return String(localized: "O arquivo está em uso no servidor")
        case .naoVazia: return String(localized: "A pasta não está vazia")
        case .shareNaoAchado: return String(localized: "Compartilhamento não encontrado")
        case .recusou(let m): return String(localized: "O servidor recusou: \(m)")
        }
    }

    /// A conexão caiu ou travou — e não o servidor recusou. É o caso em que
    /// reconectar resolve.
    static func ehDeConexao(_ e: Error) -> Bool {
        if e is ConnectionError || e is NWError || e is POSIXError { return true }
        if let s = e as? SmbErro {
            switch s {
            case .tempo, .caiu, .porta, .host: return true
            default: return false
            }
        }
        let ns = e as NSError
        return ns.domain == NSPOSIXErrorDomain
    }

    static func traduzir(_ e: Error) -> Error {
        if e is SmbErro || e is ErroDeArquivo || e is CancellationError { return e }
        if let r = e as? ErrorResponse {
            switch ErrorCode(rawValue: r.header.status) {
            case .logonFailure?: return SmbErro.senha
            case .accessDenied?: return SmbErro.negado
            case .objectNameCollision?: return ErroDeArquivo.nomeExiste
            case .objectNameNotFound?, .objectPathNotFound?: return ErroDeArquivo.naoAchado
            case .sharingViolation?: return SmbErro.emUso
            case .directoryNotEmpty?: return SmbErro.naoVazia
            case .badNetworkName?: return SmbErro.shareNaoAchado
            case .networkNameDeleted?, .networkSessionExpired?: return SmbErro.caiu
            default: return SmbErro.recusou(r.description)
            }
        }
        if e is ConnectionError { return SmbErro.caiu }
        if let n = e as? NWError {
            switch n {
            case .dns: return SmbErro.host
            case .posix(let c) where c == .ETIMEDOUT: return SmbErro.tempo
            case .posix(let c) where c == .EHOSTUNREACH || c == .EHOSTDOWN: return SmbErro.host
            case .posix(let c) where c == .ECONNREFUSED: return SmbErro.porta
            default: return SmbErro.caiu
            }
        }
        if e is POSIXError { return SmbErro.caiu }
        return e
    }
}

/// O resultado de uma corrida entre a operação, o prazo e o Cancelar — o
/// primeiro que chegar vale, e a continuação é retomada uma vez só (retomar
/// duas vezes derruba o app).
final class Corrida<T>: @unchecked Sendable {
    private let trava = NSLock()
    private var cont: CheckedContinuation<T, Error>?
    private var cedo: Result<T, Error>?
    private var feito = false
    private var relogio: Task<Void, Never>?

    func armar(_ c: CheckedContinuation<T, Error>, relogio r: Task<Void, Never>) {
        trava.lock()
        if let x = cedo {
            cedo = nil
            trava.unlock()
            r.cancel()
            c.resume(with: x)
            return
        }
        cont = c
        relogio = r
        trava.unlock()
    }

    func acabar(_ r: Result<T, Error>) {
        trava.lock()
        if feito { trava.unlock(); return }
        feito = true
        let c = cont
        cont = nil
        let rel = relogio
        relogio = nil
        if c == nil { cedo = r }
        trava.unlock()
        rel?.cancel()
        c?.resume(with: r)
    }
}

/// Uma operação de rede com prazo, que também desiste na hora ao ser
/// cancelada. Não dá para usar um grupo de tarefas: ele espera a tarefa lenta
/// terminar antes de sair, e uma leitura cuja resposta nunca chega não
/// termina nunca.
func comPrazo<T>(_ segundos: Double, _ op: @escaping () async throws -> T) async throws -> T {
    let corrida = Corrida<T>()
    return try await withTaskCancellationHandler {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<T, Error>) in
            Task {
                do {
                    let v = try await op()
                    corrida.acabar(.success(v))
                } catch {
                    corrida.acabar(.failure(error))
                }
            }
            let relogio = Task {
                try? await Task.sleep(nanoseconds: UInt64(segundos * 1_000_000_000))
                if !Task.isCancelled { corrida.acabar(.failure(SmbErro.tempo)) }
            }
            corrida.armar(cont, relogio: relogio)
        }
    } onCancel: {
        corrida.acabar(.failure(CancellationError()))
    }
}

/// Trava para uma continuação nunca ser retomada duas vezes.
final class UmaVez {
    private let trava = NSLock()
    private var usado = false
    func marcar() -> Bool {
        trava.lock(); defer { trava.unlock() }
        if usado { return false }
        usado = true
        return true
    }
}
