import Foundation

/// Lê um arquivo aos pedaços, venha de onde vier.
protocol Leitor: AnyObject {
    /// Até `max` bytes; vazio = fim do arquivo.
    func ler(_ max: Int) async throws -> Data
    func fechar() async
}

/// Grava um arquivo aos pedaços. `descartar` apaga o que ficou pela metade.
protocol Gravador: AnyObject {
    func gravar(_ dados: Data) async throws
    func concluir() async throws
    func descartar() async
}

/// O sistema de arquivos único das telas e das operações: o mesmo `Fss` do
/// Android, decidindo pelo `Loc` se é o aparelho ou o servidor.
enum Arquivos {
    static func listar(_ dir: Loc) async throws -> [FileEntry] {
        switch dir {
        case .local: return try await LocalFs.listar(dir)
        case .smb: return try await SmbFs.listar(dir)
        }
    }

    static func criarPasta(_ pai: Loc, _ nome: String) async throws -> Loc {
        switch pai {
        case .local: return try await LocalFs.criarPasta(pai, nome)
        case .smb: return try await SmbFs.criarPasta(pai, nome)
        }
    }

    static func apagar(_ e: FileEntry) async throws {
        switch e.loc {
        case .local: try await LocalFs.apagar(e.loc)
        case .smb: try await SmbFs.apagar(e)
        }
    }

    static func renomear(_ loc: Loc, _ nome: String) async throws -> Loc {
        switch loc {
        case .local: return try await LocalFs.renomear(loc, nome)
        case .smb: return try await SmbFs.renomear(loc, nome)
        }
    }

    /// Move sem copiar os bytes, quando origem e destino estão no mesmo lugar.
    /// `false` = não dá, copie.
    static func moverRapido(_ de: Loc, _ para: Loc) async -> Bool {
        switch (de, para) {
        case (.local, .local): return await LocalFs.moverRapido(de, para)
        case (.smb, .smb): return await SmbFs.moverRapido(de, para)
        default: return false
        }
    }

    static func abrirLeitura(_ loc: Loc) async throws -> Leitor {
        switch loc {
        case .local: return try await LocalFs.abrirLeitura(loc)
        case .smb: return try await SmbFs.abrirLeitura(loc)
        }
    }

    static func abrirGravacao(_ loc: Loc) async throws -> Gravador {
        switch loc {
        case .local: return try LocalFs.abrirGravacao(loc)
        case .smb: return try await SmbFs.abrirGravacao(loc)
        }
    }

    /// O tamanho de tudo, pastas abertas recursivamente (Detalhes e cópias).
    static func tamanhoTotal(_ e: FileEntry, contagem: inout (arquivos: Int, pastas: Int)) async throws -> Int64 {
        try Task.checkCancellation()
        if !e.isDir { contagem.arquivos += 1; return e.tamanho }
        contagem.pastas += 1
        var soma: Int64 = 0
        for f in try await listar(e.loc) { soma += try await tamanhoTotal(f, contagem: &contagem) }
        return soma
    }
}

/// Os lugares do aparelho. Nada aqui roda na principal: são funções `async`
/// fora de qualquer ator, então o Swift as leva para o pool de threads.
enum LocalFs {
    private static let chaves: [URLResourceKey] = [
        .isDirectoryKey, .fileSizeKey, .contentModificationDateKey, .isHiddenKey,
        .isUbiquitousItemKey, .ubiquitousItemDownloadingStatusKey,
    ]

    static func url(_ loc: Loc) throws -> URL {
        guard let u = Raizes.url(de: loc) else { throw ErroDeArquivo.semAcesso(loc.nome) }
        return u
    }

    static func listar(_ dir: Loc) async throws -> [FileEntry] {
        let base = try url(dir)
        let itens: [URL]
        do {
            itens = try FileManager.default.contentsOfDirectory(at: base, includingPropertiesForKeys: chaves, options: [])
        } catch {
            throw ErroDeArquivo.semAcesso(dir.nome.isEmpty ? base.lastPathComponent : dir.nome)
        }
        return itens.compactMap { entrada($0, em: dir) }
    }

    /// Uma entrada a partir da URL. Arquivo do iCloud que nunca desceu pode
    /// vir como `.Nome.pdf.icloud`, um marcador escondido: aparece com o nome
    /// de verdade, e baixa quando alguém o abre ou copia.
    static func entrada(_ u: URL, em dir: Loc) -> FileEntry? {
        var nome = u.lastPathComponent
        var nuvem = false
        if nome.hasPrefix("."), nome.hasSuffix(".icloud"), nome.count > 8 {
            nome = String(nome.dropFirst().dropLast(7))
            nuvem = true
        }
        let v = try? u.resourceValues(forKeys: Set(chaves))
        let dirFlag = v?.isDirectory ?? false
        if !nuvem, v?.isUbiquitousItem == true, let s = v?.ubiquitousItemDownloadingStatus, s == .notDownloaded { nuvem = true }
        return FileEntry(
            loc: dir.filho(nome),
            nome: nome,
            isDir: nuvem ? false : dirFlag,
            tamanho: Int64(v?.fileSize ?? 0),
            modificado: v?.contentModificationDate,
            oculto: nome.hasPrefix(".") || (v?.isHidden ?? false),
            naNuvem: nuvem
        )
    }

    static func criarPasta(_ pai: Loc, _ nome: String) async throws -> Loc {
        let novo = pai.filho(nome)
        let u = try url(novo)
        if FileManager.default.fileExists(atPath: u.path) { throw ErroDeArquivo.nomeExiste }
        do {
            try FileManager.default.createDirectory(at: u, withIntermediateDirectories: false)
        } catch {
            throw ErroDeArquivo.mensagem(String(localized: "Não foi possível criar a pasta"))
        }
        return novo
    }

    static func apagar(_ loc: Loc) async throws {
        let u = try url(loc)
        do {
            try FileManager.default.removeItem(at: u)
        } catch {
            // O marcador do iCloud é o que existe de fato no disco.
            let marcador = u.deletingLastPathComponent().appendingPathComponent("." + u.lastPathComponent + ".icloud")
            if FileManager.default.fileExists(atPath: marcador.path) {
                try FileManager.default.removeItem(at: marcador)
            } else {
                throw ErroDeArquivo.mensagem(String(localized: "Não foi possível apagar \(loc.nome)"))
            }
        }
    }

    static func renomear(_ loc: Loc, _ nome: String) async throws -> Loc {
        guard let pai = loc.pai else { throw ErroDeArquivo.semAcesso(loc.nome) }
        let novo = pai.filho(nome)
        let de = try url(loc), para = try url(novo)
        // Só mudar maiúsculas ("foto.JPG" → "foto.jpg") num disco que não
        // distingue as duas acharia o próprio arquivo como "já existe".
        if nome.lowercased() != loc.nome.lowercased(), FileManager.default.fileExists(atPath: para.path) {
            throw ErroDeArquivo.nomeExiste
        }
        do {
            try FileManager.default.moveItem(at: de, to: para)
        } catch {
            throw ErroDeArquivo.mensagem(String(localized: "Não foi possível renomear"))
        }
        return novo
    }

    /// No mesmo volume, mover é renomear: instantâneo até para 50 GB. Entre o
    /// iPhone e um pendrive, `false` — e a operação copia com progresso.
    static func moverRapido(_ de: Loc, _ para: Loc) async -> Bool {
        guard let a = try? url(de), let b = try? url(para) else { return false }
        let va = (try? a.resourceValues(forKeys: [.volumeIdentifierKey]))?.volumeIdentifier as? NSObject
        let vb = (try? b.deletingLastPathComponent().resourceValues(forKeys: [.volumeIdentifierKey]))?.volumeIdentifier as? NSObject
        guard let va, let vb, va.isEqual(vb) else { return false }
        return (try? FileManager.default.moveItem(at: a, to: b)) != nil
    }

    static func abrirLeitura(_ loc: Loc) async throws -> Leitor {
        let u = try url(loc)
        try await garantirBaixado(u)
        do {
            return LocalLeitor(try FileHandle(forReadingFrom: u))
        } catch {
            throw ErroDeArquivo.semAcesso(loc.nome)
        }
    }

    static func abrirGravacao(_ loc: Loc) throws -> Gravador {
        let u = try url(loc)
        if FileManager.default.fileExists(atPath: u.path) { throw ErroDeArquivo.nomeExiste }
        guard FileManager.default.createFile(atPath: u.path, contents: nil) else {
            throw ErroDeArquivo.semAcesso(loc.pai?.nome ?? loc.nome)
        }
        return LocalGravador(try FileHandle(forWritingTo: u), url: u)
    }

    static func definirData(_ loc: Loc, _ data: Date?) {
        guard let data, let u = try? url(loc) else { return }
        try? FileManager.default.setAttributes([.modificationDate: data], ofItemAtPath: u.path)
    }

    /// Arquivo do iCloud que ainda está só na nuvem: pede ao sistema que
    /// baixe e espera. Cancelável — a espera olha o cancelamento a cada volta.
    static func garantirBaixado(_ u: URL) async throws {
        var url = u
        let marcador = u.deletingLastPathComponent().appendingPathComponent("." + u.lastPathComponent + ".icloud")
        let ehMarcador = !FileManager.default.fileExists(atPath: u.path) && FileManager.default.fileExists(atPath: marcador.path)
        if !ehMarcador {
            let v = try? url.resourceValues(forKeys: [.isUbiquitousItemKey, .ubiquitousItemDownloadingStatusKey])
            guard v?.isUbiquitousItem == true, v?.ubiquitousItemDownloadingStatus == .notDownloaded else { return }
        }
        try FileManager.default.startDownloadingUbiquitousItem(at: u)
        while true {
            try Task.checkCancellation()
            try await Task.sleep(nanoseconds: 300_000_000)
            url.removeAllCachedResourceValues()
            let v = try? url.resourceValues(forKeys: [.ubiquitousItemDownloadingStatusKey, .ubiquitousItemDownloadingErrorKey])
            if let erro = v?.ubiquitousItemDownloadingError { throw erro }
            if let s = v?.ubiquitousItemDownloadingStatus, s == .current || s == .downloaded,
               FileManager.default.fileExists(atPath: u.path) { return }
        }
    }
}

final class LocalLeitor: Leitor {
    private let h: FileHandle
    init(_ h: FileHandle) { self.h = h }
    func ler(_ max: Int) async throws -> Data { try h.read(upToCount: max) ?? Data() }
    func fechar() async { try? h.close() }
}

final class LocalGravador: Gravador {
    private let h: FileHandle
    private let url: URL
    private var fechado = false
    init(_ h: FileHandle, url: URL) { self.h = h; self.url = url }
    func gravar(_ dados: Data) async throws { try h.write(contentsOf: dados) }
    func concluir() async throws {
        guard !fechado else { return }
        fechado = true
        try h.close()
    }
    func descartar() async {
        if !fechado { fechado = true; try? h.close() }
        try? FileManager.default.removeItem(at: url)
    }
}
