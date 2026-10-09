import SwiftUI

@main
struct FolderApp: App {
    init() {
        Self.prepararPastaDoApp()
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

    /// A pasta do app aparece no Arquivos ("No iPhone › LibertyX Folder") só
    /// se tiver um arquivo de verdade dentro — vazia, o iOS esconde (lição do
    /// Player). Um LEIA-ME resolve, e explica o porquê de existir.
    private static func prepararPastaDoApp() {
        let fm = FileManager.default
        let docs = Lugares.urlDocs
        let conteudo = (try? fm.contentsOfDirectory(at: docs, includingPropertiesForKeys: [.isDirectoryKey])) ?? []
        let temArquivo = conteudo.contains { (try? $0.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory == false }
        guard !temArquivo else { return }
        let texto = String(localized: """
        LibertyX Folder
        ===============

        Esta é a pasta do LibertyX Folder no iPhone. Ela aparece no app Arquivos em No iPhone › LibertyX Folder, e tudo o que estiver aqui o LibertyX Folder vê sem pedir nada.

        Para o app ver outras pastas — iCloud Drive, No iPhone, um pendrive ou a pasta de outro app —, toque em Adicionar pasta na tela inicial e escolha a pasta no seletor do sistema. O acesso fica salvo.

        Atenção: apagar o app apaga junto tudo o que estiver nesta pasta. Para arquivos importantes, prefira uma pasta do iCloud Drive autorizada no app.

        Este arquivo existe porque o iOS esconde a pasta de um app enquanto ela está vazia. Pode apagá-lo depois de colocar algo aqui.
        """)
        try? texto.write(to: docs.appendingPathComponent("LEIA-ME.txt"), atomically: true, encoding: .utf8)
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
