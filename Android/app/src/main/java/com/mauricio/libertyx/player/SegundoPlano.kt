package com.mauricio.libertyx.player

/**
 * A ponte entre o player e o serviço de áudio da variante de celular.
 *
 * Com a tela apagada, o player entrega o filme ao serviço, que segue só com o
 * som — o mesmo que atende o Android Auto. Ao voltar, o player pega de volta a
 * posição e o serviço para. A ponte é um objeto no processo, e não um
 * `bindService`, porque as duas pontas moram no mesmo processo e a variante de
 * TV nem tem o serviço: aqui o código comum não precisa conhecer a classe.
 */
object SegundoPlano {

    const val ACAO = "com.mauricio.libertyx.CONTINUAR_EM_AUDIO"
    const val EXTRA_POSICAO = "posicao"
    const val EXTRA_VELOCIDADE = "velocidade"

    /** A classe do serviço, só na variante de celular. */
    const val SERVICO = "com.mauricio.libertyx.auto.LibertyXMediaService"

    val disponivel: Boolean by lazy { runCatching { Class.forName(SERVICO) }.isSuccess }

    /** O serviço está tocando o que veio do player. */
    @Volatile var ativo = false

    /** Onde o áudio está agora, enquanto [ativo]. */
    var posicao: () -> Double = { 0.0 }
    var tocando: () -> Boolean = { false }
    var parar: () -> Unit = {}

    /** Onde o áudio parou, quando o serviço foi fechado pela notificação. */
    @Volatile var ultimaPosicao: Double? = null
}
