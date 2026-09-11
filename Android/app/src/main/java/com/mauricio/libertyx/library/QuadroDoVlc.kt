package com.mauricio.libertyx.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.mauricio.libertyx.player.Vlc
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Um quadro de um vídeo, tirado pelo próprio VLC.
 *
 * É o mesmo princípio do irmão de iOS: quem consegue tocar o arquivo consegue
 * tirar um quadro dele — e isso vale para o servidor, onde o Android não tem
 * nada pronto. O VLC já fala `smb://`, já sabe as credenciais e já abre MKV,
 * HEVC e tudo o que o player abre; um segundo decodificador só para miniatura
 * recusaria justamente os arquivos que o app promete tocar.
 *
 * O `libVLC` do Android não expõe o gerador de miniaturas, então a extração é
 * uma reprodução muda e invisível: o vídeo é desenhado numa superfície de um
 * [ImageReader], que nunca vai para a tela, e o primeiro quadro que chega
 * depois da busca vira a imagem.
 */
object QuadroDoVlc {

    class Resultado(val imagem: Bitmap, val duracaoMs: Long)

    private const val TAG = "LibertyX"
    private const val LARGURA = 320
    private const val ALTURA = 180

    /** Onde os quadros chegam — e onde a limpeza acontece, para não cruzar com eles. */
    private val fundo by lazy { Handler(HandlerThread("miniaturas").apply { start() }.looper) }

    /**
     * O formato em que o VLC entrega os pixels.
     *
     * O `ImageReader` só aceita quadros no formato com que foi criado, e quem
     * decide o formato é o VLC — no emulador, RGBX. Começa por ele; se um
     * aparelho responder com outro, a primeira extração descobre qual é e as
     * seguintes já nascem certas.
     */
    @Volatile private var formato = PixelFormat.RGBX_8888

    suspend fun extrair(
        context: Context,
        uri: Uri,
        opcoes: List<String>,
        prazoMs: Long = 20_000,
    ): Resultado? {
        val pedido = AtomicInteger(0)
        tentar(context, uri, opcoes, prazoMs, formato, pedido)?.let { return it }

        val certo = pedido.get()
        // Só os formatos de 4 bytes por pixel sabem virar Bitmap aqui.
        if (certo == formato || certo !in setOf(PixelFormat.RGBA_8888, PixelFormat.RGBX_8888)) return null
        Log.i(TAG, "miniatura: o VLC entrega pixels em 0x${certo.toString(16)}; ajustando")
        formato = certo
        return tentar(context, uri, opcoes, prazoMs, certo, AtomicInteger(0))
    }

    private suspend fun tentar(
        context: Context,
        uri: Uri,
        opcoes: List<String>,
        prazoMs: Long,
        formatoDoLeitor: Int,
        formatoPedido: AtomicInteger,
    ): Resultado? {
        val libVlc = Vlc.get(context)
        val leitor = ImageReader.newInstance(LARGURA, ALTURA, formatoDoLeitor, 2)
        val tocador = withContext(Dispatchers.Main) { MediaPlayer(libVlc) }

        val aberto = CompletableDeferred<Unit>()
        val quadro = CompletableDeferred<Bitmap?>()
        // Zero enquanto a busca não foi pedida: o que chega antes dela é o
        // começo do arquivo, quase sempre tela preta ou logotipo.
        val armadoEm = AtomicLong(0L)
        val tempoMinimo = AtomicLong(0L)

        leitor.setOnImageAvailableListener({ r ->
            try {
                val imagem = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val desde = armadoEm.get()
                    if (desde == 0L || SystemClock.uptimeMillis() - desde < 300) return@setOnImageAvailableListener
                    if (tocador.time < tempoMinimo.get()) return@setOnImageAvailableListener
                    quadro.complete(recortar(paraBitmap(imagem), tocador))
                } finally {
                    imagem.close()
                }
            } catch (e: UnsupportedOperationException) {
                // "The producer output buffer format 0x2 doesn't match the
                // ImageReader's configured buffer format 0x1" — a mensagem diz
                // qual formato o VLC está usando.
                Regex("buffer format 0x([0-9a-fA-F]+) doesn't match").find(e.message.orEmpty())
                    ?.let { formatoPedido.set(it.groupValues[1].toInt(16)) }
                quadro.complete(null)
            } catch (e: Exception) {
                Log.w(TAG, "miniatura: quadro ilegível ($e)")
                quadro.complete(null)
            }
        }, fundo)

        try {
            return withTimeoutOrNull(prazoMs) {
                withContext(Dispatchers.Main) {
                    tocador.setEventListener { evento ->
                        when (evento.type) {
                            MediaPlayer.Event.LengthChanged -> if (tocador.length > 0) aberto.complete(Unit)
                            // Há arquivo que não informa duração; esse não
                            // pode ficar esperando por ela até o prazo.
                            MediaPlayer.Event.Playing -> fundo.postDelayed({ aberto.complete(Unit) }, 1_500)
                            MediaPlayer.Event.EncounteredError, MediaPlayer.Event.EndReached -> {
                                aberto.complete(Unit)
                                quadro.complete(null)
                            }
                        }
                    }
                    val media = Media(libVlc, uri).apply {
                        opcoes.forEach { addOption(it) }
                        addOption(":no-audio")
                        addOption(":no-spu")
                        addOption(":no-sub-autodetect-file")
                        addOption(":network-caching=1500")
                        // Decodificação em software e saída por OpenGL: o
                        // decodificador de hardware escreve direto numa
                        // superfície própria, em formato que o ImageReader não
                        // lê, e a saída nativa impõe o tamanho do vídeo ao
                        // buffer. Pelo OpenGL, o quadro chega já no tamanho da
                        // miniatura, com quatro bytes por pixel.
                        setHWDecoderEnabled(false, false)
                        addOption(":vout=gles2")
                    }
                    tocador.media = media
                    media.release()
                    tocador.vlcVout.apply {
                        setVideoSurface(leitor.surface, null)
                        setWindowSize(LARGURA, ALTURA)
                        attachViews()
                    }
                    tocador.play()
                }

                aberto.await()
                if (quadro.isCompleted) return@withTimeoutOrNull null

                val duracao = tocador.length
                if (duracao > 0) {
                    // Um terço do caminho, como na miniatura local. A busca
                    // rápida cai no quadro-chave anterior, então a tolerância
                    // aceita chegar um pouco antes do ponto pedido.
                    val destino = duracao / 3
                    withContext(Dispatchers.Main) { tocador.setTime(destino, true) }
                    tempoMinimo.set(max(0L, destino - max(10_000L, duracao / 20)))
                } else {
                    tempoMinimo.set(1_000L)
                }
                armadoEm.set(SystemClock.uptimeMillis())

                quadro.await()?.let { Resultado(it, max(duracao, 0L)) }
            }
        } finally {
            withContext(NonCancellable) { limpar(tocador, leitor) }
        }
    }

    private suspend fun limpar(tocador: MediaPlayer, leitor: ImageReader) {
        withContext(Dispatchers.Main) {
            runCatching { tocador.setEventListener(null) }
            runCatching { tocador.vlcVout.detachViews() }
        }
        // Na mesma thread dos quadros: um que chegasse no meio da liberação
        // perguntaria o tempo a um tocador já destruído.
        withContext(fundo.asCoroutineDispatcher()) {
            runCatching { leitor.setOnImageAvailableListener(null, null) }
            runCatching { tocador.stop() }
            runCatching { tocador.release() }
            runCatching { leitor.close() }
        }
    }

    /** O buffer tem passo de linha próprio, maior que a largura em alguns aparelhos. */
    private fun paraBitmap(imagem: Image): Bitmap {
        val plano = imagem.planes[0]
        val larguraDaLinha = plano.rowStride / plano.pixelStride
        val bytes = ByteBuffer.allocateDirect(larguraDaLinha * imagem.height * 4)
        val origem = plano.buffer
        origem.limit(minOf(origem.capacity(), bytes.capacity()))
        bytes.put(origem)
        bytes.rewind()
        val cheio = Bitmap.createBitmap(larguraDaLinha, imagem.height, Bitmap.Config.ARGB_8888)
        cheio.copyPixelsFromBuffer(bytes)
        // Em RGBX o quarto byte não é transparência e pode vir zerado; sem
        // isto a miniatura seria desenhada invisível.
        cheio.setHasAlpha(false)
        return if (larguraDaLinha == imagem.width) cheio
        else Bitmap.createBitmap(cheio, 0, 0, imagem.width, imagem.height)
    }

    /**
     * Tira as faixas pretas.
     *
     * O VLC encaixa o vídeo inteiro nos 320×180, e um filme em 2,39:1 chega com
     * tarjas em cima e embaixo — que a grade, ao recortar para o cartão,
     * mostraria como parte da imagem.
     */
    private fun recortar(imagem: Bitmap, tocador: MediaPlayer): Bitmap {
        val faixa = runCatching { tocador.currentVideoTrack }.getOrNull() ?: return imagem
        if (faixa.width <= 0 || faixa.height <= 0) return imagem
        val sar = if (faixa.sarNum > 0 && faixa.sarDen > 0) faixa.sarNum.toDouble() / faixa.sarDen else 1.0
        val proporcao = faixa.width * sar / faixa.height
        val janela = imagem.width.toDouble() / imagem.height
        return if (proporcao > janela) {
            val altura = (imagem.width / proporcao).roundToInt().coerceIn(1, imagem.height)
            Bitmap.createBitmap(imagem, 0, (imagem.height - altura) / 2, imagem.width, altura)
        } else {
            val largura = (imagem.height * proporcao).roundToInt().coerceIn(1, imagem.width)
            Bitmap.createBitmap(imagem, (imagem.width - largura) / 2, 0, largura, imagem.height)
        }
    }
}
