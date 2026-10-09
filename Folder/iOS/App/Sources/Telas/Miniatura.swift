import SwiftUI
import QuickLookThumbnailing
import ImageIO

/// As miniaturas de imagem, vídeo e PDF.
///
/// No aparelho é o gerador do sistema (o mesmo do app Arquivos), que já sabe
/// HEIC, vídeo e PDF. No servidor, só imagem até 25 MB — como no Android:
/// vídeo exigiria ler pedaços espalhados de um arquivo enorme pela rede.
actor GeradorDeMiniaturas {
    static let shared = GeradorDeMiniaturas()

    private var cache: NSCache<NSString, UIImage> { CacheDeMiniaturas.cache }
    /// Duas leituras de servidor por vez: a lista inteira pedindo miniatura
    /// junto travaria a navegação, que usa a mesma conexão.
    private var smbAtivos = 0
    private var fila: [CheckedContinuation<Void, Never>] = []

    nonisolated static func podeTer(_ e: FileEntry) -> Bool {
        guard !e.isDir, !e.naNuvem else { return false }
        let t = Mime.tipo(e)
        switch e.loc {
        case .local: return t == .imagem || t == .video || t == .pdf
        case .smb: return t == .imagem && (1...25_000_000).contains(e.tamanho)
        }
    }

    nonisolated private static func chave(_ e: FileEntry, _ lado: CGFloat) -> NSString {
        "\(e.loc)|\(e.modificado?.timeIntervalSince1970 ?? 0)|\(e.tamanho)|\(Int(lado))" as NSString
    }

    nonisolated func emCache(_ e: FileEntry, lado: CGFloat) -> UIImage? {
        CacheDeMiniaturas.cache.object(forKey: Self.chave(e, lado))
    }

    func imagem(_ e: FileEntry, lado: CGFloat, escala: CGFloat) async -> UIImage? {
        let k = Self.chave(e, lado)
        if let i = cache.object(forKey: k) { return i }
        let img: UIImage?
        switch e.loc {
        case .local:
            img = await Self.local(e, lado: lado, escala: escala)
        case .smb:
            await vez()
            img = await Self.doServidor(e, lado: lado * escala)
            liberar()
        }
        if let img { cache.setObject(img, forKey: k) }
        return img
    }

    private func vez() async {
        if smbAtivos < 2 { smbAtivos += 1; return }
        await withCheckedContinuation { fila.append($0) }
    }

    private func liberar() {
        if fila.isEmpty { smbAtivos -= 1 } else { fila.removeFirst().resume() }
    }

    nonisolated private static func local(_ e: FileEntry, lado: CGFloat, escala: CGFloat) async -> UIImage? {
        guard let url = Raizes.url(de: e.loc) else { return nil }
        let pedido = QLThumbnailGenerator.Request(fileAt: url, size: CGSize(width: lado, height: lado),
                                                  scale: escala, representationTypes: .thumbnail)
        return try? await QLThumbnailGenerator.shared.generateBestRepresentation(for: pedido).uiImage
    }

    nonisolated private static func doServidor(_ e: FileEntry, lado: CGFloat) async -> UIImage? {
        guard let leitor = try? await SmbFs.abrirLeitura(e.loc, dedicada: false) else { return nil }
        var dados = Data()
        while !Task.isCancelled {
            guard let d = try? await leitor.ler(1 << 20), !d.isEmpty else { break }
            dados.append(d)
        }
        await leitor.fechar()
        guard let fonte = CGImageSourceCreateWithData(dados as CFData, nil) else { return nil }
        let op: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: Int(lado),
        ]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(fonte, 0, op as CFDictionary) else { return nil }
        return UIImage(cgImage: cg)
    }
}

/// O NSCache já é seguro entre threads; fica fora do ator para a tela poder
/// consultá-lo sem esperar a vez.
enum CacheDeMiniaturas {
    static let cache: NSCache<NSString, UIImage> = {
        let c = NSCache<NSString, UIImage>()
        c.countLimit = 600
        return c
    }()
}

/// Ícone colorido do tipo, ou a miniatura real para imagem, vídeo e PDF.
struct Miniatura: View {
    let e: FileEntry
    var lado: CGFloat = 44
    var raio: CGFloat = 13
    /// Na grade, a placa ocupa a célula inteira.
    var preencher = false

    @Environment(\.displayScale) private var escala
    @State private var img: UIImage?

    var body: some View {
        // A forma define o tamanho e a imagem vai por cima: `.scaledToFill()`
        // dentro de uma pilha faz a pilha adotar o tamanho da imagem (lição
        // paga no Player, com as capas se sobrepondo).
        base
            .overlay {
                if let img {
                    Image(uiImage: img).resizable().scaledToFill().allowsHitTesting(false)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: raio, style: .continuous))
            .overlay {
                if img != nil {
                    RoundedRectangle(cornerRadius: raio, style: .continuous).strokeBorder(Lx.vidroBorda, lineWidth: 0.5)
                }
            }
            .overlay(alignment: .bottomTrailing) {
                if e.naNuvem {
                    Image(systemName: "icloud.and.arrow.down")
                        .font(.system(size: preencher ? 14 : 10, weight: .bold))
                        .foregroundStyle(Lx.apagado)
                        .padding(preencher ? 6 : 2)
                }
            }
            .task(id: e.loc) {
                guard GeradorDeMiniaturas.podeTer(e) else { img = nil; return }
                let alvo = preencher ? 240 : lado
                if let c = GeradorDeMiniaturas.shared.emCache(e, lado: alvo) { img = c; return }
                img = await GeradorDeMiniaturas.shared.imagem(e, lado: alvo, escala: escala)
            }
    }

    @ViewBuilder private var base: some View {
        let cor = Mime.cor(e)
        let pasta = e.isDir
        if preencher {
            // Na grade a placa é grande: em ouro, uma pasta atrás da outra vira
            // uma parede. A da pasta é de vidro, e só o desenho fica dourado.
            RoundedRectangle(cornerRadius: raio, style: .continuous)
                .fill(pasta ? AnyShapeStyle(Lx.vidro)
                            : AnyShapeStyle(LinearGradient(colors: [cor.opacity(0.26), cor.opacity(0.09)],
                                                           startPoint: .topLeading, endPoint: .bottomTrailing)))
                .overlay(RoundedRectangle(cornerRadius: raio, style: .continuous)
                    .strokeBorder(pasta ? Lx.vidroBorda : cor.opacity(0.20), lineWidth: 0.5))
                .overlay(Image(systemName: Mime.simbolo(e))
                    .font(.system(size: pasta ? 44 : 36, weight: .semibold))
                    .foregroundStyle(cor))
                .aspectRatio(1, contentMode: .fit)
        } else {
            Selo(cor: cor, simbolo: Mime.simbolo(e), tamanho: lado, raio: raio, forte: !pasta)
        }
    }
}
