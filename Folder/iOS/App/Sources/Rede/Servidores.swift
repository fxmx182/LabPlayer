import Foundation
import Security

/// Um servidor SMB salvo. A senha **não** mora aqui — vai para o Keychain,
/// pelo `id`; isto vai para o UserDefaults, que é texto claro no backup.
struct SMBServer: Codable, Identifiable, Hashable {
    var id: UUID = UUID()
    var nome: String
    var host: String
    var porta: Int = 445
    var usuario: String = ""
    var dominio: String = ""
    var convidado: Bool = false

    var hostVisivel: String { porta == 445 ? host : "\(host):\(porta)" }

    /// "192.168.0.10 · convidado" / "192.168.0.10 · mauricio".
    var subtitulo: String {
        if convidado { return String(localized: "\(hostVisivel) · convidado") }
        return usuario.isEmpty ? hostVisivel : "\(hostVisivel) · \(usuario)"
    }
}

@MainActor
final class Servidores: ObservableObject {
    static let shared = Servidores()

    @Published private(set) var lista: [SMBServer] = []
    private let chave = "folder.servidores"

    private init() {
        if let d = UserDefaults.standard.data(forKey: chave),
           let l = try? JSONDecoder().decode([SMBServer].self, from: d) { lista = l }
        CopiaDosServidores.definir(lista)
    }

    private func salvar() {
        if let d = try? JSONEncoder().encode(lista) { UserDefaults.standard.set(d, forKey: chave) }
        CopiaDosServidores.definir(lista)
    }

    func gravar(_ s: SMBServer, senha: String?) {
        if let i = lista.firstIndex(where: { $0.id == s.id }) { lista[i] = s } else { lista.append(s) }
        if s.convidado { Chaveiro.apagar(s.id) } else if let senha { Chaveiro.gravar(senha, s.id) }
        salvar()
        Task { await SmbFs.esquecer(s.id) }
    }

    func remover(_ s: SMBServer) {
        lista.removeAll { $0.id == s.id }
        Chaveiro.apagar(s.id)
        salvar()
        Task { await SmbFs.esquecer(s.id) }
    }

    func porId(_ id: UUID) -> SMBServer? { lista.first { $0.id == id } }
}

/// O que o sistema de arquivos precisa dos servidores, lido de qualquer thread.
enum CopiaDosServidores {
    private static let trava = NSLock()
    private static var porId: [UUID: SMBServer] = [:]
    static func definir(_ l: [SMBServer]) {
        trava.lock(); defer { trava.unlock() }
        porId = Dictionary(l.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
    }
    static func servidor(_ id: UUID) -> SMBServer? {
        trava.lock(); defer { trava.unlock() }
        return porId[id]
    }
}

/// Senhas de SMB no Keychain. `AfterFirstUnlock` e não `WhenUnlocked`: uma
/// cópia que continua com a tela apagada precisa poder reconectar.
enum Chaveiro {
    private static let servico = "com.mauricio.libertyx.files.smb"

    private static func consulta(_ id: UUID) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: servico,
         kSecAttrAccount as String: id.uuidString]
    }

    static func gravar(_ senha: String, _ id: UUID) {
        var a = consulta(id)
        SecItemDelete(a as CFDictionary)
        a[kSecValueData as String] = Data(senha.utf8)
        a[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(a as CFDictionary, nil)
    }

    static func ler(_ id: UUID) -> String? {
        var a = consulta(id)
        a[kSecReturnData as String] = true
        a[kSecMatchLimit as String] = kSecMatchLimitOne
        var r: CFTypeRef?
        guard SecItemCopyMatching(a as CFDictionary, &r) == errSecSuccess, let d = r as? Data else { return nil }
        return String(data: d, encoding: .utf8)
    }

    static func apagar(_ id: UUID) { SecItemDelete(consulta(id) as CFDictionary) }
}
