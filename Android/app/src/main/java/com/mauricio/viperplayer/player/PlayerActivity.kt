package com.mauricio.viperplayer.player

import android.app.Activity
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Rational
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.mauricio.viperplayer.R
import com.mauricio.viperplayer.core.MediaItem
import com.mauricio.viperplayer.core.Device
import com.mauricio.viperplayer.core.PlayerPreferences
import com.mauricio.viperplayer.core.ResumeStore
import com.mauricio.viperplayer.core.TimeFormat
import com.mauricio.viperplayer.core.resumeKey
import com.mauricio.viperplayer.databinding.ActivityPlayerBinding
import org.videolan.libvlc.MediaPlayer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * A tela de reprodução e toda a camada de gestos estilo MX Player.
 *
 * Escrita em Views e contra o [VlcEngine], nunca contra o `MediaPlayer` do VLC:
 * os gestos são a parte que dá a cara do app e não podem ser refeitos se o
 * motor mudar. É a mesma separação do `PlayerViewController` do iOS, e ela já
 * se pagou lá — trocar de motor custou uma linha.
 */
class PlayerActivity : Activity() {

    /** Ajustes de sensibilidade. */
    private object Tuning {
        /** Quantos segundos de vídeo por largura de tela arrastada. */
        const val SEEK_SECONDS_PER_SCREEN_WIDTH = 120.0
        /** Fração da altura da tela para percorrer 0→100% de brilho/volume. */
        const val VERTICAL_TRAVEL_FRACTION = 0.6f
        const val DOUBLE_TAP_SECONDS = 10.0
    }

    private enum class PanAxis { UNDECIDED, HORIZONTAL, VERTICAL, MULTITOUCH }

    private lateinit var ui: ActivityPlayerBinding
    private lateinit var engine: VlcEngine
    private lateinit var prefs: PlayerPreferences
    private lateinit var audio: AudioManager
    private lateinit var resume: ResumeStore

    private val main = Handler(Looper.getMainLooper())

    private var item: MediaItem? = null
    private var playbackSpeed = 1f
    private var rateBeforeHold = 1f
    private var holdActive = false

    private var controlsVisible = true
    private var controlsVisibleSince = 0L
    private var isLocked = false
    private var didPresentError = false
    private var lastSavedPosition = 0.0

    /** Instante a retomar assim que a reprodução começar de fato. */
    private var pendingResume: Double? = null

    private var isBarScrubbing = false
    private var suppressBuffering = false

    // Ferramentas
    private enum class RepeatMode { OFF, ONE }
    private var repeatMode = RepeatMode.OFF
    private var isShuffling = false
    private var sleepDeadline: Long? = null
    private val sleepRunnable = Runnable {
        engine.pause()
        sleepDeadline = null
        showHud("Pausado pelo temporizador", null, null)
        hideHudAfter(2500)
    }

    // Gestos
    private var panAxis = PanAxis.UNDECIDED
    private var panStartX = 0f
    private var panStartY = 0f
    private var panStartTime = 0.0
    private var panStartBrightness = 0.5f
    private var panStartVolume = 0
    private var panIsOnLeftHalf = true
    private var touchStartedOnBars = false
    private var axisLockThreshold = 12f

    /** Televisão: quem comanda é o controle remoto, e não o dedo. */
    private var isTv = false

    // Ampliação: o enquadramento decide como o vídeo se encaixa na tela; a
    // ampliação é o usuário chegando mais perto de um pedaço. São coisas
    // diferentes e por isso não se atrapalham.
    private var videoZoom = 1f
    private var videoOffsetX = 0f
    private var videoOffsetY = 0f
    private var pinchStartZoom = 1f
    private var pinchStartDistance = 0f
    private var lastMidX = 0f
    private var lastMidY = 0f

    private val gravityModes = listOf(
        MediaPlayer.ScaleType.SURFACE_BEST_FIT to "Ajustar",
        MediaPlayer.ScaleType.SURFACE_FILL to "Preencher",
        MediaPlayer.ScaleType.SURFACE_FIT_SCREEN to "Esticar",
    )
    private var gravityIndex = 0

    // MARK: - Ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(ui.root)

        prefs = PlayerPreferences(this)
        resume = ResumeStore.get(this)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        axisLockThreshold = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        isTv = Device.isTv(this)

        goFullscreen()
        afastarDoRecorte()

        // "Abrir com": o vídeo veio de fora e não há lista em volta dele.
        intent?.data?.let { uri ->
            if (intent.action == Intent.ACTION_VIEW) {
                Playback.single(Playback.itemFromUri(this, uri))
            }
        }

        val atual = Playback.current
        if (atual == null) {
            finish()
            return
        }
        item = atual

        engine = VlcEngine(this)
        engine.attach(ui.videoLayout)

        bindControls()
        if (isTv) prepararParaTv()
        bindEngine()
        updateNavigation()
        loadAndPlay()
    }

    override fun onStop() {
        super.onStop()
        // Sair com a janela flutuante aberta é legítimo: o vídeo continua nela.
        if (isInPip()) return
        if (::engine.isInitialized) engine.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        if (::engine.isInitialized) {
            saveResumeNow()
            engine.detach()
            engine.teardown()
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Sem isto, girar a tela deixa o vídeo com o enquadramento antigo — a
        // imagem fica esticada ou com tarja até a próxima troca de modo.
        ui.videoLayout.post { engine.updateSurfaces() }
    }

    /**
     * Afasta as barras do recorte da câmera e dos gestos do sistema.
     *
     * O vídeo ocupa a tela inteira, recorte incluído — é o que se quer numa
     * imagem. Os **controles**, não: no retrato de um celular com câmera na
     * tela, a barra de cima cai exatamente embaixo dela, e o título fica
     * escondido atrás do furo. Deitado, o recorte muda de lado, por isso o
     * afastamento vem dos quatro lados e não de um número fixo.
     *
     * O recuo de origem é lido uma vez e somado, em vez de substituído: os
     * valores do XML continuam valendo, e o do sistema entra por cima.
     */
    private fun afastarDoRecorte() {
        val topoOriginal = ui.topBar.paddingTop
        val baseOriginal = ui.bottomBar.paddingBottom
        val ladoCima = ui.topBar.paddingLeft
        val ladoBaixo = ui.bottomBar.paddingLeft

        ViewCompat.setOnApplyWindowInsetsListener(ui.root) { _, insets ->
            val livre = insets.getInsets(
                WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.systemBars()
            )
            ui.topBar.updatePadding(
                left = ladoCima + livre.left,
                top = topoOriginal + livre.top,
                right = ladoCima + livre.right,
            )
            ui.bottomBar.updatePadding(
                left = ladoBaixo + livre.left,
                right = ladoBaixo + livre.right,
                bottom = baseOriginal + livre.bottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(ui.root)
    }

    private fun goFullscreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Deixa a imagem entrar na faixa do recorte em vez de o sistema
        // encolher a janela para fugir dele — quem se afasta são os controles,
        // logo acima, e não o vídeo.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN
    }

    // MARK: - Motor

    private fun bindEngine() {
        engine.onTimeUpdate = { tempo ->
            // Aplica a retomada no primeiro sinal de que a reprodução começou
            // DE VERDADE. `play()` marca o estado como tocando na hora, antes
            // de o VLC posicionar o arquivo — buscar ali é ignorado, e o vídeo
            // seguia do zero. Atualização de tempo só chega com ele rodando.
            val alvo = pendingResume
            if (alvo != null && engine.duration > 0) {
                pendingResume = null
                engine.seek(alvo, precise = false)
            } else {
                updateProgress(tempo)
                saveResumePoint(tempo)
            }
        }
        engine.onBufferingChange = { carregando ->
            ui.buffering.visibility =
                if (carregando && !suppressBuffering) View.VISIBLE else View.GONE
        }
        engine.onStateChange = { estado -> applyState(estado) }
        engine.onTracksChange = { /* as listas são lidas na hora de abrir */ }
    }

    private fun applyState(estado: PlaybackState) {
        ui.btnPlay.setImageResource(
            if (estado == PlaybackState.Playing) R.drawable.ic_pause else R.drawable.ic_play
        )
        when (estado) {
            is PlaybackState.Failed -> presentError(estado.message)
            PlaybackState.Ended -> handlePlaybackEnded()
            else -> {}
        }
    }

    private fun loadAndPlay() {
        val alvo = item ?: return
        ui.tvTitle.text = alvo.title
        didPresentError = false
        lastSavedPosition = 0.0

        engine.load(alvo).onFailure {
            presentError(it.message ?: "não deu para abrir")
            return
        }

        // Retomar é pergunta, não regra: às vezes se quer rever o filme desde o
        // começo, e voltar sozinho ao meio obriga a desfazer na mão toda vez.
        val retomada = resume.position(alvo.origin.resumeKey)
        if (retomada != null && prefs.askToResume) {
            perguntarRetomada(retomada)
        } else {
            if (retomada != null) pendingResume = retomada
            engine.play()
            scheduleControlsHide()
        }
    }

    private fun perguntarRetomada(instante: Double) {
        cancelControlsHide()
        dialog()
            .setTitle(item?.title)
            .setMessage("Você parou em ${TimeFormat.clock(instante)}.")
            .setPositiveButton("Continuar de ${TimeFormat.clock(instante)}") { _, _ ->
                // Buscar antes de a reprodução começar é ignorado pelo VLC — ele
                // ainda não tem o arquivo posicionado. A marca fica guardada e é
                // aplicada assim que ele começa a tocar.
                pendingResume = instante
                engine.play()
                scheduleControlsHide()
            }
            .setNegativeButton("Começar do início") { _, _ ->
                item?.let { resume.clear(it.origin.resumeKey) }
                engine.play()
                scheduleControlsHide()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Guarda a posição de tempos em tempos, não a cada quadro: são dezenas de
     * gravações por segundo contra uma a cada cinco segundos.
     */
    private fun saveResumePoint(tempo: Double) {
        val alvo = item ?: return
        if (engine.duration <= 0 || abs(tempo - lastSavedPosition) < 5) return
        lastSavedPosition = tempo
        resume.save(tempo, engine.duration, alvo.origin.resumeKey)
    }

    /** Ao sair, sem o intervalo: é justamente quando a posição precisa estar certa. */
    private fun saveResumeNow() {
        val alvo = item ?: return
        if (engine.duration > 0 && engine.currentTime > 30) {
            resume.save(engine.currentTime, engine.duration, alvo.origin.resumeKey)
        }
    }

    private fun presentError(mensagem: String) {
        // A falha chega por duas vias ao mesmo tempo — o retorno de `load` e a
        // transição para Failed. Sem esta trava viriam dois alertas empilhados.
        if (didPresentError) return
        didPresentError = true
        setControlsVisible(true)
        dialog()
            .setTitle("Não deu para tocar")
            .setMessage(mensagem)
            .setPositiveButton("Voltar") { _, _ -> finish() }
            .show()
    }

    // MARK: - Anterior / próxima

    /**
     * Convenção de player: com o vídeo já andando, "anterior" volta ao começo
     * dele; só nos primeiros segundos é que salta para o arquivo anterior. Sem
     * isso, quem quer reiniciar acaba pulando de faixa sem querer.
     */
    private fun goToPrevious() {
        if (engine.currentTime > 3) {
            engine.seek(0.0, precise = false)
            scheduleControlsHide()
            return
        }
        switchTo(Playback.index - 1)
    }

    private fun goToNext() {
        if (isShuffling && Playback.queue.size > 1) {
            // Sorteia sem repetir o atual — cair no mesmo vídeo não parece
            // aleatório, parece defeito.
            var sorteado = Playback.index
            while (sorteado == Playback.index) sorteado = Random.nextInt(Playback.queue.size)
            switchTo(sorteado)
            return
        }
        switchTo(Playback.index + 1)
    }

    private fun handlePlaybackEnded() {
        if (repeatMode == RepeatMode.ONE) {
            engine.seek(0.0, precise = false)
            engine.play()
            return
        }
        // Quem assistiu até o fim não quer voltar para os créditos na próxima.
        item?.let { resume.clear(it.origin.resumeKey) }

        if (isShuffling && Playback.queue.size > 1) goToNext()
        else if (Playback.index + 1 < Playback.queue.size) goToNext()
        else finish()
    }

    private fun switchTo(indice: Int) {
        if (indice !in Playback.queue.indices) return
        saveResumeNow()

        Playback.index = indice
        item = Playback.queue[indice]

        // A ampliação era do vídeo anterior; o novo pode nem ter o mesmo
        // formato de tela.
        videoZoom = 1f
        videoOffsetX = 0f
        videoOffsetY = 0f
        applyZoom()

        setControlsVisible(true)
        updateNavigation()
        showHud(item?.title ?: "", null, null)
        hideHudAfter(1200)

        loadAndPlay()
    }

    private fun updateNavigation() {
        ui.btnPrev.isEnabled = Playback.index > 0
        ui.btnNext.isEnabled = Playback.index + 1 < Playback.queue.size
        ui.btnPrev.alpha = if (ui.btnPrev.isEnabled) 1f else 0.35f
        ui.btnNext.alpha = if (ui.btnNext.isEnabled) 1f else 0.35f
    }

    private fun togglePlayPause() {
        if (engine.state == PlaybackState.Playing) {
            engine.pause()
        } else {
            engine.play()
            // `play()` volta para 1×; sem isto a velocidade escolhida some a
            // cada pausa.
            if (playbackSpeed != 1f) engine.rate = playbackSpeed
        }
        scheduleControlsHide()
    }

    private fun jump(delta: Double) {
        val alvo = (engine.currentTime + delta).coerceIn(0.0, max(engine.duration, 0.0))
        showHud(
            TimeFormat.signed(delta),
            "${TimeFormat.clock(alvo)} / ${TimeFormat.clock(engine.duration)}",
            null,
        )
        engine.seek(alvo, precise = false)
        hideHudAfter(700)
    }

    // MARK: - Controles

    /**
     * Um diálogo escuro.
     *
     * O padrão do sistema é claro, e um retângulo branco no meio de um filme no
     * escuro é um flash na cara — literalmente a queixa que faz gente desistir
     * de mexer nos ajustes durante o vídeo.
     */
    private fun dialog(): AlertDialog.Builder =
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)

    private fun bindControls() {
        ui.btnClose.setOnClickListener { finish() }
        ui.btnPlay.setOnClickListener { togglePlayPause() }
        ui.btnRewind.setOnClickListener { jump(-Tuning.DOUBLE_TAP_SECONDS) }
        ui.btnForward.setOnClickListener { jump(Tuning.DOUBLE_TAP_SECONDS) }
        ui.btnPrev.setOnClickListener { goToPrevious() }
        ui.btnNext.setOnClickListener { goToNext() }
        ui.btnAudio.setOnClickListener { showTracks(audio = true) }
        ui.btnSubtitle.setOnClickListener { showTracks(audio = false) }
        ui.btnAspect.setOnClickListener { cycleAspect() }
        ui.btnPip.setOnClickListener { enterPip() }
        ui.btnMore.setOnClickListener { showToolsMenu(it) }
        ui.btnLock.setOnClickListener { setLocked(true) }
        ui.btnUnlock.setOnClickListener { setLocked(false) }

        ui.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, valor: Int, doUsuario: Boolean) {
                if (!doUsuario || engine.duration <= 0) return
                val alvo = engine.duration * valor / bar.max
                ui.tvPosition.text = TimeFormat.clock(alvo)
                // Com o dedo na barra, esconder no meio do arrasto é o pior
                // momento possível: o cronômetro só volta a correr ao soltar.
                cancelControlsHide()
                engine.scrub(alvo, prefs.preciseScrub)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                isBarScrubbing = true
                suppressBuffering = true
                ui.buffering.visibility = View.GONE
                cancelControlsHide()
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                if (engine.duration > 0) {
                    engine.seek(engine.duration * bar.progress / bar.max, precise = true)
                }
                isBarScrubbing = false
                suppressBuffering = false
                scheduleControlsHide()
            }
        })

        applyGravity(anunciar = false)
        setControlsVisible(true)
    }

    /**
     * Os ajustes que a televisão exige.
     *
     * Dois botões deixam de fazer sentido sem uma tela sensível ao toque:
     * bloquear a tela protege contra o dedo que encosta sem querer, e não
     * existe dedo aqui; e a janela flutuante do Android não existe em TV.
     * Mantê-los seria oferecer dois caminhos que não levam a lugar nenhum.
     */
    private fun prepararParaTv() {
        ui.btnLock.visibility = View.GONE
        ui.btnPip.visibility = View.GONE
        // A barra fica mais tempo: a três metros, com um controle na mão, a
        // pessoa demora mais para decidir o que apertar do que com o dedo já
        // sobre o botão.
        ui.seekBar.isFocusable = true
        ui.btnPlay.isFocusableInTouchMode = false
    }

    /**
     * O controle remoto.
     *
     * A regra que faz os dois mundos conviverem: **com a barra escondida, as
     * setas comandam o vídeo; com a barra à mostra, elas andam entre os
     * botões**. É o que todo player de TV faz, e é o que evita o impasse de
     * uma seta significar duas coisas ao mesmo tempo.
     *
     * As teclas de mídia (as do controle com desenho de play e avanço, e as de
     * qualquer teclado bluetooth) valem sempre, com barra ou sem ela.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                togglePlayPause(); return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { engine.play(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { engine.pause(); return true }
            KeyEvent.KEYCODE_MEDIA_STOP -> { finish(); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { jump(30.0); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { jump(-30.0); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { goToNext(); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { goToPrevious(); return true }
        }

        if (!isTv) return super.dispatchKeyEvent(event)

        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            setControlsVisible(true)
            ui.btnMore.post { showToolsMenu(ui.btnMore) }
            return true
        }

        // Com os controles na tela, o foco é quem manda: as setas percorrem os
        // botões e o centro aciona o que estiver focado.
        if (controlsVisible) {
            scheduleControlsHide()
            return super.dispatchKeyEvent(event)
        }

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { jump(-Tuning.DOUBLE_TAP_SECONDS); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { jump(Tuning.DOUBLE_TAP_SECONDS); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                togglePlayPause()
                mostrarControlesComFoco()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                mostrarControlesComFoco()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Mostra a barra já com algo focado.
     *
     * Sem isto a barra aparece e a primeira seta não move nada: o foco está em
     * lugar nenhum, e da poltrona isso é indistinguível de um travamento.
     */
    private fun mostrarControlesComFoco() {
        setControlsVisible(true)
        ui.btnPlay.post { ui.btnPlay.requestFocus() }
        scheduleControlsHide()
    }

    private fun updateProgress(tempo: Double) {
        val duracao = engine.duration
        ui.tvPosition.text = TimeFormat.clock(tempo)
        ui.tvDuration.text = TimeFormat.clock(duracao)
        if (duracao > 0 && !isBarScrubbing) {
            ui.seekBar.progress = (tempo / duracao * ui.seekBar.max).toInt()
            val carregado = engine.bufferedTime
            ui.seekBar.secondaryProgress =
                if (carregado > 0) (carregado / duracao * ui.seekBar.max).toInt() else 0
        }
    }

    private fun setControlsVisible(visivel: Boolean) {
        controlsVisible = visivel
        val alvo = if (visivel) View.VISIBLE else View.GONE
        ui.topBar.visibility = alvo
        ui.bottomBar.visibility = alvo
        if (visivel) {
            controlsVisibleSince = System.currentTimeMillis()
            goFullscreen()
        }
    }

    private fun scheduleControlsHide() {
        cancelControlsHide()
        // Em "Nunca" a barra fica até o usuário tocar na tela — o agendamento
        // simplesmente não acontece.
        val atraso = prefs.autoHide.delayMillis ?: return
        main.postDelayed(hideControlsRunnable, atraso)
    }

    private fun cancelControlsHide() = main.removeCallbacks(hideControlsRunnable)

    private val hideControlsRunnable = Runnable {
        // Só o pausado segura os controles na tela — ali o usuário quer ver o
        // botão de play.
        if (engine.state == PlaybackState.Paused) return@Runnable
        setControlsVisible(false)
    }

    private fun setLocked(travado: Boolean) {
        isLocked = travado
        setControlsVisible(!travado)
        ui.btnUnlock.visibility = if (travado) View.VISIBLE else View.GONE
        if (travado) {
            cancelControlsHide()
            showHud("Tela bloqueada", null, null)
            hideHudAfter(1200)
        } else {
            scheduleControlsHide()
        }
    }

    // MARK: - Gestos

    /**
     * Todo toque passa por aqui antes de chegar aos botões.
     *
     * É o equivalente dos reconhecedores de gesto do UIKit, que convivem com os
     * botões da tela. A regra que faz os dois coexistirem: um toque que COMEÇA
     * dentro de uma das barras pertence às barras — do começo ao fim do gesto,
     * senão arrastar na barra de progresso também rolaria o vídeo.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            touchStartedOnBars =
                (controlsVisible && (dentroDe(ui.topBar, ev) || dentroDe(ui.bottomBar, ev))) ||
                    (isLocked && dentroDe(ui.btnUnlock, ev))
        }

        if (touchStartedOnBars) return super.dispatchTouchEvent(ev)

        // Com a tela bloqueada, todo gesto é ignorado — é para isso que serve.
        if (isLocked) return true

        handleGesture(ev)
        return true
    }

    private fun dentroDe(view: View, ev: MotionEvent): Boolean {
        if (view.visibility != View.VISIBLE) return false
        val pos = IntArray(2)
        view.getLocationOnScreen(pos)
        val x = ev.rawX
        val y = ev.rawY
        return x >= pos[0] && x <= pos[0] + view.width && y >= pos[1] && y <= pos[1] + view.height
    }

    private fun handleGesture(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                panAxis = PanAxis.UNDECIDED
                panStartX = ev.x
                panStartY = ev.y
                panStartTime = engine.currentTime
                panStartBrightness = brilhoAtual()
                panStartVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                panIsOnLeftHalf = ev.x < ui.root.width / 2f

                // Mostrar e esconder acontecem os dois no encostar do dedo.
                //
                // O toque único teria que esperar o sistema descartar a
                // hipótese de toque duplo — uns 0,3 s. Nesse intervalo parece
                // que nada aconteceu, o usuário toca de novo, vira toque duplo
                // e o vídeo pausa. Respondendo já no toque, a barra aparece na
                // hora e o gesto duplo continua valendo para o que serve.
                if (controlsVisible) {
                    // A margem existe para o segundo dedo de uma pinça não
                    // desfazer o que o primeiro fez.
                    if (System.currentTimeMillis() - controlsVisibleSince > 300) {
                        setControlsVisible(false)
                    }
                } else {
                    setControlsVisible(true)
                    scheduleControlsHide()
                }

                agendarToqueLongo()
                detectarToqueDuplo(ev)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelarToqueLongo()
                if (ev.pointerCount == 2) {
                    panAxis = PanAxis.MULTITOUCH
                    pinchStartZoom = videoZoom
                    pinchStartDistance = distancia(ev)
                    lastMidX = (ev.getX(0) + ev.getX(1)) / 2
                    lastMidY = (ev.getY(0) + ev.getY(1)) / 2
                    cancelControlsHide()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (panAxis == PanAxis.MULTITOUCH) {
                    if (ev.pointerCount >= 2) handlePinch(ev)
                    return
                }
                val dx = ev.x - panStartX
                val dy = ev.y - panStartY

                if (panAxis == PanAxis.UNDECIDED) {
                    if (max(abs(dx), abs(dy)) <= axisLockThreshold) return
                    cancelarToqueLongo()
                    panAxis = if (abs(dx) > abs(dy)) PanAxis.HORIZONTAL else PanAxis.VERTICAL
                    // Qualquer gesto em curso segura os controles na tela.
                    cancelControlsHide()
                    if (panAxis == PanAxis.HORIZONTAL) {
                        suppressBuffering = true
                        ui.buffering.visibility = View.GONE
                    }
                }

                when (panAxis) {
                    PanAxis.HORIZONTAL -> updateSeekPan(dx)
                    PanAxis.VERTICAL -> updateVerticalPan(dy)
                    else -> {}
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelarToqueLongo()
                if (holdActive) {
                    holdActive = false
                    engine.rate = rateBeforeHold
                    hideHudAfter(400)
                }
                if (panAxis == PanAxis.HORIZONTAL) suppressBuffering = false
                if (panAxis == PanAxis.MULTITOUCH) encaixarZoom()
                panAxis = PanAxis.UNDECIDED
                scheduleControlsHide()
                hideHudAfter(700)
            }
        }
    }

    private fun distancia(ev: MotionEvent): Float {
        val dx = ev.getX(0) - ev.getX(1)
        val dy = ev.getY(0) - ev.getY(1)
        return kotlin.math.hypot(dx, dy)
    }

    // MARK: - Rolagem horizontal

    private fun updateSeekPan(dx: Float) {
        if (engine.duration <= 0) return

        // Escala a sensibilidade com a duração: num vídeo de 3 h, arrastar a
        // tela inteira por 2 min é inútil; num clipe de 40 s, 2 min é grosseiro.
        val span = (engine.duration / 4).coerceIn(30.0, Tuning.SEEK_SECONDS_PER_SCREEN_WIDTH * 4)
        val segundosPorPixel =
            min(span, Tuning.SEEK_SECONDS_PER_SCREEN_WIDTH) / ui.root.width
        val alvo = (panStartTime + dx * segundosPorPixel).coerceIn(0.0, engine.duration)

        showHud(
            TimeFormat.clock(alvo),
            TimeFormat.signed(alvo - panStartTime),
            null,
        )
        ui.tvPosition.text = TimeFormat.clock(alvo)
        if (engine.duration > 0) {
            ui.seekBar.progress = (alvo / engine.duration * ui.seekBar.max).toInt()
        }

        // É aqui que mora a "rolagem integral": em vez de só mostrar um rótulo
        // e buscar no fim, mandamos buscas exatas durante o arrasto, com ritmo
        // controlado pelo motor. Sem isso o vídeo pula de keyframe em keyframe,
        // que é a queixa que originou o projeto.
        engine.scrub(alvo, prefs.preciseScrub)
    }

    // MARK: - Brilho e volume

    private fun updateVerticalPan(dy: Float) {
        // Para cima aumenta: invertemos porque dy cresce para baixo.
        val curso = ui.root.height * Tuning.VERTICAL_TRAVEL_FRACTION
        val fracao = -dy / curso

        if (panIsOnLeftHalf) {
            val valor = (panStartBrightness + fracao).coerceIn(0.01f, 1f)
            window.attributes = window.attributes.apply { screenBrightness = valor }
            showHud("${(valor * 100).toInt()}%", "Brilho", (valor * 100).toInt())
            ui.hudIcon.setImageResource(R.drawable.ic_brightness)
            ui.hudIcon.visibility = View.VISIBLE
        } else {
            val maximo = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val valor = (panStartVolume + fracao * maximo).toInt().coerceIn(0, maximo)
            // Volume do aparelho, o mesmo dos botões laterais — e sem a régua
            // do sistema por cima, porque o balão do gesto já diz o mesmo.
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, valor, 0)
            showHud("${valor * 100 / maximo}%", "Volume", valor * 100 / maximo)
            ui.hudIcon.setImageResource(R.drawable.ic_volume)
            ui.hudIcon.visibility = View.VISIBLE
        }
    }

    /**
     * O brilho de onde o gesto parte.
     *
     * `screenBrightness` vale -1 enquanto a janela não tiver brilho próprio —
     * ela está seguindo o do sistema. Começar de -1 faria o primeiro arrasto
     * apagar a tela.
     */
    private fun brilhoAtual(): Float {
        val daJanela = window.attributes.screenBrightness
        if (daJanela >= 0) return daJanela
        return runCatching {
            android.provider.Settings.System.getInt(
                contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS
            ) / 255f
        }.getOrDefault(0.5f)
    }

    /** Os botões físicos de volume usam o mesmo balão do gesto. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val passo = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> 1
            KeyEvent.KEYCODE_VOLUME_DOWN -> -1
            else -> return super.onKeyDown(keyCode, event)
        }
        val maximo = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val novo = (audio.getStreamVolume(AudioManager.STREAM_MUSIC) + passo).coerceIn(0, maximo)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, novo, 0)
        ui.hudIcon.setImageResource(R.drawable.ic_volume)
        ui.hudIcon.visibility = View.VISIBLE
        showHud("${novo * 100 / maximo}%", "Volume", novo * 100 / maximo)
        hideHudAfter(1000)
        return true
    }

    // MARK: - Toque duplo e toque longo

    private var lastTapAt = 0L
    private var lastTapX = 0f

    private fun detectarToqueDuplo(ev: MotionEvent) {
        val agora = System.currentTimeMillis()
        val intervalo = ViewConfiguration.getDoubleTapTimeout().toLong()
        if (agora - lastTapAt < intervalo && abs(ev.x - lastTapX) < 120) {
            lastTapAt = 0
            cancelarToqueLongo()
            val terco = ui.root.width / 3f
            when {
                ev.x < terco -> jump(-Tuning.DOUBLE_TAP_SECONDS)
                ev.x > terco * 2 -> jump(Tuning.DOUBLE_TAP_SECONDS)
                else -> togglePlayPause()
            }
            return
        }
        lastTapAt = agora
        lastTapX = ev.x
    }

    private val toqueLongo = Runnable {
        if (engine.state != PlaybackState.Playing) return@Runnable
        holdActive = true
        rateBeforeHold = engine.rate
        engine.rate = prefs.holdSpeed
        ui.hudIcon.setImageResource(R.drawable.ic_speed)
        ui.hudIcon.visibility = View.VISIBLE
        showHud("${prefs.holdSpeed}×", "Segurando", null)
    }

    private fun agendarToqueLongo() = main.postDelayed(toqueLongo, 350)
    private fun cancelarToqueLongo() = main.removeCallbacks(toqueLongo)

    // MARK: - Ampliação

    /**
     * Pinça amplia e reduz a imagem, como em qualquer foto.
     *
     * Antes (no app de iOS) ela percorria os enquadramentos — mas para isso já
     * existe o botão "Enquadrar", e nenhum outro app do aparelho usa a pinça
     * assim. Dois dedos arrastando movem a imagem ampliada: um dedo já é
     * rolagem e volume, e mexer nisso custaria os gestos principais para servir
     * a um caso ocasional.
     */
    private fun handlePinch(ev: MotionEvent) {
        val distancia = distancia(ev)
        if (pinchStartDistance > 0 && distancia > 0) {
            videoZoom = (pinchStartZoom * distancia / pinchStartDistance).coerceIn(0.5f, 6f)
        }
        val midX = (ev.getX(0) + ev.getX(1)) / 2
        val midY = (ev.getY(0) + ev.getY(1)) / 2
        videoOffsetX += midX - lastMidX
        videoOffsetY += midY - lastMidY
        lastMidX = midX
        lastMidY = midY

        applyZoom()
        showHud("${(videoZoom * 100).toInt()}%", "Ampliação", null)
        cancelControlsHide()
    }

    private fun applyZoom() {
        // A imagem não pode ser arrastada para fora de vista: o limite é a
        // sobra que a ampliação criou de cada lado.
        val sobraX = max(0f, ui.root.width * (videoZoom - 1) / 2)
        val sobraY = max(0f, ui.root.height * (videoZoom - 1) / 2)
        videoOffsetX = videoOffsetX.coerceIn(-sobraX, sobraX)
        videoOffsetY = videoOffsetY.coerceIn(-sobraY, sobraY)

        ui.videoLayout.scaleX = videoZoom
        ui.videoLayout.scaleY = videoZoom
        ui.videoLayout.translationX = videoOffsetX
        ui.videoLayout.translationY = videoOffsetY
    }

    /**
     * Perto do tamanho original a pinça encaixa em 1×: acertar exatamente 100%
     * com dois dedos é impossível, e ficar em 1,03× deixa a imagem tremida sem
     * nenhum motivo.
     */
    private fun encaixarZoom() {
        if (abs(videoZoom - 1f) < 0.08f) {
            videoZoom = 1f
            videoOffsetX = 0f
            videoOffsetY = 0f
            applyZoom()
        }
    }

    private fun resetZoom() {
        videoZoom = 1f
        videoOffsetX = 0f
        videoOffsetY = 0f
        applyZoom()
        showHud("100%", "Ampliação", null)
        hideHudAfter(900)
    }

    // MARK: - Enquadramento

    /**
     * Percorre os enquadramentos e desfaz a ampliação.
     *
     * "Enquadrar" é justamente o botão de "ajeita isso aí": deixar a imagem
     * ampliada por baixo faria o enquadramento novo chegar recortado, e daria a
     * impressão de que o botão não fez nada.
     */
    private fun cycleAspect() {
        gravityIndex = (gravityIndex + 1) % gravityModes.size
        videoZoom = 1f
        videoOffsetX = 0f
        videoOffsetY = 0f
        applyZoom()
        applyGravity()
        scheduleControlsHide()
    }

    private fun applyGravity(anunciar: Boolean = true) {
        val (modo, nome) = gravityModes[gravityIndex]
        engine.setScale(modo)
        if (anunciar) {
            showHud(nome, "Enquadramento", null)
            hideHudAfter(900)
        }
    }

    // MARK: - Balão dos gestos

    private fun showHud(titulo: String, legenda: String?, barra: Int?) {
        main.removeCallbacks(hideHudRunnable)
        ui.hud.visibility = View.VISIBLE
        ui.hudText.text = if (legenda == null) titulo else "$titulo\n$legenda"
        ui.hudText.textAlignment = View.TEXT_ALIGNMENT_CENTER
        if (barra == null) {
            ui.hudBar.visibility = View.GONE
        } else {
            ui.hudBar.visibility = View.VISIBLE
            ui.hudBar.progress = barra
        }
    }

    private fun hideHudAfter(millis: Long) {
        main.removeCallbacks(hideHudRunnable)
        main.postDelayed(hideHudRunnable, millis)
    }

    private val hideHudRunnable = Runnable {
        ui.hud.visibility = View.GONE
        ui.hudIcon.visibility = View.GONE
        ui.hudBar.visibility = View.GONE
    }

    // MARK: - Faixas

    private fun showTracks(audio: Boolean) {
        cancelControlsHide()
        val faixas = if (audio) engine.audioTracks else engine.subtitleTracks
        val atual = if (audio) engine.currentAudioTrack else engine.currentSubtitleTrack

        if (faixas.isEmpty()) {
            Toast.makeText(
                this,
                if (audio) "Este arquivo tem só uma faixa de áudio."
                else "Este arquivo não tem legendas embutidas.",
                Toast.LENGTH_SHORT,
            ).show()
            scheduleControlsHide()
            return
        }

        val rotulos = mutableListOf<String>()
        val ids = mutableListOf<Int?>()
        if (!audio) {
            rotulos += if (atual == null) "✓ Desligada" else "Desligada"
            ids += null
        }
        for (faixa in faixas) {
            rotulos += (if (faixa.id == atual) "✓ " else "") + faixa.label
            ids += faixa.id
        }

        dialog()
            .setTitle(if (audio) "Faixas de áudio" else "Legendas")
            .setItems(rotulos.toTypedArray()) { _, i ->
                val id = ids[i]
                if (audio) id?.let { engine.selectAudioTrack(it) }
                else engine.selectSubtitleTrack(id)
                // Sem isto a barra ficava presa na tela depois de trocar de
                // faixa: abrir a lista cancela o agendamento e nada o repunha.
                scheduleControlsHide()
            }
            .setOnDismissListener { scheduleControlsHide() }
            .show()
    }

    // MARK: - Menu de ferramentas

    private fun showToolsMenu(ancora: View) {
        cancelControlsHide()
        val menu = PopupMenu(this, ancora)
        val m = menu.menu
        var id = 1
        val acoes = LinkedHashMap<Int, () -> Unit>()

        fun item(titulo: String, acao: () -> Unit) {
            m.add(0, id, 0, titulo)
            acoes[id] = acao
            id++
        }

        item(if (engine.isMuted) "✓ Mudo" else "Mudo") { engine.isMuted = !engine.isMuted }
        item(if (repeatMode == RepeatMode.ONE) "✓ Repetir este vídeo" else "Repetir este vídeo") {
            repeatMode = if (repeatMode == RepeatMode.ONE) RepeatMode.OFF else RepeatMode.ONE
        }
        if (Playback.queue.size > 1) {
            item(if (isShuffling) "✓ Aleatório" else "Aleatório") { isShuffling = !isShuffling }
        }
        item("Velocidade (${playbackSpeed}×)") { showSpeedSheet() }
        item("Captura de tela") { takeSnapshot() }
        item("Ampliação normal") { resetZoom() }
        // Girar e bloquear são respostas a problemas que só existem num
        // aparelho de mão: a tela que vira sozinha e o dedo que encosta sem
        // querer. Numa televisão as duas entradas seriam caminhos para lugar
        // nenhum no meio de um menu curto.
        if (!isTv) {
            item("Girar tela") { toggleOrientation() }
            item("Bloquear tela") { setLocked(true) }
        }
        item("Modo noturno") { cycleNightMode() }
        item(sleepTimerTitle()) { showSleepSheet() }
        item("Segurar para acelerar (${prefs.holdSpeed}×)") { showHoldSpeedSheet() }
        item("Ocultar barra: ${prefs.autoHide.title}") { showAutoHideSheet() }
        item(if (prefs.preciseScrub) "✓ Rolagem quadro a quadro" else "Rolagem quadro a quadro") {
            prefs.preciseScrub = !prefs.preciseScrub
            showHud(
                if (prefs.preciseScrub) "Quadro a quadro" else "Por keyframe",
                "Rolagem", null,
            )
            hideHudAfter(1400)
        }

        menu.setOnMenuItemClickListener { escolhido ->
            acoes[escolhido.itemId]?.invoke()
            scheduleControlsHide()
            true
        }
        menu.setOnDismissListener { scheduleControlsHide() }
        menu.show()
    }

    private fun showSpeedSheet() {
        val valores = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        val rotulos = valores.map {
            (if (abs(it - playbackSpeed) < 0.01f) "✓ " else "") + "${it}×"
        }
        dialog()
            .setTitle("Velocidade")
            .setItems(rotulos.toTypedArray()) { _, i ->
                playbackSpeed = valores[i]
                if (engine.state == PlaybackState.Playing) engine.rate = playbackSpeed
                showHud("${playbackSpeed}×", "Velocidade", null)
                hideHudAfter(900)
            }
            .setOnDismissListener { scheduleControlsHide() }
            .show()
    }

    private fun showHoldSpeedSheet() {
        val opcoes = PlayerPreferences.holdSpeedOptions
        val rotulos = opcoes.map {
            (if (abs(it - prefs.holdSpeed) < 0.01f) "✓ " else "") + "${it}×"
        }
        dialog()
            .setTitle("Segurar para acelerar")
            .setItems(rotulos.toTypedArray()) { _, i -> prefs.holdSpeed = opcoes[i] }
            .setOnDismissListener { scheduleControlsHide() }
            .show()
    }

    private fun showAutoHideSheet() {
        val opcoes = PlayerPreferences.AutoHide.entries
        val rotulos = opcoes.map {
            (if (it == prefs.autoHide) "✓ " else "") + it.title
        }
        dialog()
            .setTitle("Ocultar barra depois de")
            .setItems(rotulos.toTypedArray()) { _, i ->
                prefs.autoHide = opcoes[i]
                // Reagenda já com o valor novo, para o efeito ser sentido nesta
                // mesma vez em vez de só no próximo toque.
                scheduleControlsHide()
            }
            .setOnDismissListener { scheduleControlsHide() }
            .show()
    }

    /**
     * Percorre os níveis em vez de abrir um menu: uma ferramenta de um toque
     * só, que é o ponto de ela existir.
     */
    private fun cycleNightMode() {
        val niveis = listOf(0f, 0.25f, 0.45f, 0.65f)
        val atual = niveis.indexOfFirst { abs(it - ui.dimView.alpha) < 0.01f }.coerceAtLeast(0)
        val proximo = niveis[(atual + 1) % niveis.size]
        ui.dimView.animate().alpha(proximo).setDuration(200).start()
        showHud(
            if (proximo == 0f) "Desligado" else "${(proximo * 100).toInt()}%",
            "Modo noturno", null,
        )
        hideHudAfter(1200)
    }

    private fun sleepTimerTitle(): String {
        val prazo = sleepDeadline ?: return "Tempo para dormir"
        val restante = max(0, prazo - System.currentTimeMillis())
        return "Dormir em ${restante / 60000 + 1} min"
    }

    private fun showSleepSheet() {
        val minutos = listOf(15, 30, 45, 60)
        val rotulos = buildList {
            add(if (sleepDeadline == null) "✓ Desligado" else "Desligado")
            addAll(minutos.map { "$it minutos" })
            add("No fim do vídeo")
        }
        dialog()
            .setTitle(sleepTimerTitle())
            .setItems(rotulos.toTypedArray()) { _, i ->
                main.removeCallbacks(sleepRunnable)
                when (i) {
                    0 -> {
                        sleepDeadline = null
                        showHud("Desligado", "Temporizador", null)
                    }
                    rotulos.lastIndex -> {
                        val restante = max(1.0, engine.duration - engine.currentTime)
                        armarSono((restante * 1000).toLong())
                    }
                    else -> armarSono(minutos[i - 1] * 60_000L)
                }
                hideHudAfter(1800)
            }
            .setOnDismissListener { scheduleControlsHide() }
            .show()
    }

    private fun armarSono(millis: Long) {
        sleepDeadline = System.currentTimeMillis() + millis
        main.postDelayed(sleepRunnable, millis)
        showHud("Dormir em ${millis / 60000} min", "Temporizador", null)
    }

    private fun toggleOrientation() {
        requestedOrientation =
            if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        scheduleControlsHide()
    }

    private fun takeSnapshot() {
        val imagem = engine.snapshot()
        if (imagem == null) {
            showHud("Nada para capturar", null, null)
            hideHudAfter(1500)
            return
        }
        val salvo = salvarNaGaleria(imagem)
        showHud(if (salvo) "Salvo em Imagens" else "Não deu para salvar", null, null)
        hideHudAfter(1800)
    }

    private fun salvarNaGaleria(imagem: Bitmap): Boolean = runCatching {
        val nome = "viper_${System.currentTimeMillis()}.jpg"
        val valores = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, nome)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Viper")
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, valores)
            ?: return false
        contentResolver.openOutputStream(uri)?.use { saida ->
            imagem.compress(Bitmap.CompressFormat.JPEG, 92, saida)
        }
        true
    }.getOrDefault(false)

    // MARK: - Janela flutuante

    /**
     * Aqui ela existe de verdade.
     *
     * No iOS a janelinha só é montada pelo sistema sobre a camada do AVPlayer,
     * e como quem toca é o VLC, o botão vira uma explicação. No Android a
     * janela é um modo da atividade inteira, independente de quem desenha —
     * então o mesmo botão, com o mesmo motor, simplesmente funciona.
     */
    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, "Este Android não tem janela flutuante.", Toast.LENGTH_SHORT).show()
            return
        }
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .build()
        runCatching { enterPictureInPictureMode(params) }
            .onFailure { Toast.makeText(this, "A janela flutuante está desativada nos ajustes do Android.", Toast.LENGTH_LONG).show() }
    }

    private fun isInPip(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode

    override fun onPictureInPictureModeChanged(inPip: Boolean, config: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(inPip, config)
        // Na janelinha não cabe barra nenhuma: ela tem a altura de um dedo.
        setControlsVisible(!inPip)
        ui.hud.visibility = View.GONE
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Sair pelo botão de início com o vídeo tocando entra na janelinha, em
        // vez de parar — é o que todo player de vídeo do Android faz.
        // Em televisão não há janelinha para onde ir, e tentar entrar nela
        // deixaria o vídeo tocando sem tela ao sair do app.
        if (!isTv && engine.state == PlaybackState.Playing &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        ) {
            enterPip()
        }
    }
}
