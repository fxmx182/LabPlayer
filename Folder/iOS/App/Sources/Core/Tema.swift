import SwiftUI
import CoreText

/// A linguagem visual do app — a mesma do LibertyX Folder do Android
/// (`ui/Theme.kt`), valor por valor.
///
/// Ouro sobre preto quente, superfícies de vidro (um branco quase transparente
/// com a borda um pouco mais viva, que é o que dá a impressão de espessura),
/// cantos generosos e tipografia apertada. O app é escuro sempre, e não só à
/// noite: a marca é ouro, e ouro sobre fundo claro vira mostarda.
enum Lx {
    /// O amarelo da marca, colhido do ícone.
    static let ouro = Color(hex: 0xFBC501)
    static let ouroClaro = Color(hex: 0xFFE08A)
    static let ouroEscuro = Color(hex: 0xB97A06)

    static let verde = Color(hex: 0x35D0BE)
    static let vermelho = Color(hex: 0xFF5A5A)

    /// Os três níveis de texto.
    static let texto = Color.white.opacity(0.94)
    static let apagado = Color(hex: 0xEBEBF5).opacity(0.60)
    static let tenue = Color(hex: 0xEBEBF5).opacity(0.38)

    static let vidro = Color.white.opacity(0.055)
    static let vidroForte = Color.white.opacity(0.10)
    static let vidroBorda = Color.white.opacity(0.11)

    /// Preto com um resto de marrom, o mesmo do fundo do ícone.
    static let fundo = Color(hex: 0x0C0B0A)
    static let superficie = Color(hex: 0x16151A)

    static let raioCartao: CGFloat = 22
    static let raioPequeno: CGFloat = 14

    static let ouroUI = UIColor(red: 0.984, green: 0.773, blue: 0.004, alpha: 1)
    static let fundoUI = UIColor(red: 0.047, green: 0.043, blue: 0.039, alpha: 1)
}

extension Color {
    init(hex: UInt32, opacity: Double = 1) {
        self.init(.sRGB,
                  red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255,
                  opacity: opacity)
    }
}

/// A letra do app: Manrope, a mesma do Android e do Player. Números de largura
/// constante — tamanhos e datas alinham sozinhos numa lista. Se o arquivo não
/// carregar, cai na do sistema no mesmo peso, nunca num texto quebrado.
enum LxFonte {
    private static func nome(_ peso: UIFont.Weight) -> String {
        switch peso {
        case .heavy, .black: return "Manrope-ExtraBold"
        case .bold:          return "Manrope-Bold"
        case .semibold:      return "Manrope-SemiBold"
        case .medium:        return "Manrope-Medium"
        default:             return "Manrope-Regular"
        }
    }

    static func ui(_ tamanho: CGFloat, _ peso: UIFont.Weight = .regular) -> UIFont {
        UIFont(name: nome(peso), size: tamanho) ?? .systemFont(ofSize: tamanho, weight: peso)
    }

    static func f(_ tamanho: CGFloat, _ peso: UIFont.Weight = .regular) -> Font {
        Font.custom(nome(peso), size: tamanho)
    }

    /// O nome do app na tela inicial — pesado e apertado, como capa de revista.
    static let titulao = f(30, .heavy)
}

extension View {
    /// Uma superfície de vidro: cartão, linha, painel.
    func vidro(_ raio: CGFloat = Lx.raioCartao, cor: Color = Lx.vidro, borda: Color = Lx.vidroBorda) -> some View {
        background(RoundedRectangle(cornerRadius: raio, style: .continuous).fill(cor))
            .overlay(RoundedRectangle(cornerRadius: raio, style: .continuous).strokeBorder(borda, lineWidth: 0.5))
    }
}

/// A luz de cima: um brilho dourado bem fraco atrás de tudo, no alto à
/// esquerda, e uma segunda luz fria no rodapé. Sem isso o preto fica chapado e
/// o vidro não parece vidro — é a única coisa que dá profundidade à tela.
struct BrilhoDeFundo: View {
    var body: some View {
        GeometryReader { g in
            let w = g.size.width, h = g.size.height
            ZStack {
                Lx.fundo
                RadialGradient(colors: [Lx.ouro.opacity(0.11), .clear],
                               center: UnitPoint(x: 0.12, y: -0.04),
                               startRadius: 0, endRadius: w * 1.15)
                RadialGradient(colors: [Color(hex: 0x3A2E6E).opacity(0.13), .clear],
                               center: UnitPoint(x: 1.05, y: 1.02),
                               startRadius: 0, endRadius: max(w, 1) * 0.95)
            }
            .frame(width: w, height: h)
        }
        .ignoresSafeArea()
    }
}

/// Título de seção: pequeno, em caixa alta e espaçado — o contrário do corpo,
/// que é apertado. A diferença entre os dois separa as seções sem linha.
struct TituloDeSecao: View {
    let texto: LocalizedStringKey
    var body: some View {
        Text(texto)
            .font(LxFonte.f(11, .bold))
            .textCase(.uppercase)
            .tracking(1.6)
            .foregroundStyle(Lx.tenue)
            .padding(.leading, 26).padding(.trailing, 16)
            .padding(.top, 24).padding(.bottom, 10)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// O cartão de vidro que agrupa uma seção da tela inicial.
struct Cartao<Conteudo: View>: View {
    @ViewBuilder let conteudo: Conteudo
    var body: some View {
        VStack(spacing: 0) { conteudo }
            .padding(.vertical, 6)
            .frame(maxWidth: .infinity)
            .vidro()
            .padding(.horizontal, 16)
    }
}

/// O selo de um tipo de arquivo: a cor do tipo em degradê num quadrado de
/// cantos vivos. Quadrado, e não círculo: ao lado de miniaturas de foto — que
/// são retângulos — o círculo quebrava o alinhamento da coluna.
struct Selo: View {
    let cor: Color
    let simbolo: String
    var tamanho: CGFloat = 42
    var raio: CGFloat = Lx.raioPequeno
    var forte = true

    var body: some View {
        let fim = forte ? 0.30 : 0.15
        RoundedRectangle(cornerRadius: raio, style: .continuous)
            .fill(LinearGradient(colors: [cor.opacity(fim), cor.opacity(fim / 3)],
                                 startPoint: .topLeading, endPoint: .bottomTrailing))
            .overlay(RoundedRectangle(cornerRadius: raio, style: .continuous)
                .strokeBorder(cor.opacity(forte ? 0.22 : 0.13), lineWidth: 0.5))
            .overlay(Image(systemName: simbolo)
                .font(.system(size: tamanho * 0.46, weight: .semibold))
                .foregroundStyle(cor))
            .frame(width: tamanho, height: tamanho)
    }
}

/// A barra de uso do armazenamento — em ouro, e vermelha quando aperta.
struct BarraDeUso: View {
    let usado: Int64
    let total: Int64
    var body: some View {
        let f = total > 0 ? min(max(Double(usado) / Double(total), 0), 1) : 0
        let cores = f > 0.9 ? [Color(hex: 0xFF8A5A), Lx.vermelho] : [Lx.ouroClaro, Lx.ouroEscuro]
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(Lx.vidroForte)
                Capsule().fill(LinearGradient(colors: cores, startPoint: .leading, endPoint: .trailing))
                    .frame(width: g.size.width * f)
            }
        }
        .frame(height: 5)
    }
}

struct EstadoVazio: View {
    let texto: String
    var sub: String? = nil
    var simbolo: String? = nil
    var body: some View {
        VStack(spacing: 0) {
            if let simbolo {
                Circle().fill(Lx.vidro)
                    .overlay(Circle().strokeBorder(Lx.vidroBorda, lineWidth: 0.5))
                    .overlay(Image(systemName: simbolo).font(.system(size: 28)).foregroundStyle(Lx.tenue))
                    .frame(width: 72, height: 72)
            }
            Text(texto).font(LxFonte.f(17, .semibold)).foregroundStyle(Lx.apagado).padding(.top, 16)
            if let sub {
                Text(sub).font(LxFonte.f(13)).foregroundStyle(Lx.tenue)
                    .multilineTextAlignment(.center).padding(.top, 6)
            }
        }
        .padding(48)
        .frame(maxWidth: .infinity)
    }
}

/// Uma linha de cartão: selo, título e subtítulo. A mesma `NetRow` do Android.
struct LinhaDeCartao: View {
    let simbolo: String
    let titulo: String
    let sub: String
    var cor: Color = Lx.ouro
    var acao: () -> Void

    var body: some View {
        Button(action: acao) {
            HStack(spacing: 16) {
                Selo(cor: cor, simbolo: simbolo)
                VStack(alignment: .leading, spacing: 2) {
                    Text(titulo).font(LxFonte.f(16, .medium)).foregroundStyle(Lx.texto).lineLimit(1)
                    if !sub.isEmpty {
                        Text(sub).font(LxFonte.f(12)).foregroundStyle(Lx.tenue).lineLimit(2)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 20).padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// O tamanho das coisas em tela grande, como o `Escala` do Player.
enum Escala {
    static var tablet: Bool { UIDevice.current.userInterfaceIdiom == .pad }
    /// Teto de largura de listas e painéis: de ponta a ponta num iPad deitado,
    /// o nome ficava de um lado da tela e a ação do outro.
    static let larguraMaxima: CGFloat = 760
}
