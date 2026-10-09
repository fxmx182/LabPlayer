import SwiftUI

@main
struct FolderApp: App {
    init() {
        Abridor.limparAbertos()
        Self.aplicarLetraNaNavegacao()
    }

    var body: some Scene {
        WindowGroup {
            Raiz()
                .preferredColorScheme(.dark)
                .tint(Lx.ouro)
                .font(LxFonte.f(16, .medium))
        }
    }

    /// A Manrope também nos títulos da barra de navegação, que é do sistema e
    /// não enxerga a letra do ambiente do SwiftUI. Fundo transparente: o
    /// brilho da raiz passa por trás.
    private static func aplicarLetraNaNavegacao() {
        let a = UINavigationBarAppearance()
        a.configureWithTransparentBackground()
        a.titleTextAttributes = [.font: LxFonte.ui(17, .bold), .foregroundColor: UIColor.white]
        a.largeTitleTextAttributes = [.font: LxFonte.ui(32, .heavy), .foregroundColor: UIColor.white]
        let rolando = UINavigationBarAppearance()
        rolando.configureWithDefaultBackground()
        rolando.backgroundEffect = UIBlurEffect(style: .systemUltraThinMaterialDark)
        rolando.titleTextAttributes = a.titleTextAttributes
        rolando.largeTitleTextAttributes = a.largeTitleTextAttributes
        let barra = UINavigationBar.appearance()
        barra.standardAppearance = rolando
        barra.compactAppearance = rolando
        barra.scrollEdgeAppearance = a
        barra.tintColor = Lx.ouroUI
    }
}
