import Foundation
import SwiftUI

// O app fala a língua do aparelho: inglês (o padrão), português e espanhol,
// como no Android. Os textos ficam em português no código — são a chave de
// busca nas tabelas de `en.lproj` e `es.lproj`; em português, sem tradução a
// achar, sai a própria chave. Armadilha já paga no Player: `Text(variavel)` e
// ternário de literais NÃO traduzem — o que for texto fixo guardado em
// variável passa por `String(localized:)`.

/// Quantidades com o plural certo de cada língua ("1 item", "2 itens"). As
/// formas moram em `Localizable.stringsdict`.
enum Plural {
    static func itens(_ n: Int) -> String { String(localized: "\(n) itens") }
    static func arquivos(_ n: Int) -> String { String(localized: "\(n) arquivos") }
    static func pastas(_ n: Int) -> String { String(localized: "\(n) pastas") }
    static func selecionados(_ n: Int) -> String { String(localized: "\(n) selecionados") }
    static func excluirTitulo(_ n: Int) -> String { String(localized: "Excluir \(n) itens?") }
    static func colar(_ n: Int, mover: Bool) -> String {
        mover ? String(localized: "Mover \(n) itens — abra a pasta de destino")
              : String(localized: "Copiar \(n) itens — abra a pasta de destino")
    }
}

enum Fmt {
    /// Tamanho em base 1024, com a vírgula ou o ponto do idioma: 1,5 GB / 1.5 GB.
    static func tamanho(_ b: Int64) -> String {
        if b < 1024 { return "\(b) B" }
        let u = ["KB", "MB", "GB", "TB"]
        var v = Double(b) / 1024
        var i = 0
        while v >= 1024 && i < u.count - 1 { v /= 1024; i += 1 }
        let casas = v >= 100 ? 0 : 1
        return v.formatted(.number.precision(.fractionLength(casas))) + " " + u[i]
    }

    static func data(_ d: Date?) -> String {
        guard let d, d.timeIntervalSince1970 > 0 else { return "" }
        return d.formatted(date: .abbreviated, time: .shortened)
    }

    /// "12.345.678" — os bytes exatos dos Detalhes, no formato do país.
    static func bytes(_ b: Int64) -> String { b.formatted(.number) }
}

/// Identidade do build, lida do Info.plist. O commit é carimbado pelo CI.
enum AppBuild {
    static var versao: String { info("CFBundleShortVersionString") ?? "?" }
    static var commit: String { info("FolderBuild") ?? "?" }
    private static func info(_ k: String) -> String? { Bundle.main.object(forInfoDictionaryKey: k) as? String }
}

enum Ordem: String, CaseIterable, Identifiable {
    case nome, data, tamanho, tipo
    var id: String { rawValue }
    var titulo: String {
        switch self {
        case .nome: return String(localized: "Nome")
        case .data: return String(localized: "Data")
        case .tamanho: return String(localized: "Tamanho")
        case .tipo: return String(localized: "Tipo")
        }
    }
}

/// As preferências de exibição, as mesmas do Android: ordem, sentido, grade e
/// ocultos. Valem para todas as pastas.
@MainActor
final class Prefs: ObservableObject {
    static let shared = Prefs()
    private let d = UserDefaults.standard

    @Published var ordem: Ordem { didSet { d.set(ordem.rawValue, forKey: "sort") } }
    @Published var crescente: Bool { didSet { d.set(crescente, forKey: "asc") } }
    @Published var grade: Bool { didSet { d.set(grade, forKey: "grid") } }
    @Published var ocultos: Bool { didSet { d.set(ocultos, forKey: "hidden") } }

    private init() {
        ordem = Ordem(rawValue: d.string(forKey: "sort") ?? "") ?? .nome
        crescente = d.object(forKey: "asc") as? Bool ?? true
        grade = d.bool(forKey: "grid")
        ocultos = d.bool(forKey: "hidden")
    }

    /// Pastas primeiro; dentro de cada grupo, a ordem escolhida, com desempate
    /// natural ("Episódio 2" antes de "Episódio 10").
    func ordenar(_ lista: [FileEntry]) -> [FileEntry] {
        let visiveis = lista.filter { ocultos || !$0.oculto }
        return visiveis.sorted { a, b in
            if a.isDir != b.isDir { return a.isDir }
            let r: ComparisonResult
            switch ordem {
            case .nome: r = .orderedSame
            case .data: r = Self.comparar(a.modificado ?? .distantPast, b.modificado ?? .distantPast)
            case .tamanho: r = Self.comparar(a.tamanho, b.tamanho)
            case .tipo: r = a.ext.compare(b.ext)
            }
            let final = r != .orderedSame ? r : a.nome.localizedStandardCompare(b.nome)
            return crescente ? final == .orderedAscending : final == .orderedDescending
        }
    }

    private static func comparar<T: Comparable>(_ a: T, _ b: T) -> ComparisonResult {
        a < b ? .orderedAscending : (a > b ? .orderedDescending : .orderedSame)
    }
}
