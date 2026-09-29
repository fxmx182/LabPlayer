package com.mauricio.libertyx.auto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat.MediaItem as BrowserItem
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.mauricio.libertyx.MainActivity
import com.mauricio.libertyx.R
import com.mauricio.libertyx.core.MediaItem
import com.mauricio.libertyx.core.Pro
import com.mauricio.libertyx.core.ResumeStore
import com.mauricio.libertyx.core.resumeKey
import com.mauricio.libertyx.library.MediaLibrary
import com.mauricio.libertyx.player.Playback
import com.mauricio.libertyx.player.PlaybackState
import com.mauricio.libertyx.player.PlayerActivity
import com.mauricio.libertyx.player.SegundoPlano
import com.mauricio.libertyx.player.VlcEngine
import com.mauricio.libertyx.smb.SmbBrowser
import com.mauricio.libertyx.smb.SmbServerStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * O LibertyX dentro do carro — e no bolso, com a tela apagada.
 *
 * **O que o Android Auto permite, e o que não permite.** Não existe vídeo:
 * a plataforma não entrega superfície de imagem a app de terceiro, e a política
 * do Google recusa app de vídeo no carro, por motivo óbvio. O que existe é o
 * app de *mídia*: o carro pede a árvore de pastas, mostra numa lista que o
 * motorista consegue operar de relance, e manda tocar. O que sai pelos
 * alto-falantes é a **faixa de áudio** do arquivo — que é exatamente o que se
 * quer de um show, de um documentário ou de uma aula gravada em vídeo.
 *
 * Este mesmo serviço resolve, de quebra, o áudio em segundo plano no celular:
 * são o mesmo problema — tocar sem tela na frente — e seria desperdício
 * escrever duas vezes.
 *
 * Ele só existe na variante de celular. A televisão não tem carro nem bolso.
 */
class LibertyXMediaService : MediaBrowserServiceCompat() {

    private lateinit var sessao: MediaSessionCompat
    private lateinit var engine: VlcEngine
    private lateinit var audio: AudioManager

    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * O que já foi navegado, para achar de volta na hora de tocar.
     *
     * O carro manda o identificador do item, não o item. Guardar o que foi
     * listado é mais barato — e muito mais rápido — que recodificar a origem
     * inteira dentro de uma string e desmontá-la depois.
     */
    private val conhecidos = HashMap<String, MediaItem>()

    /** A fila do que está tocando, para "próxima" e "anterior" funcionarem. */
    private var fila: List<MediaItem> = emptyList()
    private var indice = 0

    private var focoPedido: AudioFocusRequest? = null
    private var temFoco = false

    /** O que a notificação mostra agora, para só republicar quando mudar. */
    private var notificacaoAtual: Pair<Boolean, String>? = null

    /**
     * Um número de notificação por vida do serviço.
     *
     * Com número fixo, o aviso atrasado de "sessão encerrada" da vez anterior
     * — apagar a tela logo depois de voltar ao player — chegava à interface
     * do sistema depois da notificação nova, com a mesma chave, e o controle
     * de mídia sumia da tela de bloqueio com o áudio ainda tocando.
     */
    private val idNotificacao = ID_NOTIFICACAO + (proximaVida++ % 1000)

    /** Onde começar assim que o VLC estiver tocando (ver `onTimeUpdate`). */
    private var inicioPendente: Double? = null

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        engine = VlcEngine(this).apply {
            // Sem imagem: no carro não há para onde mandá-la, e no bolso
            // decodificar 1080p para descartar cada quadro só gasta bateria.
            audioOnly = true
            onStateChange = { estado -> publicarEstado(estado) }
            onTimeUpdate = {
                // A posição de partida só vale com o VLC já rodando: antes
                // disso ele ignora a busca e o áudio começaria do zero.
                val alvo = inicioPendente
                // O primeiro aviso chega com o tempo ainda em zero, antes de o
                // arquivo andar — buscar ali também é ignorado.
                if (alvo != null && duration > 0 && currentTime > 0.0) {
                    inicioPendente = null
                    seek(alvo, precise = false)
                }
                publicarPosicao()
            }
        }

        sessao = MediaSessionCompat(this, "LibertyX").apply {
            setCallback(Comandos())
            setSessionActivity(
                PendingIntent.getActivity(
                    this@LibertyXMediaService, 0,
                    Intent(this@LibertyXMediaService, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            isActive = true
        }
        sessionToken = sessao.sessionToken
        criarCanal()
    }

    /**
     * O player apagou a tela e passou o filme para cá.
     *
     * A fila é a do player ([Playback]), e o ponto é exatamente onde ele
     * estava — não o da retomada gravada, que pode ter até alguns segundos de
     * atraso.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != SegundoPlano.ACAO) return super.onStartCommand(intent, flags, startId)
        val item = Playback.current
        if (item == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        fila = Playback.queue
        indice = Playback.index
        // Tocar na notificação volta ao player, e não à biblioteca.
        sessao.setSessionActivity(
            PendingIntent.getActivity(
                this, 1,
                Intent(this, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
        )
        // O Android exige a notificação em segundos depois de pedir o serviço
        // em primeiro plano — antes de abrir o arquivo, que pela rede demora.
        sessao.setMetadata(metadados(item))
        atualizarNotificacao(emPrimeiroPlano = true)

        SegundoPlano.ativo = true
        SegundoPlano.posicao = { engine.currentTime }
        SegundoPlano.tocando = { engine.state == PlaybackState.Playing }
        SegundoPlano.parar = { encerrar(guardarPosicao = false) }

        tocar(item, inicio = intent.getDoubleExtra(SegundoPlano.EXTRA_POSICAO, 0.0))
        engine.rate = intent.getFloatExtra(SegundoPlano.EXTRA_VELOCIDADE, 1f)
        return START_NOT_STICKY
    }

    /** Fecha o serviço, guardando onde parou para o player e para a retomada. */
    private fun encerrar(guardarPosicao: Boolean) {
        val atual = fila.getOrNull(indice)
        if (atual != null && engine.duration > 0) {
            ResumeStore.get(this).save(engine.currentTime, engine.duration, atual.origin.resumeKey)
        }
        if (guardarPosicao && SegundoPlano.ativo) SegundoPlano.ultimaPosicao = engine.currentTime
        SegundoPlano.ativo = false
        engine.pause()
        largarFoco()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        SegundoPlano.ativo = false
        super.onDestroy()
        escopo.cancel()
        largarFoco()
        engine.teardown()
        sessao.isActive = false
        sessao.release()
    }

    // ------------------------------------------------------------------
    // A árvore que o carro navega
    // ------------------------------------------------------------------

    override fun onGetRoot(pacote: String, uid: Int, dica: Bundle?): BrowserRoot = BrowserRoot(RAIZ, null)

    /**
     * Cada nível da navegação.
     *
     * `detach()` em tudo que depende de disco ou de rede: o carro chama isto na
     * thread principal e espera a resposta quando ela vier. Responder na hora,
     * com uma lista vazia, seria mais rápido e mostraria uma pasta vazia.
     */
    override fun onLoadChildren(pai: String, resultado: Result<MutableList<BrowserItem>>) {
        resultado.detach()

        // Carro e segundo plano são Pro. Sem ele, a raiz vem vazia e o carro
        // mostra o aviso no lugar da lista — o motorista entende de relance,
        // sem um item falso para tocar.
        Pro.recalcular()
        if (!Pro.ativo) {
            avisarQueEPro()
            resultado.sendResult(mutableListOf())
            return
        }

        escopo.launch {
            val filhos: MutableList<BrowserItem> = when {
                pai == RAIZ -> raiz()
                pai == LOCAIS -> pastasLocais()
                pai.startsWith("$PASTA/") -> videosDaPasta(pai.removePrefix("$PASTA/"))
                pai == SERVIDORES -> servidores()
                pai.startsWith("$SMB/") -> conteudoDoServidor(pai.removePrefix("$SMB/"))
                else -> mutableListOf()
            }
            resultado.sendResult(filhos)
        }
    }

    private fun raiz(): MutableList<BrowserItem> = mutableListOf(
        pasta(LOCAIS, getString(R.string.neste_aparelho)),
        pasta(SERVIDORES, getString(R.string.servidores)),
    )

    private suspend fun pastasLocais(): MutableList<BrowserItem> =
        MediaLibrary.scan(this).map { grupo ->
            grupo.items.forEach { conhecidos[idDoItem(it)] = it }
            pasta("$PASTA/${grupo.path}", grupo.name, resources.getQuantityString(R.plurals.n_videos, grupo.items.size, grupo.items.size))
        }.toMutableList()

    private suspend fun videosDaPasta(caminho: String): MutableList<BrowserItem> {
        val grupo = MediaLibrary.scan(this).firstOrNull { it.path == caminho } ?: return mutableListOf()
        return grupo.items.map { item ->
            conhecidos[idDoItem(item)] = item
            tocavel(item)
        }.toMutableList()
    }

    private fun servidores(): MutableList<BrowserItem> =
        SmbServerStore.get(this).servers.map { servidor ->
            pasta("$SMB/${servidor.id}", servidor.name, servidor.displayHost)
        }.toMutableList()

    /**
     * Um nível dentro do servidor: compartilhamentos ou pastas.
     *
     * O identificador carrega `id-do-servidor/share/caminho`, porque no
     * servidor não há uma lista pronta para consultar depois — cada nível é uma
     * ida à rede, e o caminho é a única forma de saber onde estamos.
     */
    private suspend fun conteudoDoServidor(chave: String): MutableList<BrowserItem> {
        val partes = chave.split("/")
        val servidor = SmbServerStore.get(this).byId(partes[0]) ?: return mutableListOf()
        val senha = SmbServerStore.get(this).password(servidor)
        val share = partes.getOrNull(1)
        val caminho = partes.drop(2).joinToString("/")

        val uri = if (share == null) SmbBrowser.rootUri(servidor)
        else SmbBrowser.uri(servidor, share, caminho)

        val entradas = SmbBrowser.list(this, uri, SmbBrowser.credenciais(servidor, senha))
            ?: return mutableListOf()

        val tocaveis = if (share != null) {
            SmbBrowser.playableItems(servidor, share, caminho, entradas)
        } else {
            emptyList()
        }
        tocaveis.forEach { conhecidos[idDoItem(it)] = it }

        return entradas.mapNotNull { entrada ->
            when {
                entrada.isDirectory -> {
                    val novo = listOfNotNull(partes[0], share, caminho.ifEmpty { null }, entrada.name)
                        .joinToString("/")
                    pasta("$SMB/$novo", entrada.name)
                }
                else -> tocaveis.firstOrNull { it.title == entrada.name }?.let { tocavel(it) }
            }
        }.toMutableList()
    }

    private fun pasta(id: String, titulo: String, subtitulo: String? = null): BrowserItem =
        BrowserItem(
            MediaDescriptionCompat.Builder()
                .setMediaId(id).setTitle(titulo).setSubtitle(subtitulo).build(),
            BrowserItem.FLAG_BROWSABLE,
        )

    private fun tocavel(item: MediaItem): BrowserItem {
        // Mostrar onde parou é o que mais rende num carro: quase sempre se
        // volta para o meio de algo começado ontem.
        val retomada = ResumeStore.get(this).position(item.origin.resumeKey)
        return BrowserItem(
            MediaDescriptionCompat.Builder()
                .setMediaId(idDoItem(item))
                .setTitle(item.title)
                .setSubtitle(retomada?.let { getString(R.string.parou_em, com.mauricio.libertyx.core.TimeFormat.clock(it)) })
                .build(),
            BrowserItem.FLAG_PLAYABLE,
        )
    }

    private fun idDoItem(item: MediaItem) = "$TOCAR/${item.id}"

    // ------------------------------------------------------------------
    // Os comandos que chegam do volante, do carro e do fone
    // ------------------------------------------------------------------

    private inner class Comandos : MediaSessionCompat.Callback() {

        override fun onPlayFromMediaId(mediaId: String, extras: Bundle?) {
            if (!Pro.ativo) return avisarQueEPro()
            val item = conhecidos[mediaId] ?: return
            // A fila é o que estava na mesma pasta: é o que faz "próxima"
            // significar alguma coisa no volante.
            fila = conhecidos.values.filter { irmao(it, item) }.sortedBy { it.title }
            indice = fila.indexOfFirst { idDoItem(it) == mediaId }.coerceAtLeast(0)
            tocar(item)
        }

        override fun onPlay() {
            if (pedirFoco()) {
                engine.play()
                publicarEstado(PlaybackState.Playing)
            }
        }

        override fun onPause() {
            engine.pause()
            publicarEstado(PlaybackState.Paused)
        }

        override fun onStop() = encerrar(guardarPosicao = true)

        override fun onSeekTo(pos: Long) {
            engine.seek(pos / 1000.0, precise = false)
            publicarPosicao()
        }

        override fun onSkipToNext() {
            fila.getOrNull(indice + 1)?.let { indice++; tocar(it) }
        }

        override fun onSkipToPrevious() {
            // No carro, "anterior" com o áudio andando volta ao começo da
            // faixa — mexer no volante para pular sem querer é pior aqui do
            // que no celular, onde se está olhando para a tela.
            if (engine.currentTime > 3) {
                engine.seek(0.0, precise = false)
                return
            }
            fila.getOrNull(indice - 1)?.let { indice--; tocar(it) }
        }
    }

    /** Dois itens da mesma pasta (ou do mesmo compartilhamento). */
    private fun irmao(a: MediaItem, b: MediaItem): Boolean =
        a.origin::class == b.origin::class &&
            a.id.substringBeforeLast(':') == b.id.substringBeforeLast(':')

    /** @param inicio o ponto de partida; sem ele, a retomada gravada. */
    private fun tocar(item: MediaItem, inicio: Double? = null) {
        if (!pedirFoco()) return
        // Pulou de vídeo na notificação: o player, ao voltar, abre o mesmo.
        if (SegundoPlano.ativo && fila === Playback.queue) Playback.index = indice

        // O ponto que veio do player entra na abertura do arquivo (exato); a
        // retomada gravada, como antes, por busca quando o tempo começa a andar.
        engine.load(item, inicio).onFailure {
            publicarEstado(PlaybackState.Failed(it.message ?: getString(R.string.erro_abrir_curto)))
            return
        }

        // No carro não há como perguntar. Retomar é o que quase sempre se
        // quer, e a barra de progresso deixa voltar ao início num toque.
        inicioPendente = if (inicio != null) null else ResumeStore.get(this).position(item.origin.resumeKey)
        engine.play()

        sessao.setMetadata(metadados(item))
        publicarEstado(PlaybackState.Playing)
    }

    private fun metadados(item: MediaItem): MediaMetadataCompat =
        MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, idDoItem(item))
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, item.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "LibertyX Player")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, ((item.duration ?: 0.0) * 1000).toLong())
            .build()

    // ------------------------------------------------------------------
    // Estado, notificação e foco de áudio
    // ------------------------------------------------------------------

    private fun avisarQueEPro() {
        sessao.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_ERROR, 0, 0f)
                .setErrorMessage(
                    PlaybackStateCompat.ERROR_CODE_PREMIUM_ACCOUNT_REQUIRED,
                    getString(R.string.pro_auto_bloqueado),
                )
                .build()
        )
    }

    private fun publicarEstado(estado: PlaybackState) {
        val (codigo, emPrimeiroPlano) = when (estado) {
            PlaybackState.Playing -> PlaybackStateCompat.STATE_PLAYING to true
            PlaybackState.Paused -> PlaybackStateCompat.STATE_PAUSED to false
            PlaybackState.Loading -> PlaybackStateCompat.STATE_BUFFERING to true
            PlaybackState.Ended -> PlaybackStateCompat.STATE_STOPPED to false
            is PlaybackState.Failed -> PlaybackStateCompat.STATE_ERROR to false
            else -> PlaybackStateCompat.STATE_NONE to false
        }

        sessao.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
                )
                .setState(codigo, (engine.currentTime * 1000).toLong(), engine.rate)
                .build()
        )

        if (estado == PlaybackState.Ended) {
            fila.getOrNull(indice + 1)?.let { indice++; tocar(it) } ?: largarFoco()
        }

        atualizarNotificacao(emPrimeiroPlano)
    }

    private fun publicarPosicao() {
        val tocando = engine.state == PlaybackState.Playing
        sessao.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
                )
                .setState(
                    if (tocando) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    (engine.currentTime * 1000).toLong(),
                    engine.rate,
                )
                .build()
        )
    }

    private fun atualizarNotificacao(emPrimeiroPlano: Boolean) {
        val titulo = sessao.controller?.metadata
            ?.getString(MediaMetadataCompat.METADATA_KEY_TITLE) ?: "LibertyX Player"
        // O estado do motor muda várias vezes por segundo ao abrir e ao
        // buscar; republicar a notificação a cada vez fazia o sistema
        // descartá-las por excesso. A posição vai pela sessão, não por aqui.
        if (notificacaoAtual == (emPrimeiroPlano to titulo)) return
        notificacaoAtual = emPrimeiroPlano to titulo

        val aviso: Notification = NotificationCompat.Builder(this, CANAL)
            .setContentTitle(titulo)
            .setContentText("LibertyX Player")
            .setSmallIcon(R.drawable.ic_play)
            .setContentIntent(sessao.controller?.sessionActivity)
            .setStyle(MediaStyle().setMediaSession(sessao.sessionToken))
            .setOnlyAlertOnce(true)
            .build()

        if (emPrimeiroPlano) {
            startForeground(idNotificacao, aviso)
        } else {
            // Sai do primeiro plano mas mantém o aviso: pausado ainda se quer
            // o controle na cortina, e o sistema pode matar o serviço.
            stopForeground(STOP_FOREGROUND_DETACH)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(idNotificacao, aviso)
        }
    }

    private fun criarCanal() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val canal = NotificationChannel(CANAL, getString(R.string.canal_reproducao), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.canal_reproducao_desc)
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(canal)
    }

    /**
     * O foco de áudio.
     *
     * Sem pedir, o som do LibertyX se sobrepõe ao GPS e à ligação em vez de baixar
     * ou parar — que é o tipo de coisa que faz desinstalar um app no carro.
     */
    private fun pedirFoco(): Boolean {
        // Quem já tem o foco não pede de novo. Um pedido novo faz o sistema
        // avisar o pedido anterior — do próprio app — que ele perdeu o foco,
        // e o áudio pausava logo depois de o "tocar" da tela de bloqueio
        // mandar continuar.
        if (temFoco) return true
        val atributos = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()

        val resultado = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val pedido = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(atributos)
                .setOnAudioFocusChangeListener(::mudouFoco)
                .build()
            focoPedido = pedido
            audio.requestAudioFocus(pedido)
        } else {
            @Suppress("DEPRECATION")
            audio.requestAudioFocus(::mudouFoco, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        temFoco = resultado == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return temFoco
    }

    private fun mudouFoco(mudanca: Int) {
        when (mudanca) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                temFoco = false
                engine.pause()
                publicarEstado(PlaybackState.Paused)
                largarFoco()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                engine.pause()
                publicarEstado(PlaybackState.Paused)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> engine.volume = 0.25f
            AudioManager.AUDIOFOCUS_GAIN -> {
                engine.volume = 1f
                if (engine.state == PlaybackState.Paused) {
                    engine.play()
                    publicarEstado(PlaybackState.Playing)
                }
            }
        }
    }

    private fun largarFoco() {
        temFoco = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focoPedido?.let { audio.abandonAudioFocusRequest(it) }
            focoPedido = null
        } else {
            @Suppress("DEPRECATION")
            audio.abandonAudioFocus(::mudouFoco)
        }
    }

    private companion object {
        const val RAIZ = "raiz"
        const val LOCAIS = "locais"
        const val SERVIDORES = "servidores"
        const val PASTA = "pasta"
        const val SMB = "smb"
        const val TOCAR = "tocar"
        const val CANAL = "libertyx.playback"
        const val ID_NOTIFICACAO = 41
        private var proximaVida = 0
    }
}
