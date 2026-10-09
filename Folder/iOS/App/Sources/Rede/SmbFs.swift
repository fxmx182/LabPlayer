import Foundation
import Network
import SMBClient

/// Uma conexão SMB viva com **um compartilhamento** de um servidor.
///
/// Uma por share, e não uma por servidor: a sessão do SMBClient tem uma árvore
/// montada por vez, e uma cópia de um share para outro do mesmo servidor
/// trocaria a árvore debaixo do arquivo que ainda está sendo lido. Com uma
/// conexão para cada lado, os dois ficam abertos ao mesmo tempo. A lista de
/// compartilhamentos é a sessão de share vazio.
actor SmbSessao {
    let servidor: SMBServer
    let share: String
    private var cliente: SMBClient?
    private var conectando: Task<SMBClient, Error>?

    init(servidor: SMBServer, share: String) {
        self.servidor = servidor
        self.share = share
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
            try await comPrazo(15) {
                if s.convidado {
                    try await c.login(username: nil, password: nil)
                } else {
                    try await c.login(username: s.usuario, password: senha,
                                      domain: s.dominio.isEmpty ? nil : s.dominio)
                }
            }
            if !sh.isEmpty { _ = try await comPrazo(15) { try await c.connectShare(sh) } }
            return c
        }
        conectando = t
        do {
            let c = try await t.value
            cliente = c
            conectando = nil
            return c
        } catch {
            conectando = nil
            throw SmbErro.traduzir(error)
        }
    }

    /// Roda uma operação na conexão. Erro de rede (e não uma recusa do
    /// servidor) derruba a conexão, para a próxima operação reconectar.
    func usar<T>(_ op: (SMBClient) async throws -> T) async throws -> T {
        let c = try await conectado()
        do {
            return try await op(c)
        } catch {
            if !(error is ErrorResponse) {
                cliente = nil
                c.session.disconnect()
            }
            throw SmbErro.traduzir(error)
        }
    }

    func encerrar() async {
        conectando?.cancel()
        conectando = nil
        if let c = cliente { try? await c.logoff() }
        cliente = nil
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

    /// Fecha as conexões de um servidor ("Desconectar e reconectar", ou ao
    /// editar e remover o servidor).
    static func esquecer(_ id: UUID) async {
        for s in await pool.esquecer(id) { await s.encerrar() }
    }

    static func sessao(_ id: UUID, _ share: String) async throws -> SmbSessao {
        guard let s = CopiaDosServidores.servidor(id) else { throw SmbErro.removido }
        return await pool.sessao(s, share)
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
        let arquivos = try await sessao.usar { try await $0.listDirectory(path: caminhoSmb(caminho)) }
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
        try await s.usar { try await $0.createDirectory(path: caminhoSmb(c)) }
        return novo
    }

    static func apagar(_ e: FileEntry) async throws {
        guard case .smb(let id, let share, let c) = e.loc else { return }
        if c.isEmpty || e.ehShare { throw ErroDeArquivo.shareNaoApaga }
        let s = try await sessao(id, share)
        if e.isDir {
            try await s.usar { try await $0.deleteDirectory(path: caminhoSmb(c)) }
        } else {
            try await s.usar { try await $0.deleteFile(path: caminhoSmb(c)) }
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
        try await s.usar { try await $0.move(from: caminhoSmb(c), to: caminhoSmb(c2)) }
        return novo
    }

    /// Mover dentro do mesmo share é renomear com outro caminho: nenhum byte
    /// atravessa a rede.
    static func moverRapido(_ de: Loc, _ para: Loc) async -> Bool {
        guard de.mesmoLugar(para), case .smb(let id, let share, let c1) = de, case .smb(_, _, let c2) = para,
              let s = try? await sessao(id, share) else { return false }
        do {
            try await s.usar { try await $0.move(from: caminhoSmb(c1), to: caminhoSmb(c2)) }
            return true
        } catch { return false }
    }

    static func abrirLeitura(_ loc: Loc) async throws -> Leitor {
        guard case .smb(let id, let share, let c) = loc else { throw ErroDeArquivo.naoAchado }
        let s = try await sessao(id, share)
        let caminho = caminhoSmb(c)
        let leitor = try await s.usar { c -> FileReader in c.fileReader(path: caminho) }
        let tamanho = try await s.usar { _ in try await leitor.fileSize }
        return SmbLeitor(sessao: s, leitor: leitor, tamanho: tamanho)
    }

    static func abrirGravacao(_ loc: Loc) async throws -> Gravador {
        guard case .smb(let id, let share, let c) = loc, !share.isEmpty else { throw ErroDeArquivo.raizDoServidor }
        let s = try await sessao(id, share)
        let caminho = caminhoSmb(c)
        let resposta = try await s.usar { cli in
            try await cli.session.create(
                desiredAccess: [.readData, .writeData, .appendData, .readAttributes, .writeAttributes, .readControl, .delete],
                fileAttributes: [.archive, .normal],
                shareAccess: [.read, .write, .delete],
                createDisposition: .create,
                createOptions: [],
                name: caminho)
        }
        return SmbGravador(sessao: s, caminho: caminho, fileId: resposta.fileId)
    }
}

/// Leitura aos pedaços, por posição: nunca o arquivo inteiro na memória.
final class SmbLeitor: Leitor {
    private let sessao: SmbSessao
    private let leitor: FileReader
    let tamanho: UInt64
    private var posicao: UInt64 = 0

    init(sessao: SmbSessao, leitor: FileReader, tamanho: UInt64) {
        self.sessao = sessao
        self.leitor = leitor
        self.tamanho = tamanho
    }

    func ler(_ max: Int) async throws -> Data {
        // Pedir além do fim faz o servidor responder "fim de arquivo" como
        // erro; parar antes é mais simples que tratar a resposta.
        guard posicao < tamanho else { return Data() }
        let n = UInt32(min(UInt64(max), tamanho - posicao))
        let p = posicao
        let d = try await sessao.usar { _ in try await self.leitor.read(offset: p, length: n) }
        posicao += UInt64(d.count)
        return d
    }

    func ler(em offset: UInt64, _ n: Int) async throws -> Data {
        guard offset < tamanho else { return Data() }
        let q = UInt32(min(UInt64(n), tamanho - offset))
        return try await sessao.usar { _ in try await self.leitor.read(offset: offset, length: q) }
    }

    func fechar() async {
        try? await sessao.usar { _ in try await self.leitor.close() }
    }
}

/// Gravação aos pedaços: o `FileWriter` do SMBClient só sabe mandar um
/// arquivo inteiro de uma vez, sem como cancelar no meio de 10 GB.
final class SmbGravador: Gravador {
    private let sessao: SmbSessao
    private let caminho: String
    private let fileId: Data
    private var posicao: UInt64 = 0
    private var fechado = false

    init(sessao: SmbSessao, caminho: String, fileId: Data) {
        self.sessao = sessao
        self.caminho = caminho
        self.fileId = fileId
    }

    func gravar(_ dados: Data) async throws {
        var i = 0
        while i < dados.count {
            let p = posicao, id = fileId
            let fatia = try await sessao.usar { c -> Int in
                let max = Int(c.session.maxWriteSize > 0 ? min(c.session.maxWriteSize, 1 << 20) : 1 << 16)
                let pedaco = Data(dados[dados.startIndex + i ..< dados.startIndex + min(i + max, dados.count)])
                try await c.session.write(data: pedaco, fileId: id, offset: p)
                return pedaco.count
            }
            i += fatia
            posicao += UInt64(fatia)
        }
    }

    func concluir() async throws {
        guard !fechado else { return }
        fechado = true
        let id = fileId
        try await sessao.usar { c in _ = try await c.session.close(fileId: id) }
    }

    /// Arquivo pela metade no destino é pior que nenhum.
    func descartar() async {
        if !fechado {
            fechado = true
            let id = fileId
            _ = try? await sessao.usar { c in try await c.session.close(fileId: id) }
        }
        let c = caminho
        _ = try? await sessao.usar { cli in try await cli.deleteFile(path: c) }
    }
}

enum SmbErro: LocalizedError {
    case removido, senha, negado, host, porta, tempo, emUso, naoVazia, shareNaoAchado
    case recusou(String)

    var errorDescription: String? {
        switch self {
        case .removido: return String(localized: "Servidor removido")
        case .senha: return String(localized: "Usuário ou senha incorretos")
        case .negado: return String(localized: "Acesso negado")
        case .host: return String(localized: "Servidor não encontrado. Confira o endereço.")
        case .porta: return String(localized: "Sem resposta na porta 445. O servidor está ligado e na mesma rede?")
        case .tempo: return String(localized: "O servidor demorou demais para responder")
        case .emUso: return String(localized: "O arquivo está em uso no servidor")
        case .naoVazia: return String(localized: "A pasta não está vazia")
        case .shareNaoAchado: return String(localized: "Compartilhamento não encontrado")
        case .recusou(let m): return String(localized: "O servidor recusou: \(m)")
        }
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
            default: return SmbErro.recusou(r.description)
            }
        }
        if let n = e as? NWError {
            switch n {
            case .dns: return SmbErro.host
            case .posix(let c) where c == .ETIMEDOUT: return SmbErro.tempo
            case .posix(let c) where c == .EHOSTUNREACH || c == .EHOSTDOWN: return SmbErro.host
            default: return SmbErro.porta
            }
        }
        return e
    }
}

/// Uma operação de rede com prazo. Não dá para usar um grupo de tarefas: ele
/// espera a tarefa lenta terminar antes de sair, e uma conexão TCP para um
/// endereço mudo pode levar mais de um minuto para desistir sozinha.
func comPrazo<T>(_ segundos: Double, _ op: @escaping () async throws -> T) async throws -> T {
    let uma = UmaVez()
    return try await withCheckedThrowingContinuation { (cont: CheckedContinuation<T, Error>) in
        let tarefa = Task {
            do {
                let v = try await op()
                if uma.marcar() { cont.resume(returning: v) }
            } catch {
                if uma.marcar() { cont.resume(throwing: error) }
            }
        }
        Task {
            try? await Task.sleep(nanoseconds: UInt64(segundos * 1_000_000_000))
            if uma.marcar() {
                tarefa.cancel()
                cont.resume(throwing: SmbErro.tempo)
            }
        }
    }
}

/// Trava para uma continuação nunca ser retomada duas vezes — retomar duas
/// vezes derruba o app, e há caminhos concorrendo.
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
