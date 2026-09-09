package com.mauricio.viperplayer.player

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.TextureView
import android.view.ViewGroup
import com.mauricio.viperplayer.core.MediaItem
import com.mauricio.viperplayer.core.MediaOrigin
import com.mauricio.viperplayer.smb.SmbBrowser
import com.mauricio.viperplayer.smb.SmbServerStore
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout

sealed interface PlaybackState {
    data object Idle : PlaybackState
    data object Loading : PlaybackState
    data object Ready : PlaybackState
    data object Playing : PlaybackState
    data object Paused : PlaybackState
    data object Ended : PlaybackState
    data class Failed(val message: String) : PlaybackState
}

/** Uma faixa selecionável dentro do arquivo. */
data class MediaTrack(val id: Int, val label: String)

/**
 * O motor de reprodução, sobre o libVLC.
 *
 * Mesma escolha do app de iOS e pelos mesmos motivos: são vinte anos de
 * correções acumuladas em sincronia de som e imagem, buffer de rede e
 * recuperação de erro. E ele fala SMB nativamente, o que transforma toda a
 * ponte de leitura por blocos numa URL `smb://`.
 *
 * A interface inteira — gestos, controles — conversa só com esta classe, nunca
 * com o `MediaPlayer` do VLC. É o mesmo contrato do `PlaybackEngine` do iOS, e
 * existe pela mesma razão: os gestos são a cara do app e não podem ser
 * reescritos quando o motor mudar.
 */
class VlcEngine(private val context: Context) {

    private val libVlc = Vlc.get(context)
    private val player = MediaPlayer(libVlc)
    private val main = Handler(Looper.getMainLooper())

    /**
     * O descritor do arquivo, aberto enquanto a reprodução durar.
     *
     * `content://` de outro app não tem caminho que o VLC possa abrir sozinho;
     * quem tem permissão de ler é o nosso processo. Fechar isto cedo é o
     * equivalente exato do escopo de segurança fechado no iOS: o contêiner é
     * reconhecido, nenhuma faixa aparece, e parece arquivo inválido.
     */
    private var fileDescriptor: ParcelFileDescriptor? = null

    private var videoLayout: VLCVideoLayout? = null

    var state: PlaybackState = PlaybackState.Idle
        private set(value) {
            if (field == value) return
            field = value
            onStateChange?.invoke(value)
        }

    var onTimeUpdate: ((Double) -> Unit)? = null
    var onStateChange: ((PlaybackState) -> Unit)? = null
    var onBufferingChange: ((Boolean) -> Unit)? = null
    /** Quando o VLC descobre as faixas — só aí a lista de áudio/legenda existe. */
    var onTracksChange: (() -> Unit)? = null

    /**
     * Avisa a interface só na mudança.
     *
     * Emitir a cada atualização de tempo fazia o transporte piscar; emitir só
     * quando o VLC anuncia "tocando" deixava o indicador aceso para sempre.
     * Reagir à transição resolve os dois.
     */
    private var buffering = false
        set(value) {
            if (field == value) return
            field = value
            onBufferingChange?.invoke(value)
        }

    /**
     * Até que instante do vídeo os dados já estão em mãos.
     *
     * Não existe API que responda isso — o que há são as estatísticas de
     * leitura. A conta é simples e a aproximação é honesta: a fração do
     * arquivo lida desde a última busca vale, em segundos, a mesma fração da
     * duração. Erra em vídeo de taxa muito variável, e para uma barra de
     * progresso isso não tem importância. Sem o tamanho do arquivo — caso do
     * servidor — não há o que estimar, e a barra simplesmente não mostra.
     */
    val bufferedTime: Double
        get() {
            val tamanho = tamanhoDoArquivo ?: return 0.0
            if (tamanho <= 0 || duration <= 0) return 0.0
            val lidos = player.media?.let { m -> m.stats?.demuxReadBytes?.toLong() ?: 0L } ?: 0L
            if (lidos <= 0) return 0.0
            val (bytesRef, instanteRef) = bytesNaBusca ?: (0L to 0.0)
            val desde = (lidos - bytesRef).coerceAtLeast(0L)
            return (instanteRef + desde.toDouble() / tamanho * duration).coerceAtMost(duration)
        }

    private var tamanhoDoArquivo: Long? = null

    /**
     * Quantos bytes já tinham sido lidos quando a posição atual começou.
     *
     * Depois de buscar, a contagem recomeça: o que estava em memória era de
     * outro trecho do arquivo.
     */
    private var bytesNaBusca: Pair<Long, Double>? = null

    private fun marcarBusca(instante: Double) {
        val lidos = player.media?.let { m -> m.stats?.demuxReadBytes?.toLong() ?: 0L } ?: 0L
        bytesNaBusca = lidos to instante
    }

    val currentTime: Double get() = player.time / 1000.0
    val duration: Double get() = player.length.takeIf { it > 0 }?.let { it / 1000.0 } ?: 0.0
    val isSeekable: Boolean get() = player.isSeekable

    var rate: Float
        get() = player.rate
        set(value) { player.rate = value }

    /** O VLC trabalha com 0–200; acima de 100 é ganho além do original. */
    var volume: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            player.volume = (field * 100).toInt()
        }

    var isMuted: Boolean = false
        set(value) {
            field = value
            player.volume = if (value) 0 else (volume * 100).toInt()
        }

    init {
        player.setEventListener(::handleEvent)
    }

    // MARK: - Superfície

    /**
     * Liga o motor à tela.
     *
     * `useTextureView = true` de propósito, e não é detalhe: com SurfaceView a
     * imagem é desenhada por fora da janela, e escalar a view não escala o
     * vídeo — a pinça de ampliar não teria efeito nenhum. Também é o que
     * permite a captura de tela, que numa SurfaceView sai preta.
     */
    fun attach(layout: VLCVideoLayout) {
        videoLayout = layout
        player.attachViews(layout, null, true, true)
    }

    fun detach() {
        player.detachViews()
        videoLayout = null
    }

    /** Reenquadra depois de girar a tela ou mudar o modo. */
    fun updateSurfaces() = player.updateVideoSurfaces()

    fun setScale(type: MediaPlayer.ScaleType) {
        player.videoScale = type
    }

    // MARK: - Carga

    fun load(item: MediaItem): Result<Unit> = runCatching {
        state = PlaybackState.Loading
        // Trocar de vídeo com o anterior tocando: sem parar antes, o VLC troca
        // a mídia por baixo do laço de leitura e a imagem do arquivo velho
        // continua alguns segundos por cima do novo.
        runCatching { if (player.isPlaying) player.stop() }
        closeDescriptor()

        val media = when (val origem = item.origin) {
            is MediaOrigin.Local -> mediaForLocal(origem.uri)
            is MediaOrigin.Remote -> Media(libVlc, origem.uri)
            is MediaOrigin.Smb -> {
                val servidor = SmbServerStore.get(context).byId(origem.serverId)
                    ?: error("servidor não encontrado")
                val senha = SmbServerStore.get(context).password(servidor)
                Media(libVlc, SmbBrowser.uri(servidor, senha, origem.share, origem.path))
            }
        }

        media.setHWDecoderEnabled(true, false)
        configureBuffer(media, item.origin)
        tamanhoDoArquivo = item.fileSize
        bytesNaBusca = null
        player.media = media
        media.release()
        state = PlaybackState.Ready
    }.onFailure {
        state = PlaybackState.Failed(it.message ?: "não deu para abrir este arquivo")
    }

    private fun mediaForLocal(uri: Uri): Media {
        // `file://` o VLC abre sozinho. Qualquer outra coisa vem de outro app
        // e só o nosso processo tem permissão de ler — daí o descritor.
        if (uri.scheme == "file") return Media(libVlc, uri)
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("o Android não deixou abrir este arquivo")
        fileDescriptor = pfd
        return Media(libVlc, pfd.fileDescriptor)
    }

    /**
     * Quanto o VLC guarda adiantado antes de precisar.
     *
     * Dois mecanismos diferentes, e no servidor são precisos os dois:
     *
     * - `network-caching` é o fôlego do reprodutor: quantos milissegundos de
     *   vídeo já decodificado ele mantém à frente. Absorve uma engasgada da
     *   rede sem a imagem parar.
     * - `prefetch` é o fôlego da leitura: um bloco grande lido adiantado, para
     *   que o pedido seguinte não vire uma ida ao servidor.
     *
     * O que faz diferença ao arrastar a barra é o segundo: depois de uma busca,
     * o bloco novo chega de uma vez em vez de gota a gota. Em troca o vídeo
     * demora um pouco mais para começar — preço que se paga nos primeiros
     * segundos e rende o filme inteiro sem travar.
     */
    private fun configureBuffer(media: Media, origin: MediaOrigin) {
        when (origin) {
            is MediaOrigin.Local -> media.addOption(":file-caching=500")
            else -> {
                media.addOption(":network-caching=5000")
                // 32 MB adiantados: uns 20 segundos de um 1080p comum.
                media.addOption(":prefetch-buffer-size=32768")
                // Blocos de 256 KB em vez dos 16 KB padrão.
                media.addOption(":prefetch-read-size=262144")
                // Busca curta aproveita o que já está em memória em vez de
                // jogar fora e ler tudo de novo.
                media.addOption(":prefetch-seek-threshold=1048576")
            }
        }
    }

    // MARK: - Transporte

    fun play() {
        player.play()
        if (state != PlaybackState.Playing) state = PlaybackState.Playing
    }

    fun pause() {
        if (player.isPlaying) player.pause()
        state = PlaybackState.Paused
    }

    /**
     * Busca.
     *
     * `precise` é o motivo de o projeto existir. O segundo parâmetro do
     * `setTime` do VLC é `fast`: ligado, ele pula para o keyframe anterior e
     * mostra — é a rolagem aos pulos que incomoda. Desligado, ele decodifica
     * até o quadro pedido. O Android expõe essa escolha; o VLCKit do iOS não,
     * e foi essa a lacuna que quase virou um motor FFmpeg próprio lá.
     */
    fun seek(time: Double, precise: Boolean = true) {
        if (duration <= 0) return
        val alvo = time.coerceIn(0.0, duration)
        player.setTime((alvo * 1000).toLong(), !precise)
        marcarBusca(alvo)
        onTimeUpdate?.invoke(alvo)
    }

    // MARK: - Rolagem

    private var pendingScrub: Double? = null
    private var scrubScheduled = false
    private var lastScrubApplied = 0L

    /**
     * Rolagem com ritmo controlado.
     *
     * O dedo gera dezenas de eventos por segundo; mandar cada um para o VLC faz
     * ele abortar e reiniciar a busca sem parar, e a imagem trava em vez de
     * acompanhar. Aplicando no máximo a cada 80 ms, cada busca tem tempo de
     * mostrar o quadro antes da próxima — o movimento fica contínuo.
     */
    fun scrub(time: Double, precise: Boolean) {
        if (duration <= 0) return
        val alvo = time.coerceIn(0.0, duration)
        pendingScrub = alvo
        onTimeUpdate?.invoke(alvo)

        val agora = System.currentTimeMillis()
        if (agora - lastScrubApplied >= 80) {
            aplicarScrub(alvo, precise, agora)
        } else {
            agendarScrubPendente(precise)
        }
    }

    private fun aplicarScrub(alvo: Double, precise: Boolean, agora: Long) {
        lastScrubApplied = agora
        pendingScrub = null
        player.setTime((alvo * 1000).toLong(), !precise)
        marcarBusca(alvo)
    }

    /**
     * Garante que o último ponto arrastado seja aplicado mesmo que o dedo pare
     * — senão a imagem fica num ponto anterior ao que a barra mostra.
     */
    private fun agendarScrubPendente(precise: Boolean) {
        if (scrubScheduled) return
        scrubScheduled = true
        main.postDelayed({
            scrubScheduled = false
            pendingScrub?.let { aplicarScrub(it, precise, System.currentTimeMillis()) }
        }, 80)
    }

    // MARK: - Faixas

    val audioTracks: List<MediaTrack>
        get() = tracks(player.audioTracks)

    val subtitleTracks: List<MediaTrack>
        get() = tracks(player.spuTracks)

    /** `null` quando nenhuma está ligada — o VLC usa -1 para isso. */
    val currentAudioTrack: Int? get() = player.audioTrack.takeIf { it >= 0 }
    val currentSubtitleTrack: Int? get() = player.spuTrack.takeIf { it >= 0 }

    fun selectAudioTrack(id: Int) { player.setAudioTrack(id) }

    /** `null` desliga a legenda. */
    fun selectSubtitleTrack(id: Int?) { player.setSpuTrack(id ?: -1) }

    /** Legenda de um arquivo ao lado, escolhido pelo usuário. */
    fun addSubtitle(uri: Uri, selecionar: Boolean = true): Boolean =
        player.addSlave(IMedia.Slave.Type.Subtitle, uri, selecionar)

    private fun tracks(descricoes: Array<MediaPlayer.TrackDescription>?): List<MediaTrack> =
        descricoes.orEmpty()
            // O índice -1 é a entrada "Desativado" que o VLC inclui; a
            // interface já oferece essa opção por conta própria.
            .filter { it.id >= 0 }
            .map { MediaTrack(it.id, it.name ?: "Faixa ${it.id}") }

    // MARK: - Captura

    /**
     * O quadro que está na tela agora.
     *
     * Funciona porque a superfície é uma TextureView — ver [attach]. Numa
     * SurfaceView isto devolveria um retângulo preto, que é o modo mais
     * convincente de uma captura de tela parecer quebrada.
     */
    fun snapshot(): Bitmap? {
        val layout = videoLayout ?: return null
        val textura = encontrarTextura(layout) ?: return null
        return textura.bitmap
    }

    private fun encontrarTextura(raiz: ViewGroup): TextureView? {
        for (i in 0 until raiz.childCount) {
            when (val filho = raiz.getChildAt(i)) {
                is TextureView -> return filho
                is ViewGroup -> encontrarTextura(filho)?.let { return it }
            }
        }
        return null
    }

    // MARK: - Fim

    fun teardown() {
        runCatching { player.setEventListener(null) }
        runCatching { player.stop() }
        runCatching { player.detachViews() }
        runCatching { player.release() }
        // Solta o arquivo só depois de parar: fechar antes deixaria o VLC lendo
        // um descritor já inválido.
        closeDescriptor()
        state = PlaybackState.Idle
    }

    private fun closeDescriptor() {
        runCatching { fileDescriptor?.close() }
        fileDescriptor = null
    }

    // MARK: - Estado vindo do VLC

    private fun handleEvent(evento: MediaPlayer.Event) {
        when (evento.type) {
            MediaPlayer.Event.Opening -> state = PlaybackState.Loading
            MediaPlayer.Event.Buffering ->
                // O VLC emite "buffering" durante a reprodução normal, com
                // percentual em 100. Só o que está abaixo disso é espera de
                // verdade; sem esta comparação o indicador pisca sem parar.
                buffering = evento.buffering < 100f
            MediaPlayer.Event.Playing -> {
                buffering = false
                state = PlaybackState.Playing
            }
            MediaPlayer.Event.Paused -> state = PlaybackState.Paused
            MediaPlayer.Event.Stopped -> state = PlaybackState.Idle
            MediaPlayer.Event.EndReached -> state = PlaybackState.Ended
            MediaPlayer.Event.EncounteredError ->
                state = PlaybackState.Failed("o VLC não conseguiu abrir este arquivo")
            MediaPlayer.Event.TimeChanged -> {
                // Tempo andando é a prova de que não está carregando — mais
                // confiável que o estado anunciado.
                buffering = false
                onTimeUpdate?.invoke(evento.timeChanged / 1000.0)
            }
            MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted -> onTracksChange?.invoke()
        }
    }
}
