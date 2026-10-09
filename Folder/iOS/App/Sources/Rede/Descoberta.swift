import Foundation
import Network

/// Acha servidores SMB na rede sozinho — o mesmo desenho do Android:
///
/// - **Bonjour** (`_smb._tcp`): NAS, Mac e Linux com Avahi se anunciam com nome.
/// - **Varredura da porta 445** na sub-rede: acha Windows e Samba que não se
///   anunciam. Só sabe o IP…
/// - …por isso cada IP achado recebe uma **consulta NetBIOS** (UDP 137), que é
///   como o Windows diz o próprio nome (`DESKTOP-ABC`). Sem resposta, fica o IP.
///
/// Varre também a rede de cada servidor salvo: Wi-Fi de convidados, repetidor
/// ou VPN põem o aparelho numa rede e o servidor em outra.
///
/// Nada disso pede entitlement: o Bonjour é o do sistema, e a consulta NetBIOS
/// vai direto para um endereço (unicast) — só o broadcast exigiria a permissão
/// de multicast da Apple. O que o iOS pede é a permissão de rede local, uma
/// vez, na primeira busca.
@MainActor
final class Descobertas: ObservableObject {
    static let shared = Descobertas()

    struct Achado: Identifiable, Hashable {
        var id: String { host }
        var host: String
        var nome: String
        var viaBonjour: Bool
    }

    @Published private(set) var achados: [Achado] = []
    @Published private(set) var buscando = false

    private var navegador: NWBrowser?
    private var tarefa: Task<Void, Never>?

    private init() {}

    func buscar() {
        guard !buscando else { return }
        buscando = true
        let salvos = Servidores.shared.lista.map(\.host)
        iniciarBonjour()
        tarefa = Task { [weak self] in
            await Self.varrer(conhecidos: salvos) { ip, nome in
                await self?.adicionar(Achado(host: ip, nome: nome ?? ip, viaBonjour: false))
            }
            // O Bonjour responde nos primeiros segundos; deixá-lo aberto além
            // da varredura só gastaria bateria.
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            await MainActor.run {
                self?.navegador?.cancel()
                self?.navegador = nil
                self?.buscando = false
            }
        }
    }

    private func adicionar(_ chegou: Achado) {
        var novo = chegou
        novo.host = Self.semInterface(novo.host)
        var lista = achados
        if let i = lista.firstIndex(where: { $0.host == novo.host }) {
            // O nome é melhor que o IP puro.
            if lista[i].nome == lista[i].host && novo.nome != novo.host { lista[i].nome = novo.nome }
        } else {
            lista.append(novo)
        }
        // A mesma máquina vista pelos dois caminhos (Bonjour e varredura) pode
        // chegar com endereços escritos diferente, e o nome só aparece depois
        // (a consulta NetBIOS vem depois do IP). Então, a cada chegada, um por
        // nome — e fica o de IP puro, que é o que conecta.
        var vistos: [String: Int] = [:]
        var limpa: [Achado] = []
        for a in lista {
            guard a.nome != a.host else { limpa.append(a); continue }
            let k = a.nome.lowercased()
            if let j = vistos[k] {
                if !Self.ehIPv4(limpa[j].host) && Self.ehIPv4(a.host) { limpa[j] = a }
            } else {
                vistos[k] = limpa.count
                limpa.append(a)
            }
        }
        limpa.sort { $0.nome.localizedStandardCompare($1.nome) == .orderedAscending }
        if limpa != achados { achados = limpa }
    }

    /// "192.168.50.113%en0" → "192.168.50.113". O Bonjour devolve o endereço
    /// com a interface de rede grudada; para o SMB e para comparar com os
    /// servidores salvos, é o mesmo IP.
    nonisolated static func semInterface(_ host: String) -> String {
        guard let i = host.firstIndex(of: "%") else { return host }
        return String(host[..<i])
    }

    nonisolated private static func ehIPv4(_ h: String) -> Bool {
        let p = h.split(separator: ".")
        return p.count == 4 && p.allSatisfy { UInt8($0) != nil }
    }

    // MARK: - Bonjour

    private func iniciarBonjour() {
        let p = NWParameters()
        p.includePeerToPeer = false
        let n = NWBrowser(for: .bonjour(type: "_smb._tcp", domain: nil), using: p)
        n.browseResultsChangedHandler = { [weak self] resultados, _ in
            for r in resultados {
                guard case .service(let nome, _, _, _) = r.endpoint else { continue }
                let endpoint = r.endpoint
                Task { [weak self] in
                    // O nome anunciado pode ter espaço ("NAS de Casa") e não
                    // serve de endereço; o IP sai de uma conexão rápida.
                    let ip = await Self.resolver(endpoint)
                    let host = ip ?? (nome.contains(" ") ? nil : "\(nome).local")
                    guard let host else { return }
                    await self?.adicionar(Achado(host: host, nome: nome, viaBonjour: true))
                }
            }
        }
        n.start(queue: .global(qos: .userInitiated))
        navegador = n
    }

    nonisolated private static func resolver(_ endpoint: NWEndpoint) async -> String? {
        await withCheckedContinuation { cont in
            let uma = UmaVez()
            let p = NWParameters.tcp
            if let ip = p.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options { ip.version = .v4 }
            let c = NWConnection(to: endpoint, using: p)
            func fim(_ v: String?) { if uma.marcar() { c.cancel(); cont.resume(returning: v) } }
            c.stateUpdateHandler = { estado in
                switch estado {
                case .ready:
                    if case .hostPort(let h, _)? = c.currentPath?.remoteEndpoint, case .ipv4(let a) = h {
                        fim(semInterface("\(a)"))
                    } else { fim(nil) }
                case .failed, .cancelled: fim(nil)
                default: break
                }
            }
            c.start(queue: .global(qos: .userInitiated))
            DispatchQueue.global().asyncAfter(deadline: .now() + 3) { fim(nil) }
        }
    }

    // MARK: - Varredura

    nonisolated private static func varrer(conhecidos: [String], achou: @escaping (String, String?) async -> Void) async {
        var alvos: [String] = []
        if let p = prefixoDaRede() { alvos.append(p) }
        for h in conhecidos { if let p = prefixoPrivado(h), !alvos.contains(p) { alvos.append(p) } }
        let enderecos = alvos.flatMap { p in (1...254).map { "\(p).\($0)" } }
        guard !enderecos.isEmpty else { return }

        // 32 por vez: rápido para 254 endereços em segundos, leve o bastante
        // para não afogar o Wi-Fi.
        await withTaskGroup(of: Void.self) { grupo in
            var proximo = 0
            func enfileirar() {
                guard proximo < enderecos.count else { return }
                let ip = enderecos[proximo]
                proximo += 1
                grupo.addTask {
                    guard await responde(ip, prazo: 1.2) else { return }
                    await achou(ip, nil)
                    if let nome = await nomeNetbios(ip) { await achou(ip, nome) }
                }
            }
            for _ in 0..<32 { enfileirar() }
            while await grupo.next() != nil {
                if Task.isCancelled { break }
                enfileirar()
            }
        }
    }

    /// Uma conexão TCP que completa na porta 445 é um servidor SMB — não
    /// precisa autenticar para saber que ele existe.
    nonisolated private static func responde(_ host: String, prazo: Double) async -> Bool {
        await withCheckedContinuation { cont in
            let uma = UmaVez()
            let c = NWConnection(host: NWEndpoint.Host(host), port: 445, using: .tcp)
            c.stateUpdateHandler = { estado in
                switch estado {
                case .ready: if uma.marcar() { c.cancel(); cont.resume(returning: true) }
                case .failed, .cancelled, .waiting: if uma.marcar() { c.cancel(); cont.resume(returning: false) }
                default: break
                }
            }
            c.start(queue: .global(qos: .userInitiated))
            DispatchQueue.global().asyncAfter(deadline: .now() + prazo) {
                if uma.marcar() { c.cancel(); cont.resume(returning: false) }
            }
        }
    }

    /// Nome NetBIOS da máquina (consulta NBSTAT em UDP 137), byte a byte como
    /// no Android. Resposta: cabeçalho 12 + nome 34 + tipo/classe/ttl/tamanho
    /// 10 = 56, então o número de nomes está no byte 56 e cada um ocupa 18.
    nonisolated static func nomeNetbios(_ ip: String, prazo: Double = 0.8) async -> String? {
        var q = [UInt8](repeating: 0, count: 50)
        q[0] = 0x13; q[1] = 0x37
        q[5] = 1
        q[12] = 0x20
        var nome = [UInt8](repeating: 0, count: 16)
        nome[0] = UInt8(ascii: "*")
        for i in 0..<16 {
            let b = nome[i]
            q[13 + i * 2] = UInt8(ascii: "A") + (b >> 4)
            q[14 + i * 2] = UInt8(ascii: "A") + (b & 0x0F)
        }
        q[46] = 0; q[47] = 0x21; q[48] = 0; q[49] = 1
        let consulta = Data(q)

        return await withCheckedContinuation { cont in
            let uma = UmaVez()
            let c = NWConnection(host: NWEndpoint.Host(ip), port: 137, using: .udp)
            func fim(_ v: String?) { if uma.marcar() { c.cancel(); cont.resume(returning: v) } }
            c.stateUpdateHandler = { estado in
                switch estado {
                case .ready:
                    c.send(content: consulta, completion: .contentProcessed { erro in if erro != nil { fim(nil) } })
                    c.receiveMessage { dados, _, _, _ in fim(dados.flatMap(lerNome)) }
                case .failed, .cancelled: fim(nil)
                default: break
                }
            }
            c.start(queue: .global(qos: .utility))
            DispatchQueue.global().asyncAfter(deadline: .now() + prazo) { fim(nil) }
        }
    }

    nonisolated private static func lerNome(_ d: Data) -> String? {
        let b = [UInt8](d)
        guard b.count >= 57 else { return nil }
        let n = Int(b[56])
        for i in 0..<n {
            let o = 57 + i * 18
            guard o + 18 <= b.count else { break }
            let sufixo = b[o + 15]
            let grupo = (b[o + 16] & 0x80) != 0
            if sufixo == 0x20 || (sufixo == 0 && !grupo) {
                let t = String(bytes: b[o..<(o + 15)], encoding: .isoLatin1)?
                    .trimmingCharacters(in: .whitespacesAndNewlines.union(CharacterSet(charactersIn: "\0"))) ?? ""
                if !t.isEmpty { return t }
            }
        }
        return nil
    }

    /// A rede de um servidor salvo, se ele estiver numa faixa privada IPv4.
    /// Tailscale (100.64/10) fica de fora: varrê-lo inteiro bateria em
    /// aparelhos de outras pessoas da mesma conta.
    nonisolated private static func prefixoPrivado(_ host: String) -> String? {
        let p = host.split(separator: ".").compactMap { Int($0) }
        guard p.count == 4, p.allSatisfy({ (0...255).contains($0) }) else { return nil }
        let privado = p[0] == 10 || (p[0] == 172 && (16...31).contains(p[1])) || (p[0] == 192 && p[1] == 168)
        return privado ? p.prefix(3).map(String.init).joined(separator: ".") : nil
    }

    /// Os três primeiros octetos do IP do aparelho no Wi-Fi (en0).
    nonisolated private static func prefixoDaRede() -> String? {
        var ponteiro: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ponteiro) == 0, let inicio = ponteiro else { return nil }
        defer { freeifaddrs(ponteiro) }
        var atual: UnsafeMutablePointer<ifaddrs>? = inicio
        while let i = atual {
            defer { atual = i.pointee.ifa_next }
            guard String(cString: i.pointee.ifa_name) == "en0",
                  let a = i.pointee.ifa_addr, a.pointee.sa_family == UInt8(AF_INET) else { continue }
            var texto = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            getnameinfo(a, socklen_t(a.pointee.sa_len), &texto, socklen_t(texto.count), nil, 0, NI_NUMERICHOST)
            let partes = String(cString: texto).split(separator: ".")
            if partes.count == 4 { return partes.prefix(3).joined(separator: ".") }
        }
        return nil
    }
}
