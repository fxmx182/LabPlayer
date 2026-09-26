package com.mauricio.libertyx.guia

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mauricio.libertyx.R
import com.mauricio.libertyx.core.LabTheme
import com.mauricio.libertyx.core.Prefs
import com.mauricio.libertyx.core.TimeFormat
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * O guia da primeira abertura.
 *
 * Os gestos são o que o LibertyX tem de melhor, e são invisíveis: nada na tela
 * diz que segurar acelera ou que afastar o dedo da barra afina a rolagem. Um
 * texto explicando não ensina — o dedo aprende fazendo. Por isso cada página
 * tem uma tela de player de mentira onde o gesto funciona de verdade, e a
 * página só dá o "isso!" depois que ele foi feito.
 *
 * Nunca prende: "Próximo" e "Pular" funcionam sem fazer gesto nenhum. Aparece
 * uma vez, antes do pedido de permissão (a última página avisa dele), e pode
 * ser revisto em Exibição › Ver o guia.
 */
object Guia {
    private const val K_VISTO = "guia.visto.v1"

    fun precisaMostrar(contexto: Context): Boolean = !Prefs.get(contexto).getBoolean(K_VISTO, false)

    fun marcarVisto(contexto: Context) = Prefs.get(contexto).edit().putBoolean(K_VISTO, true).apply()
}

private val Ouro = LabTheme.accent
private val Vidro = Color(0xCC0B0B0E)

@Composable
fun GuiaDeBoasVindas(onFim: () -> Unit) {
    val paginas = remember { paginasDoGuia() }
    val pager = rememberPagerState(pageCount = { paginas.size })
    val escopo = rememberCoroutineScope()
    val feitas = remember { mutableStateListOf<Int>() }
    val ultima = pager.currentPage == paginas.lastIndex

    BackHandler(enabled = pager.currentPage > 0) {
        escopo.launch { pager.animateScrollToPage(pager.currentPage - 1) }
    }

    Box(Modifier.fillMaxSize().background(LabTheme.background)) {
        BrilhoDeFundo()
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            // Topo: onde se está e a saída.
            Row(
                Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(painterResource(R.drawable.ic_marca), null, Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Guia rápido", color = LabTheme.muted, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                )
                AnimatedVisibility(!ultima, enter = fadeIn(), exit = fadeOut()) {
                    Text(
                        "Pular", color = LabTheme.muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(CircleShape).clickable(onClick = onFim)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }

            HorizontalPager(state = pager, modifier = Modifier.weight(1f), beyondViewportPageCount = 0) { i ->
                val feita = i in feitas
                PaginaDoGuia(paginas[i], i, paginas.size, feita) { if (i !in feitas) feitas += i }
            }

            // Pé: os pontos e o próximo passo.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(paginas.size) { i ->
                        val atual = i == pager.currentPage
                        val largura by animateDpAsState(if (atual) 22.dp else 6.dp, label = "ponto")
                        val cor by animateColorAsState(
                            when {
                                atual -> Ouro
                                i in feitas -> Ouro.copy(alpha = 0.45f)
                                else -> Color.White.copy(alpha = 0.18f)
                            }, label = "cor do ponto",
                        )
                        Box(Modifier.height(6.dp).width(largura).clip(CircleShape).background(cor))
                    }
                }
                val pulso = rememberInfiniteTransition(label = "pulso")
                val brilho by pulso.animateFloat(
                    0f, 1f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                    label = "brilho",
                )
                val chama = pager.currentPage in feitas || ultima
                Box(
                    Modifier
                        .drawBehind {
                            if (chama) drawRoundRect(
                                Ouro.copy(alpha = 0.10f + 0.18f * brilho),
                                topLeft = Offset(-6.dp.toPx(), -6.dp.toPx()),
                                size = Size(size.width + 12.dp.toPx(), size.height + 12.dp.toPx()),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height),
                            )
                        }
                        .clip(CircleShape).background(Ouro)
                        .clickable {
                            if (ultima) onFim()
                            else escopo.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                        }
                        .padding(horizontal = 26.dp, vertical = 13.dp),
                ) {
                    AnimatedContent(ultima, label = "botão") { fim ->
                        Text(
                            if (fim) "Começar" else "Próximo",
                            color = Color(0xFF1A1405), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold,
                        )
                    }
                }
            }
        }
    }
}

/** Uma luz dourada no alto, a mesma da biblioteca — o guia já é o app. */
@Composable
private fun BrilhoDeFundo() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            Brush.radialGradient(
                listOf(Ouro.copy(alpha = 0.13f), Color.Transparent),
                center = Offset(size.width * 0.2f, 0f), radius = size.maxDimension * 0.7f,
            )
        )
    }
}

// ─── A página ───────────────────────────────────────────────────────────────

private class Pagina(
    val etiqueta: String,
    val titulo: String,
    val texto: String,
    /** O convite para experimentar; `null` na página que não tem gesto. */
    val tente: String?,
    val feito: String,
    val demo: @Composable (onFeito: () -> Unit) -> Unit,
)

@Composable
private fun PaginaDoGuia(p: Pagina, indice: Int, total: Int, feita: Boolean, onFeito: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val deitado = maxWidth > maxHeight
        if (deitado) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                Box(Modifier.weight(1.1f).fillMaxHeight(), contentAlignment = Alignment.Center) { p.demo(onFeito) }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    TextoDaPagina(p, indice, total, feita)
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            ) {
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().widthIn(max = 560.dp), contentAlignment = Alignment.Center) {
                    p.demo(onFeito)
                }
                Spacer(Modifier.height(26.dp))
                TextoDaPagina(p, indice, total, feita)
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun TextoDaPagina(p: Pagina, indice: Int, total: Int, feita: Boolean) {
    Text(
        "${p.etiqueta}  ·  ${indice + 1} de $total".uppercase(),
        color = Ouro, style = LabTheme.sectionTitle, fontSize = 11.sp,
    )
    Spacer(Modifier.height(10.dp))
    Text(p.titulo, style = LabTheme.headline, fontSize = 28.sp, lineHeight = 32.sp, color = LabTheme.text)
    Spacer(Modifier.height(10.dp))
    Text(p.texto, color = LabTheme.muted, fontSize = 15.sp, lineHeight = 22.sp)
    if (p.tente != null) {
        Spacer(Modifier.height(18.dp))
        Convite(p.tente, p.feito, feita)
    }
}

/** "Experimente: …", que vira "Isso! …" quando o gesto sai. */
@Composable
private fun Convite(tente: String, feito: String, feita: Boolean) {
    val forma = RoundedCornerShape(14.dp)
    val fundo by animateColorAsState(
        if (feita) LabTheme.green.copy(alpha = 0.14f) else Ouro.copy(alpha = 0.10f), label = "convite",
    )
    val borda by animateColorAsState(
        if (feita) LabTheme.green.copy(alpha = 0.55f) else Ouro.copy(alpha = 0.40f), label = "borda",
    )
    Row(
        Modifier.clip(forma).background(fundo).border(1.dp, borda, forma).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(feita, transitionSpec = { scaleIn() + fadeIn() togetherWith fadeOut() }, label = "ícone") { ok ->
            Icon(
                if (ok) Icons.Rounded.Check else Icons.Rounded.TouchApp, null,
                tint = if (ok) LabTheme.green else Ouro, modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        AnimatedContent(feita, label = "texto") { ok ->
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) { append(if (ok) "Isso! " else "Experimente: ") }
                    append(if (ok) feito else tente)
                },
                color = LabTheme.text, fontSize = 14.sp, lineHeight = 19.sp,
            )
        }
    }
}

// ─── O player de mentira ────────────────────────────────────────────────────

private data class Balao(val titulo: String, val legenda: String? = null, val barra: Float? = null, val dourada: Boolean = false)
private data class Halo(val lado: Int, val texto: String, val serie: Int)

/**
 * O estado da tela de demonstração: um filme de 42 minutos que "toca" de
 * verdade — o céu anda, as nuvens passam —, para que pausar, acelerar e
 * buscar tenham efeito visível.
 */
@Stable
private class Demo {
    val duracao = 42f * 60f
    var tempo by mutableFloatStateOf(12f * 60f + 34f)
    var tocando by mutableStateOf(true)
    var velocidade by mutableFloatStateOf(1f)
    var brilho by mutableFloatStateOf(0.85f)
    var volume by mutableFloatStateOf(0.6f)
    var zoom by mutableFloatStateOf(1f)
    var desloc by mutableStateOf(Offset.Zero)
    var balao by mutableStateOf<Balao?>(null)
    /** O último mostrado, para o balão sumir com o texto e não vazio. */
    var ultimoBalao by mutableStateOf(Balao(""))
    var serieBalao by mutableIntStateOf(0)
    var halo by mutableStateOf<Halo?>(null)
    var mostraBarra by mutableStateOf(true)

    fun mostrar(b: Balao) { balao = b; ultimoBalao = b; serieBalao++ }
    fun pular(s: Float) { tempo = (tempo + s).coerceIn(0f, duracao) }
}

@Composable
private fun rememberDemo(barra: Boolean = true): Demo {
    val d = remember { Demo().apply { mostraBarra = barra } }
    LaunchedEffect(d) {
        var antes = 0L
        while (true) {
            withFrameNanos { agora ->
                if (antes != 0L && d.tocando) {
                    val dt = (agora - antes) / 1e9f
                    d.tempo = (d.tempo + dt * d.velocidade).let { if (it >= d.duracao) 0f else it }
                }
                antes = agora
            }
        }
    }
    LaunchedEffect(d.serieBalao) {
        if (d.balao != null) { delay(1100); d.balao = null }
    }
    return d
}

/**
 * A moldura do vídeo, com a cena, os avisos do player e a camada de gestos
 * que cada página passa em [gestos].
 */
@Composable
private fun TelaDeDemo(d: Demo, gestos: Modifier, extra: @Composable BoxScope.() -> Unit = {}) {
    val forma = RoundedCornerShape(22.dp)
    Box(
        Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(forma)
            .border(1.dp, Color.White.copy(alpha = 0.12f), forma)
            .background(Color.Black)
            .then(gestos),
    ) {
        Cena(
            d.tempo,
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = d.zoom; scaleY = d.zoom
                translationX = d.desloc.x; translationY = d.desloc.y
            },
        )
        // Brilho: o quanto falta para o máximo vira sombra por cima.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = (1f - d.brilho) * 0.8f)))

        if (!d.tocando) {
            Box(
                Modifier.align(Alignment.Center).size(54.dp).clip(CircleShape).background(Vidro),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(32.dp)) }
        }

        HaloDoToque(d.halo)

        // A velocidade do segurar, na borda direita — como no player.
        AnimatedVisibility(
            d.velocidade > 1f, Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
            enter = fadeIn(), exit = fadeOut(),
        ) {
            Text(
                "${velocidadeEmTexto(d.velocidade)} ▸▸", color = Ouro, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.clip(CircleShape).background(Vidro).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        AnimatedVisibility(
            d.balao != null, Modifier.align(Alignment.TopCenter).padding(top = 14.dp),
            enter = fadeIn(tween(120)), exit = fadeOut(tween(250)),
        ) {
            BalaoDaDemo(d.ultimoBalao)
        }

        if (d.mostraBarra) BarraDoTempo(d, Modifier.align(Alignment.BottomCenter))
        extra()
    }
}

@Composable
private fun BalaoDaDemo(b: Balao) {
    Column(
        Modifier.clip(RoundedCornerShape(14.dp)).background(Vidro)
            .border(0.5.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(b.titulo, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        if (b.legenda != null) {
            Text(b.legenda, color = if (b.dourada) Ouro else LabTheme.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        if (b.barra != null) {
            Spacer(Modifier.height(6.dp))
            Box(Modifier.width(90.dp).height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(b.barra.coerceIn(0f, 1f)).background(Ouro))
            }
        }
    }
}

@Composable
private fun BarraDoTempo(d: Demo, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(TimeFormat.clock(d.tempo.toDouble()), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Box(Modifier.weight(1f).padding(horizontal = 10.dp).height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(d.tempo / d.duracao).background(Ouro))
        }
        Text(TimeFormat.clock(d.duracao.toDouble()), color = LabTheme.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** O semicírculo do toque duplo, que nasce da borda tocada e se apaga. */
@Composable
private fun HaloDoToque(h: Halo?) {
    if (h == null) return
    val alfa = remember(h.serie) { Animatable(1f) }
    LaunchedEffect(h.serie) { alfa.animateTo(0f, tween(700, delayMillis = 250)) }
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer { alpha = alfa.value }) {
        val w = maxWidth
        when (h.lado) {
            0 -> Box(
                Modifier.align(Alignment.Center).size(64.dp).clip(CircleShape).background(Vidro),
                contentAlignment = Alignment.Center,
            ) { Icon(if (h.texto == "pause") Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(36.dp)) }
            else -> {
                Box(
                    Modifier.align(if (h.lado < 0) Alignment.CenterStart else Alignment.CenterEnd)
                        .offset(x = if (h.lado < 0) -w * 0.12f else w * 0.12f)
                        .size(w * 0.5f).clip(CircleShape).background(Color.White.copy(alpha = 0.13f)),
                )
                Text(
                    h.texto, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.align(if (h.lado < 0) Alignment.CenterStart else Alignment.CenterEnd)
                        .padding(horizontal = w * 0.1f),
                )
            }
        }
    }
}

/**
 * Um pôr do sol que anda com o tempo do filme: o sol cruza o céu, as nuvens
 * e os morros deslizam. Buscar, pausar e acelerar mudam o quadro — é assim que
 * a demonstração mostra que o gesto funcionou.
 */
@Composable
private fun Cena(tempo: Float, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        drawRect(Brush.verticalGradient(listOf(Color(0xFF101A3A), Color(0xFF3E2B5E), Color(0xFFD9745A), Color(0xFFF4BC6A))))
        val ciclo = (tempo % 240f) / 240f
        val sol = Offset(w * (0.12f + 0.76f * ciclo), h * 0.72f - sin(ciclo * PI).toFloat() * h * 0.42f)
        drawCircle(Brush.radialGradient(listOf(Color(0x66FFD27A), Color.Transparent), sol, h * 0.35f), h * 0.35f, sol)
        drawCircle(Color(0xFFFFE3A3), h * 0.09f, sol)
        for (i in 0 until 4) {
            val x = ((i * w / 3f + tempo * 18f * (1 + i % 2)) % (w * 1.4f)) - w * 0.2f
            val y = h * (0.14f + 0.09f * i)
            drawOval(Color.White.copy(alpha = 0.16f), Offset(x, y), Size(w * 0.18f, h * 0.05f))
            drawOval(Color.White.copy(alpha = 0.12f), Offset(x + w * 0.05f, y - h * 0.025f), Size(w * 0.12f, h * 0.05f))
        }
        fun morro(base: Float, amp: Float, fase: Float, cor: Color) {
            val p = Path().apply {
                moveTo(0f, h)
                var x = 0f
                while (x <= w) {
                    lineTo(x, base + amp * sin((x / w * 2.4f * PI + fase).toDouble()).toFloat()); x += w / 60f
                }
                lineTo(w, h); close()
            }
            drawPath(p, cor)
        }
        morro(h * 0.70f, h * 0.06f, tempo * 0.05f, Color(0xFF4A2A4E))
        morro(h * 0.80f, h * 0.05f, tempo * 0.11f + 1.3f, Color(0xFF26162E))
        morro(h * 0.90f, h * 0.035f, tempo * 0.2f + 2.1f, Color(0xFF120B18))
    }
}

private fun velocidadeEmTexto(v: Float): String =
    if (v == v.roundToInt().toFloat()) "${v.roundToInt()}×" else "${v.toString().replace('.', ',')}×"

// ─── As páginas ─────────────────────────────────────────────────────────────

private fun paginasDoGuia(): List<Pagina> = listOf(
    Pagina(
        "Bem-vindo", "O seu cinema, sem limites",
        "O LibertyX abre praticamente tudo — MKV, HEVC, 4K HDR, legendas ASS e PGS, várias faixas de " +
            "áudio — do aparelho, do pendrive ou do computador de casa. Em um minuto você aprende os " +
            "atalhos que fazem a diferença.",
        null, "", { BoasVindas() },
    ),
    Pagina(
        "No player", "Arraste para os lados",
        "Em qualquer ponto da tela. O quadro exato acompanha o dedo, em vez de pular de cena em cena, " +
            "e o balão mostra para onde você vai e quanto andou.",
        "arraste o dedo para a direita ou para a esquerda no vídeo",
        "É assim que se procura uma cena.",
        { DemoBusca(it) },
    ),
    Pagina(
        "No player", "Brilho à esquerda, volume à direita",
        "Arraste para cima ou para baixo. O brilho vale só para o vídeo e volta ao normal quando você " +
            "sai; o volume é o do aparelho, sem a régua do sistema tapando a imagem.",
        "suba ou desça o dedo em cada metade",
        "Os dois lados, sem sair do filme.",
        { DemoBrilhoVolume(it) },
    ),
    Pagina(
        "No player", "Toque duas vezes",
        "À direita avança 10 segundos, à esquerda volta 10. No meio, pausa e continua. Perdeu uma fala? " +
            "Dois toques à esquerda.",
        "toque duas vezes rápido à direita, à esquerda ou no meio",
        "Pulou sem nem olhar para os botões.",
        { DemoToqueDuplo(it) },
    ),
    Pagina(
        "No player", "Segure para acelerar",
        "Enquanto o dedo fica na tela, o vídeo corre a 2× — solte e ele volta ao normal. Ótimo para " +
            "atravessar uma parte parada. A velocidade do segurar se escolhe no menu de três pontos, de 1,5× a 4×.",
        "segure o dedo no vídeo por um instante",
        "Soltou, voltou ao normal.",
        { DemoSegurar(it) },
    ),
    Pagina(
        "No player", "Pinça para ampliar",
        "Afaste dois dedos para chegar perto de um detalhe, de 0,5× a 6×, e arraste com os dois para " +
            "mover a imagem ampliada. \"Ampliação normal\", no menu de três pontos, volta ao quadro inteiro.",
        "afaste dois dedos sobre o vídeo",
        "Detalhe de perto.",
        { DemoPinca(it) },
    ),
    Pagina(
        "No player", "Rolagem fina na barra",
        "Arrastando a bolinha da barra de tempo, afaste o dedo para cima: a barra passa a andar mais " +
            "devagar — metade, um quarto, um décimo. É o jeito de acertar o segundo exato num filme de " +
            "duas horas.",
        "arraste a bolinha e, sem soltar, suba o dedo",
        "Precisão de relojoeiro.",
        { DemoRolagemFina(it) },
    ),
    Pagina(
        "Ferramentas", "A ilha e o menu",
        "A ilha, logo abaixo do título, guarda o que se usa durante o filme. Os três pontos no canto de cima abrem o resto. " +
            "Toque em cada uma para saber o que faz.",
        "toque em três ferramentas",
        "Agora você conhece a caixa de ferramentas.",
        { DemoFerramentas(it) },
    ),
    Pagina(
        "Biblioteca", "Tudo do aparelho, já organizado",
        "As pastas viram capas, e \"Continuar assistindo\" guarda onde você parou em cada vídeo — ao " +
            "abrir de novo, o app pergunta se quer continuar. Baixou algo novo? Puxe a tela para baixo " +
            "para atualizar.",
        "puxe a lista para baixo e solte",
        "Biblioteca atualizada.",
        { DemoBiblioteca(it) },
    ),
    Pagina(
        "Na rede", "O computador de casa, no bolso",
        "O ícone de servidores, no alto da biblioteca, acha sozinho os computadores e NAS da sua rede " +
            "com pastas compartilhadas e toca direto de lá, sem baixar nada. No carro, o Android Auto " +
            "toca o áudio dos vídeos.\n\nAo tocar em Começar, o Android vai pedir acesso aos seus vídeos. " +
            "Nada sai do aparelho — a varredura é local. Para rever este guia: Exibição › Ver o guia.",
        "toque no ícone de servidores",
        "É por aqui que se chega ao computador de casa.",
        { DemoRede(it) },
    ),
)

// ─── As demonstrações ───────────────────────────────────────────────────────

@Composable
private fun BoasVindas() {
    val giro = rememberInfiniteTransition(label = "marca")
    val pulso by giro.animateFloat(0.85f, 1f, infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulso")
    val entrada = remember { Animatable(0.6f) }
    LaunchedEffect(Unit) { entrada.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 120f)) }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(190.dp)) {
                drawCircle(Brush.radialGradient(listOf(Ouro.copy(alpha = 0.28f * pulso), Color.Transparent)))
            }
            Image(
                painterResource(R.drawable.ic_marca), null,
                Modifier.size(116.dp).graphicsLayer { scaleX = entrada.value; scaleY = entrada.value },
            )
        }
        Text(
            buildAnnotatedString {
                append("Liberty"); withStyle(SpanStyle(color = Ouro)) { append("X") }
            },
            color = LabTheme.text, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp,
        )
        Text("Sua mídia. Sua liberdade.", color = LabTheme.muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DemoBusca(onFeito: () -> Unit) {
    val d = rememberDemo()
    val largura = with(LocalDensity.current) { 320.dp.toPx() }
    TelaDeDemo(d, Modifier.pointerInput(Unit) {
        var inicio = 0f
        var andou = 0f
        detectDragGestures(
            onDragStart = { inicio = d.tempo; andou = 0f },
            onDragEnd = { if (abs(d.tempo - inicio) > 15f) onFeito() },
        ) { ch, arrasto ->
            ch.consume()
            andou += arrasto.x
            // Uma largura de tela ≈ 3 minutos, como no player.
            d.tempo = (inicio + andou / largura * 180f).coerceIn(0f, d.duracao)
            val delta = d.tempo - inicio
            d.mostrar(
                Balao(
                    TimeFormat.clock(d.tempo.toDouble()),
                    (if (delta >= 0) "+" else "−") + TimeFormat.clock(abs(delta).toDouble()), dourada = true,
                )
            )
        }
    })
}

@Composable
private fun DemoBrilhoVolume(onFeito: () -> Unit) {
    val d = rememberDemo()
    var usouBrilho by remember { mutableStateOf(false) }
    var usouVolume by remember { mutableStateOf(false) }
    val altura = with(LocalDensity.current) { 180.dp.toPx() }
    Column {
        TelaDeDemo(d, Modifier.pointerInput(Unit) {
            var esquerda = true
            detectDragGestures(onDragStart = { esquerda = it.x < size.width / 2f }) { ch, arrasto ->
                ch.consume()
                if (esquerda) {
                    d.brilho = (d.brilho - arrasto.y / altura).coerceIn(0.1f, 1f)
                    d.mostrar(Balao("${(d.brilho * 100).roundToInt()}%", "Brilho", d.brilho))
                    usouBrilho = true
                } else {
                    d.volume = (d.volume - arrasto.y / altura).coerceIn(0f, 1f)
                    d.mostrar(Balao("${(d.volume * 100).roundToInt()}%", "Volume", d.volume))
                    usouVolume = true
                }
                if (usouBrilho && usouVolume) onFeito()
            }
        }) {
            // A divisão das metades, para o dedo saber onde está cada uma.
            Box(Modifier.fillMaxHeight().width(1.dp).align(Alignment.Center).background(Color.White.copy(alpha = 0.10f)))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Marcador("Brilho", usouBrilho, Modifier.weight(1f))
            Marcador("Volume", usouVolume, Modifier.weight(1f))
        }
    }
}

/** Uma das partes de um exercício, que acende quando foi feita. */
@Composable
private fun Marcador(texto: String, feito: Boolean, modifier: Modifier = Modifier) {
    val forma = RoundedCornerShape(12.dp)
    val cor by animateColorAsState(if (feito) LabTheme.green else LabTheme.faint, label = "marcador")
    Row(
        modifier.clip(forma).background(Color.White.copy(alpha = 0.05f)).border(1.dp, cor.copy(alpha = 0.5f), forma)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(texto, color = LabTheme.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        AnimatedVisibility(feito, enter = scaleIn() + fadeIn()) {
            Icon(Icons.Rounded.Check, null, tint = LabTheme.green, modifier = Modifier.padding(start = 6.dp).size(16.dp))
        }
    }
}

@Composable
private fun DemoToqueDuplo(onFeito: () -> Unit) {
    val d = rememberDemo()
    var serie by remember { mutableIntStateOf(0) }
    val feitos = remember { mutableStateListOf<Int>() }
    Column {
        TelaDeDemo(d, Modifier.pointerInput(Unit) {
            detectTapGestures(onDoubleTap = { ponto ->
                val terco = size.width / 3f
                val lado = when {
                    ponto.x < terco -> -1
                    ponto.x > terco * 2 -> 1
                    else -> 0
                }
                serie++
                when (lado) {
                    -1 -> { d.pular(-10f); d.halo = Halo(-1, "−10 s", serie) }
                    1 -> { d.pular(10f); d.halo = Halo(1, "+10 s", serie) }
                    else -> { d.tocando = !d.tocando; d.halo = Halo(0, if (d.tocando) "play" else "pause", serie) }
                }
                if (lado !in feitos) feitos += lado
                onFeito()
            })
        }) {
            // Os três terços, bem de leve.
            Row(Modifier.fillMaxSize()) {
                listOf("−10 s", "⏯", "+10 s").forEachIndexed { i, rotulo ->
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Text(rotulo, color = Color.White.copy(alpha = 0.22f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    if (i < 2) Box(Modifier.fillMaxHeight().width(1.dp).background(Color.White.copy(alpha = 0.07f)))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Marcador("Voltar", -1 in feitos, Modifier.weight(1f))
            Marcador("Pausar", 0 in feitos, Modifier.weight(1f))
            Marcador("Avançar", 1 in feitos, Modifier.weight(1f))
        }
    }
}

@Composable
private fun DemoSegurar(onFeito: () -> Unit) {
    val d = rememberDemo()
    TelaDeDemo(d, Modifier.pointerInput(Unit) {
        detectTapGestures(onPress = {
            coroutineScope {
                val acelera = launch {
                    delay(350)
                    d.velocidade = 2f
                    delay(900)
                    onFeito()
                }
                tryAwaitRelease()
                acelera.cancel()
                d.velocidade = 1f
            }
        })
    })
}

@Composable
private fun DemoPinca(onFeito: () -> Unit) {
    val d = rememberDemo()
    TelaDeDemo(d, Modifier.pointerInput(Unit) {
        detectTransformGestures { _, arrasto, escala, _ ->
            d.zoom = (d.zoom * escala).coerceIn(0.5f, 6f)
            d.desloc = if (d.zoom > 1f) d.desloc + arrasto else Offset.Zero
            d.mostrar(Balao("${(d.zoom * 100).roundToInt()}%", "Ampliação"))
            if (d.zoom > 1.4f || d.zoom < 0.8f) onFeito()
        }
    }) {
        // Quem já ampliou precisa de um caminho de volta aqui também.
        if (d.zoom != 1f) {
            Text(
                "Ampliação normal", color = LabTheme.text, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).clip(CircleShape).background(Vidro)
                    .clickable { d.zoom = 1f; d.desloc = Offset.Zero }.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun DemoRolagemFina(onFeito: () -> Unit) {
    val d = rememberDemo(barra = false)
    var precisao by remember { mutableFloatStateOf(1f) }
    var arrastando by remember { mutableStateOf(false) }
    val densidade = LocalDensity.current.density
    Column {
        TelaDeDemo(d, Modifier) {
            androidx.compose.animation.AnimatedVisibility(
                arrastando, Modifier.align(Alignment.TopCenter).padding(top = 14.dp), enter = fadeIn(), exit = fadeOut(),
            ) {
                BalaoDaDemo(
                    Balao(
                        TimeFormat.clock(d.tempo.toDouble()),
                        when {
                            precisao >= 1f -> "velocidade normal"
                            precisao > 0.4f -> "precisão ½ — afaste mais para afinar"
                            precisao > 0.2f -> "precisão ¼"
                            else -> "precisão ⅒"
                        },
                        dourada = precisao < 1f,
                    )
                )
            }
        }
        // A barra fica fora do quadro e com folga em cima, para o dedo ter
        // para onde subir sem sair da área do gesto.
        Box(
            Modifier.fillMaxWidth().height(200.dp).pointerInput(Unit) {
                var ultimoX = 0f
                detectDragGestures(
                    onDragStart = { ultimoX = it.x; arrastando = true; d.tocando = false },
                    onDragEnd = { arrastando = false; d.tocando = true; precisao = 1f },
                    onDragCancel = { arrastando = false; d.tocando = true; precisao = 1f },
                ) { ch, _ ->
                    ch.consume()
                    val trilho = size.height - 28 * densidade
                    val distancia = abs(ch.position.y - trilho) / densidade
                    precisao = when {
                        distancia < 50 -> 1f
                        distancia < 100 -> 0.5f
                        distancia < 150 -> 0.25f
                        else -> 0.1f
                    }
                    if (precisao < 1f) onFeito()
                    d.tempo = (d.tempo + (ch.position.x - ultimoX) / size.width * d.duracao * precisao).coerceIn(0f, d.duracao)
                    ultimoX = ch.position.x
                }
            },
        ) {
            // As faixas de precisão nas mesmas distâncias do player: 50, 100 e
            // 150 dp acima da barra. A faixa onde o dedo está acende.
            listOf(Triple(50, "½", 0.5f), Triple(100, "¼", 0.25f), Triple(150, "⅒", 0.1f)).forEach { (dist, rotulo, valor) ->
                val acesa = arrastando && precisao == valor
                Box(
                    Modifier.fillMaxWidth().offset(y = 172.dp - dist.dp).height(1.dp)
                        .background(if (acesa) Ouro.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.07f)),
                )
                Text(
                    "precisão $rotulo", fontSize = 11.sp, fontWeight = if (acesa) FontWeight.Bold else FontWeight.Normal,
                    color = if (acesa) Ouro else LabTheme.faint.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth().offset(y = 172.dp - dist.dp - 18.dp), textAlign = TextAlign.End,
                )
            }
            BoxWithConstraints(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(56.dp)) {
                val frac = d.tempo / d.duracao
                Box(
                    Modifier.align(Alignment.CenterStart).fillMaxWidth().height(4.dp).clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.2f)),
                ) { Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(Ouro)) }
                val raio = if (arrastando) 11.dp else 9.dp
                Box(
                    Modifier.align(Alignment.CenterStart).offset { IntOffset(((maxWidth - raio * 2) * frac).roundToPx(), 0) }
                        .size(raio * 2).clip(CircleShape).background(Ouro),
                )
                Text(
                    TimeFormat.clock(d.tempo.toDouble()), color = LabTheme.muted, fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.BottomStart),
                )
            }
        }
    }
}

private class Ferramenta(@DrawableRes val icone: Int, val nome: String, val explica: String)

private val daIlha = listOf(
    Ferramenta(R.drawable.ic_audio_track, "Faixa de áudio", "Troca a faixa de som: dublado, original, comentário do diretor."),
    Ferramenta(R.drawable.ic_subtitle, "Legenda", "Liga, desliga e escolhe entre as legendas do arquivo."),
    Ferramenta(R.drawable.ic_repeat, "Repetir", "Repete o vídeo atual sem parar. Acende enquanto estiver ligado."),
    Ferramenta(R.drawable.ic_rotate, "Girar", "Automático, deitado ou em pé — e o terceiro toque devolve ao automático."),
    Ferramenta(R.drawable.ic_speed, "Velocidade", "De 0,5× a 2×, para quando a fala corre demais ou de menos."),
)

private val doMenu = listOf(
    Ferramenta(R.drawable.ic_timer, "Dormir", "Tempo para dormir: pausa sozinho depois dos minutos que você escolher."),
    Ferramenta(R.drawable.ic_moon, "Noturno", "Escurece a imagem além do mínimo do sistema, para assistir no escuro."),
    Ferramenta(R.drawable.ic_camera, "Captura", "Salva o quadro que está na tela em Imagens."),
    Ferramenta(R.drawable.ic_pip, "Janela", "Janela flutuante: o vídeo segue num canto enquanto você usa outro app."),
    Ferramenta(R.drawable.ic_frame, "Quadro a quadro", "A busca exata do arrasto. Desligue para arquivos pesados pela rede."),
    Ferramenta(R.drawable.ic_lock, "Bloquear", "O cadeado da barra de baixo trava os toques — filme no bolso, criança no colo."),
    Ferramenta(R.drawable.ic_volume_off, "Mudo", "Silencia só o vídeo; um alto-falante riscado fica no alto enquanto durar."),
    Ferramenta(R.drawable.ic_aspect, "Proporção", "Ajustar, preencher ou esticar a imagem na tela."),
)

@Composable
private fun DemoFerramentas(onFeito: () -> Unit) {
    var escolhida by remember { mutableStateOf<Ferramenta?>(null) }
    val vistas = remember { mutableStateListOf<String>() }
    fun tocar(f: Ferramenta) {
        escolhida = f
        if (f.nome !in vistas) vistas += f.nome
        if (vistas.size >= 3) onFeito()
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // A ilha, igual à do player.
        Row(
            Modifier.clip(CircleShape).background(Color(0xE6141418)).border(0.5.dp, LabTheme.glassBorder, CircleShape)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            daIlha.forEach { f -> IconeDeFerramenta(f, f === escolhida, f.nome in vistas, 46.dp) { tocar(f) } }
        }
        Spacer(Modifier.height(14.dp))
        Text("NOS TRÊS PONTOS", style = LabTheme.sectionTitle, fontSize = 10.sp, color = LabTheme.faint)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            doMenu.chunked(4).forEach { linha ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    linha.forEach { f -> IconeDeFerramenta(f, f === escolhida, f.nome in vistas, 52.dp, rotulo = true) { tocar(f) } }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        val forma = RoundedCornerShape(16.dp)
        Box(
            Modifier.fillMaxWidth().heightIn(min = 88.dp).clip(forma).background(Color.White.copy(alpha = 0.05f))
                .border(0.5.dp, LabTheme.glassBorder, forma).padding(horizontal = 14.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            AnimatedContent(escolhida, label = "explicação") { f ->
                if (f == null) {
                    Text("Toque numa ferramenta para ver o que ela faz.", color = LabTheme.faint, fontSize = 13.sp)
                } else {
                    Column {
                        Text(f.nome, color = Ouro, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                        Spacer(Modifier.height(2.dp))
                        Text(f.explica, color = LabTheme.text, fontSize = 13.sp, lineHeight = 17.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun IconeDeFerramenta(
    f: Ferramenta, ativa: Boolean, vista: Boolean, tamanho: androidx.compose.ui.unit.Dp,
    rotulo: Boolean = false, onClick: () -> Unit,
) {
    val fundo by animateColorAsState(if (ativa) Ouro.copy(alpha = 0.18f) else Color.Transparent, label = "fundo")
    val tinta by animateColorAsState(if (ativa) Ouro else if (vista) Color.White else Color.White.copy(alpha = 0.75f), label = "tinta")
    Column(
        Modifier.width(if (rotulo) 72.dp else tamanho).clip(RoundedCornerShape(14.dp)).background(fundo)
            .clickable(onClick = onClick).padding(vertical = if (rotulo) 8.dp else 0.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(if (rotulo) 30.dp else tamanho), contentAlignment = Alignment.Center) {
            Image(
                painterResource(f.icone), f.nome, Modifier.size(if (rotulo) 22.dp else 20.dp),
                colorFilter = ColorFilter.tint(tinta),
            )
        }
        if (rotulo) {
            Text(f.nome, color = tinta, fontSize = 10.sp, textAlign = TextAlign.Center, lineHeight = 12.sp, maxLines = 2)
        }
    }
}

@Composable
private fun DemoBiblioteca(onFeito: () -> Unit) {
    val escopo = rememberCoroutineScope()
    val puxao = remember { Animatable(0f) }
    var atualizando by remember { mutableStateOf(false) }
    var atualizada by remember { mutableStateOf(false) }
    val limite = with(LocalDensity.current) { 70.dp.toPx() }
    val forma = RoundedCornerShape(22.dp)
    Box(
        Modifier.fillMaxWidth().height(262.dp).clip(forma).background(Color(0xFF121216))
            .border(1.dp, Color.White.copy(alpha = 0.12f), forma)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        escopo.launch {
                            if (puxao.value >= limite && !atualizando) {
                                atualizando = true
                                puxao.animateTo(limite * 0.8f)
                                delay(1100)
                                atualizando = false; atualizada = true
                                onFeito()
                            }
                            puxao.animateTo(0f, spring(dampingRatio = 0.7f))
                        }
                    },
                ) { ch, dy ->
                    ch.consume()
                    // Resistência: puxar vai ficando pesado, como na lista de verdade.
                    escopo.launch { puxao.snapTo((puxao.value + dy * 0.55f).coerceIn(0f, limite * 1.6f)) }
                }
            },
    ) {
        // O indicador que desce com o dedo.
        val prog = (puxao.value / limite).coerceIn(0f, 1f)
        Box(
            Modifier.align(Alignment.TopCenter).offset { IntOffset(0, (puxao.value * 0.6f).roundToInt() - 40.dp.roundToPx()) }
                .size(36.dp).clip(CircleShape).background(LabTheme.surface).border(0.5.dp, LabTheme.glassBorder, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (atualizando) CircularProgressIndicator(Modifier.size(18.dp), color = Ouro, strokeWidth = 2.dp)
            else CircularProgressIndicator(progress = { prog }, modifier = Modifier.size(18.dp), color = Ouro, strokeWidth = 2.dp, trackColor = Color.Transparent)
        }
        Column(
            Modifier.fillMaxSize().offset { IntOffset(0, puxao.value.roundToInt()) }.padding(16.dp),
        ) {
            Text("Biblioteca", color = LabTheme.text, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            Text(if (atualizada) "6 vídeos · 3 pastas · atualizada agora" else "5 vídeos · 3 pastas", color = if (atualizada) LabTheme.green else LabTheme.muted, fontSize = 11.sp)
            Spacer(Modifier.height(12.dp))
            Text("CONTINUAR ASSISTINDO", style = LabTheme.sectionTitle, fontSize = 9.sp, color = LabTheme.muted)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CapaDeMentira(0.3f, 0.62f, "faltam 16 min", Modifier.width(118.dp))
                CapaDeMentira(0.7f, 0.25f, "faltam 31 min", Modifier.width(118.dp))
                CapaDeMentira(0.5f, 0.8f, "faltam 4 min", Modifier.width(118.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text("PASTAS", style = LabTheme.sectionTitle, fontSize = 9.sp, color = LabTheme.muted)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Filmes", "Séries", "Download").forEach { nome ->
                    Box(
                        Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.06f)),
                        contentAlignment = Alignment.CenterStart,
                    ) { Text(nome, color = LabTheme.text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp)) }
                }
            }
        }
    }
}

@Composable
private fun CapaDeMentira(tempo: Float, progresso: Float, legenda: String, modifier: Modifier) {
    Column(modifier) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))) {
            Cena(tempo * 240f, Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.25f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progresso).background(Ouro))
            }
        }
        Text(legenda, color = Ouro, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun DemoRede(onFeito: () -> Unit) {
    var procurando by remember { mutableStateOf(false) }
    var achou by remember { mutableIntStateOf(0) }
    val escopo = rememberCoroutineScope()
    val forma = RoundedCornerShape(22.dp)
    val pulso = rememberInfiniteTransition(label = "rede")
    val onda by pulso.animateFloat(0f, 1f, infiniteRepeatable(tween(1400)), label = "onda")
    Column(
        Modifier.fillMaxWidth().clip(forma).background(Color(0xFF121216))
            .border(1.dp, Color.White.copy(alpha = 0.12f), forma).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_marca), null, Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text("Biblioteca", color = LabTheme.text, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            Box(contentAlignment = Alignment.Center) {
                if (!procurando && achou == 0) {
                    Canvas(Modifier.size(52.dp)) {
                        drawCircle(Ouro.copy(alpha = 0.35f * (1f - onda)), radius = size.minDimension / 2f * (0.6f + 0.4f * onda))
                    }
                }
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f))
                        .border(0.5.dp, if (achou == 0) Ouro else LabTheme.glassBorder, CircleShape)
                        .clickable {
                            if (procurando) return@clickable
                            escopo.launch {
                                procurando = true; achou = 0
                                delay(900); achou = 1
                                delay(600); achou = 2
                                procurando = false
                                onFeito()
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Image(painterResource(R.drawable.ic_server), "Servidores", Modifier.size(20.dp), colorFilter = ColorFilter.tint(Color.White))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(146.dp)) {
            if (!procurando && achou == 0) {
                Text(
                    "Servidores ficam aqui, no alto. →", color = LabTheme.faint, fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (procurando) CircularProgressIndicator(Modifier.size(12.dp), color = Ouro, strokeWidth = 1.5.dp)
                        else Icon(Icons.Rounded.Check, null, tint = LabTheme.green, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (procurando) "Procurando na rede…" else "Na sua rede", color = LabTheme.muted, fontSize = 12.sp)
                    }
                    listOf("Computador da sala" to "Filmes · Séries", "NAS" to "Mídia").take(achou).forEach { (nome, pastas) ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.06f))
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Image(painterResource(R.drawable.ic_server), null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(Ouro))
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(nome, color = LabTheme.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text(pastas, color = LabTheme.faint, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
        Text(
            "Exemplo ilustrativo — os nomes reais são os da sua rede.",
            color = LabTheme.faint, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp),
        )
    }
}
