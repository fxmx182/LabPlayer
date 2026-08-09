import Foundation

/// Fachada fina sobre a biblioteca C.
///
/// Por enquanto serve para uma coisa só, e importante: provar que o
/// xcframework realmente linkou. Compilar e linkar são etapas distintas — dá
/// para ter a biblioteca no lugar e o app ainda assim falhar no link por falta
/// de uma dependência de sistema (libz, libiconv, VideoToolbox). Mostrar a
/// versão do FFmpeg na tela fecha essa dúvida no aparelho, não no CI.
enum FFmpegRuntime {

    /// Ex.: "7.1.1"
    static var version: String {
        String(cString: av_version_info())
    }

    /// A linha de `./configure` com que a biblioteca foi construída.
    static var configuration: String {
        String(cString: avcodec_configuration())
    }

    /// Quantos contêineres o build conhece — a prova de que o
    /// `--disable-everything` deixou passar o que devia.
    static var demuxerCount: Int {
        var opaque: UnsafeMutableRawPointer?
        var count = 0
        while av_demuxer_iterate(&opaque) != nil { count += 1 }
        return count
    }

    /// Silencia o log do FFmpeg em Release; em Debug deixa o essencial.
    static func configureLogging() {
        #if DEBUG
        av_log_set_level(AV_LOG_WARNING)
        #else
        av_log_set_level(AV_LOG_QUIET)
        #endif
    }
}
