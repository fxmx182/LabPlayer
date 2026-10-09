import Foundation
import UIKit

struct OpEstado: Equatable {
    var titulo: String
    var atual = ""
    var feitos: Int64 = 0
    var total: Int64 = 0
    var itensFeitos = 0
    var itensTotal = 0
    var erro: String? = nil
    var terminou = false

    var fracao: Double { total > 0 ? min(max(Double(feitos) / Double(total), 0), 1) : 0 }
}

/// Copiar, mover e apagar — entre quaisquer dois lugares (iPhone ↔ servidor,
/// servidor ↔ outro servidor, iCloud ↔ pendrive). Uma operação por vez, fora
/// da tela, como no Android.
///
/// No Android um serviço em primeiro plano segura uma cópia de 10 GB com o app
/// fechado. O iOS não tem isso: o app ganha uns segundos a mais ao sair
/// (`beginBackgroundTask`), e uma cópia longa precisa do app aberto. O painel
/// diz isso quando o tempo acaba, em vez de a cópia sumir em silêncio.
@MainActor
final class Ops: ObservableObject {
    static let shared = Ops()

    /// O progresso mora num objeto à parte, que só o painel observa: aqui ele
    /// mudava a cada 0,15 s e redesenhava toda tela que observa as operações
    /// (a lista inteira reordenada junto) — eram os soquinhos ao rolar
    /// durante uma cópia.
    var estado: OpEstado? {
        get { PainelDaOperacao.shared.estado }
        set { PainelDaOperacao.shared.estado = newValue }
    }
    @Published private(set) var ocupado = false
    /// Incrementa a cada operação concluída: as telas recarregam quando muda.
    @Published private(set) var versao = 0

    private var tarefa: Task<Void, Never>?
    private var segundoPlano: UIBackgroundTaskIdentifier = .invalid

    private init() {}

    func cancelar() { tarefa?.cancel() }
    func dispensar() { if !ocupado { estado = nil } }

    private func iniciar(_ titulo: String, _ bloco: @escaping @MainActor (Ops) async throws -> Void) {
        guard !ocupado else { return }
        estado = OpEstado(titulo: titulo)
        ocupado = true
        segundoPlano = UIApplication.shared.beginBackgroundTask(withName: "folder.ops") { [weak self] in
            // O iOS avisa que o tempo extra acabou: melhor parar com um aviso
            // do que ser morto no meio de um arquivo.
            Task { @MainActor in
                self?.tarefa?.cancel()
                self?.encerrarSegundoPlano()
            }
        }
        tarefa = Task { [weak self] in
            guard let self else { return }
            do {
                try await bloco(self)
                self.estado?.terminou = true
            } catch is CancellationError {
                self.estado?.terminou = true
                self.estado?.erro = String(localized: "Cancelado")
            } catch ErroDeArquivo.cancelado {
                self.estado?.terminou = true
                self.estado?.erro = String(localized: "Cancelado")
            } catch {
                self.estado?.terminou = true
                self.estado?.erro = error.localizedDescription
            }
            self.tarefa = nil
            self.ocupado = false
            // O índice das categorias primeiro: as telas recarregam quando a
            // versão muda, e não podem pegar o índice de antes da operação.
            await Indice.shared.invalidar()
            self.versao += 1
            self.encerrarSegundoPlano()
        }
    }

    private func encerrarSegundoPlano() {
        if segundoPlano != .invalid {
            UIApplication.shared.endBackgroundTask(segundoPlano)
            segundoPlano = .invalid
        }
    }

    // MARK: - Operações

    func excluir(_ itens: [FileEntry]) {
        iniciar(String(localized: "Excluindo")) { op in
            op.estado?.itensTotal = itens.count
            for (i, e) in itens.enumerated() {
                try Task.checkCancellation()
                op.estado?.atual = e.nome
                op.estado?.itensFeitos = i
                try await Arquivos.apagar(e)
            }
            op.estado?.itensFeitos = itens.count
        }
    }

    func transferir(_ itens: [FileEntry], para destino: Loc, mover: Bool) {
        iniciar(mover ? String(localized: "Movendo") : String(localized: "Copiando")) { op in
            for e in itens where destino == e.loc || destino.dentro(de: e.loc) {
                throw ErroDeArquivo.dentroDeSi(mover: mover)
            }
            op.estado?.atual = String(localized: "Calculando…")
            op.estado?.itensTotal = itens.count
            var cont = (arquivos: 0, pastas: 0)
            var total: Int64 = 0
            for e in itens { total += try await Arquivos.tamanhoTotal(e, contagem: &cont) }
            op.estado?.total = total

            // A cópia corre fora da principal; a tela só espia o contador.
            let prog = Progresso()
            let espelho = Task { @MainActor [weak op] in
                while !Task.isCancelled {
                    let (feitos, atual) = prog.ler()
                    if op?.estado?.feitos != feitos { op?.estado?.feitos = feitos }
                    if !atual.isEmpty, op?.estado?.atual != atual { op?.estado?.atual = atual }
                    try? await Task.sleep(nanoseconds: 150_000_000)
                }
            }
            defer { espelho.cancel() }

            for (i, e) in itens.enumerated() {
                try Task.checkCancellation()
                op.estado?.itensFeitos = i
                // Mover para a pasta onde já está não faz nada.
                if mover && e.loc.pai == destino { continue }
                let existentes = Set(try await Arquivos.listar(destino).map { $0.nome.lowercased() })
                let alvo = destino.filho(Self.nomeLivre(e.nome, isDir: e.isDir, existentes))
                if mover, await Arquivos.moverRapido(e.loc, alvo) {
                    prog.somar(e.isDir ? 0 : e.tamanho)
                    continue
                }
                try await Self.copiar(e, para: alvo, prog)
                if mover { try await Arquivos.apagar(e) }
            }
            espelho.cancel()
            op.estado?.itensFeitos = itens.count
            op.estado?.feitos = total
        }
    }

    nonisolated private static func copiar(_ e: FileEntry, para alvo: Loc, _ prog: Progresso) async throws {
        try Task.checkCancellation()
        prog.mudar(e.nome)
        if e.isDir {
            guard let pai = alvo.pai else { return }
            _ = try await Arquivos.criarPasta(pai, alvo.nome)
            for filho in try await Arquivos.listar(e.loc) {
                try await copiar(filho, para: alvo.filho(filho.nome), prog)
            }
            return
        }
        let leitor = try await Arquivos.abrirLeitura(e.loc)
        let gravador: Gravador
        do {
            gravador = try await Arquivos.abrirGravacao(alvo)
        } catch {
            await leitor.fechar()
            throw error
        }
        do {
            var copiados: Int64 = 0
            while true {
                try Task.checkCancellation()
                let d = try await leitor.ler(1 << 20)
                if d.isEmpty { break }
                try await gravador.gravar(d)
                copiados += Int64(d.count)
                prog.somar(Int64(d.count))
            }
            // Um arquivo que "acabou" antes do tamanho que o servidor
            // informou ficou cortado: melhor falhar do que entregar metade.
            if case .smb = e.loc, copiados < e.tamanho {
                throw SmbErro.caiu
            }
            try await gravador.concluir()
            await leitor.fechar()
        } catch {
            await leitor.fechar()
            await gravador.descartar()
            throw error
        }
        if case .local = alvo { LocalFs.definirData(alvo, e.modificado) }
    }

    /// "foto.jpg" → "foto (1).jpg" se já existir: nunca sobrescreve em silêncio.
    nonisolated static func nomeLivre(_ nome: String, isDir: Bool, _ existentes: Set<String>) -> String {
        guard existentes.contains(nome.lowercased()) else { return nome }
        let ns = nome as NSString
        let semExt = isDir || ns.pathExtension.isEmpty || nome.hasPrefix(".")
        let base = semExt ? nome : ns.deletingPathExtension
        let ext = semExt ? "" : "." + ns.pathExtension
        var i = 1
        while existentes.contains("\(base) (\(i))\(ext)".lowercased()) { i += 1 }
        return "\(base) (\(i))\(ext)"
    }
}

/// O contador da cópia, escrito pela tarefa que copia e lido pela tela.
final class Progresso: @unchecked Sendable {
    private let trava = NSLock()
    private var feitos: Int64 = 0
    private var atual = ""
    func somar(_ n: Int64) { trava.lock(); feitos += n; trava.unlock() }
    func mudar(_ nome: String) { trava.lock(); atual = nome; trava.unlock() }
    func ler() -> (Int64, String) { trava.lock(); defer { trava.unlock() }; return (feitos, atual) }
}

/// O progresso da operação em andamento — observado só pelo painel.
@MainActor
final class PainelDaOperacao: ObservableObject {
    static let shared = PainelDaOperacao()
    @Published var estado: OpEstado?
    private init() {}
}
