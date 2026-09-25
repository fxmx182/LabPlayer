package com.mauricio.libertyx.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mauricio.libertyx.BuildConfig
import com.mauricio.libertyx.R
import com.mauricio.libertyx.core.LabTheme
import com.mauricio.libertyx.core.MediaItem
import com.mauricio.libertyx.core.ResumeStore
import com.mauricio.libertyx.core.TimeFormat
import com.mauricio.libertyx.core.VideoGroup
import com.mauricio.libertyx.core.labCard
import com.mauricio.libertyx.core.resumeKey
import com.mauricio.libertyx.player.Playback
import com.mauricio.libertyx.tv.tvFocus
import kotlinx.coroutines.launch

/**
 * Tela inicial: todos os vídeos do aparelho, agrupados por pasta.
 *
 * Aqui não há a dança de autorizar pasta por pasta que o iOS obriga — o
 * MediaStore já indexou tudo, inclusive o pendrive. A tela vazia, portanto,
 * quer dizer outra coisa: ou o aparelho não tem vídeo, ou a permissão foi
 * negada. Nunca "o sistema não deixa procurar".
 *
 * Navega como uma estante, e não como uma árvore: a primeira tela mostra o
 * que você estava vendo e as pastas como capas; tocar numa pasta entra nela.
 * A versão anterior abria e fechava as pastas ali mesmo, numa lista só — com
 * três pastas é prático, com trinta vira uma parede de títulos em caixa alta
 * onde nada se destaca e tudo se parece.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onOpenServers: () -> Unit) {
    val contexto = LocalContext.current
    val escopo = rememberCoroutineScope()
    val opcoes = remember { LibraryOptions(contexto) }

    var grupos by remember { mutableStateOf<List<VideoGroup>>(emptyList()) }
    var varrendo by remember { mutableStateOf(true) }
    var mostrandoOpcoes by remember { mutableStateOf(false) }
    var caminhoAberto by rememberSaveable { mutableStateOf<String?>(null) }
    val volta = rememberVoltaAoApp()

    suspend fun varrer() {
        varrendo = true
        grupos = MediaLibrary.scan(contexto)
        varrendo = false
    }

    LaunchedEffect(Unit) { varrer() }

    // Uma pasta só não vira estante de uma capa: os vídeos dela já são a
    // biblioteca, e entrar nela seria um toque a mais para nada.
    val pastaUnica = grupos.singleOrNull()
    val aberta = pastaUnica ?: grupos.firstOrNull { it.path == caminhoAberto }

    BackHandler(enabled = caminhoAberto != null && pastaUnica == null) { caminhoAberto = null }

    // O que se estava vendo por último, para o fundo e para a faixa de cima.
    val continuar = remember(grupos, volta) {
        val loja = ResumeStore.get(contexto)
        val porChave = grupos.flatMap { it.items }.associateBy { it.origin.resumeKey }
        loja.recent().mapNotNull { porChave[it] }.take(12)
    }

    Box(Modifier.fillMaxSize()) {
        FundoDeCapa(if (aberta != null) capaDa(aberta) else continuar.firstOrNull() ?: grupos.firstOrNull()?.let(::capaDa))

        AnimatedContent(
            targetState = aberta,
            transitionSpec = {
                // Entrar desliza da direita, sair volta para ela — o mesmo
                // sentido do resto do Android, para o dedo saber onde está.
                val entrando = targetState != null && pastaUnica == null
                val dir = if (entrando) 1 else -1
                (slideInHorizontally(tween(320)) { it / 5 * dir } + fadeIn(tween(320)))
                    .togetherWith(slideOutHorizontally(tween(260)) { -it / 5 * dir } + fadeOut(tween(200)))
            },
            contentKey = { it?.path },
            label = "estante e pasta",
        ) { pasta ->
            when {
                grupos.isEmpty() && !varrendo -> Vazio(onOpenServers)
                grupos.isEmpty() -> Box(Modifier.fillMaxSize())
                pasta == null -> Estante(
                    grupos = grupos,
                    continuar = continuar,
                    volta = volta,
                    onAbrir = { caminhoAberto = it.path },
                    onServidores = onOpenServers,
                    onOpcoes = { mostrandoOpcoes = true },
                    onVarrer = { escopo.launch { varrer() } },
                )
                else -> Pasta(
                    grupo = pasta,
                    opcoes = opcoes,
                    volta = volta,
                    raiz = pastaUnica != null,
                    continuar = continuar,
                    onVoltar = { caminhoAberto = null },
                    onServidores = onOpenServers,
                    onOpcoes = { mostrandoOpcoes = true },
                    onVarrer = { escopo.launch { varrer() } },
                )
            }
        }

        if (varrendo) {
            Row(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 18.dp)
                    .clip(CircleShape).background(LabTheme.surface.copy(alpha = 0.92f))
                    .border(0.5.dp, LabTheme.glassBorder, CircleShape)
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = LabTheme.accent)
                Spacer(Modifier.width(10.dp))
                Text("Procurando vídeos…", fontSize = 12.sp, color = LabTheme.muted)
            }
        }
    }

    if (mostrandoOpcoes) {
        ModalBottomSheet(
            onDismissRequest = { mostrandoOpcoes = false; opcoes.save() },
            containerColor = LabTheme.surface,
        ) {
            OpcoesDaBiblioteca(opcoes)
        }
    }
}

/**
 * Conta as voltas ao app — do player, principalmente.
 *
 * A marca de onde parou muda enquanto o filme toca; sem reler ao voltar, a
 * faixa de "continuar" mostraria o ponto de antes do filme.
 */
@Composable
private fun rememberVoltaAoApp(): Int {
    val dono = LocalLifecycleOwner.current
    var voltas by remember { mutableIntStateOf(0) }
    DisposableEffect(dono) {
        val observador = LifecycleEventObserver { _, evento ->
            if (evento == Lifecycle.Event.ON_RESUME) voltas++
        }
        dono.lifecycle.addObserver(observador)
        onDispose { dono.lifecycle.removeObserver(observador) }
    }
    return voltas
}

/** A capa de uma pasta: o vídeo mais novo dela, que é o que se reconhece. */
private fun capaDa(grupo: VideoGroup): MediaItem? =
    grupo.items.maxByOrNull { it.modifiedAt ?: 0L } ?: grupo.items.firstOrNull()

private fun resumoDa(grupo: VideoGroup): String {
    val n = grupo.items.size
    val tamanho = TimeFormat.size(grupo.items.sumOf { it.fileSize ?: 0L })
    return listOfNotNull(if (n == 1) "1 vídeo" else "$n vídeos", tamanho).joinToString("  ·  ")
}

/** Margens de fora a fora: a área segura do aparelho mais o respiro da tela. */
@Composable
private fun margens(): PaddingValues {
    val seguro = WindowInsets.safeDrawing.asPaddingValues()
    val direcao = LocalLayoutDirection.current
    return PaddingValues(
        start = seguro.calculateStartPadding(direcao) + 20.dp,
        end = seguro.calculateEndPadding(direcao) + 20.dp,
        top = seguro.calculateTopPadding() + 8.dp,
        bottom = seguro.calculateBottomPadding() + 72.dp,
    )
}

private fun LazyGridScope.inteiro(key: String, conteudo: @Composable () -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { conteudo() }

// ─── A estante ──────────────────────────────────────────────────────────────

@Composable
private fun Estante(
    grupos: List<VideoGroup>,
    continuar: List<MediaItem>,
    volta: Int,
    onAbrir: (VideoGroup) -> Unit,
    onServidores: () -> Unit,
    onOpcoes: () -> Unit,
    onVarrer: () -> Unit,
) {
    val contexto = LocalContext.current
    val total = grupos.sumOf { it.items.size }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = margens(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        inteiro("barra") {
            BarraDoTopo(onVoltar = null, onServidores = onServidores, onOpcoes = onOpcoes, onVarrer = onVarrer)
        }
        inteiro("titulo") {
            Titulo("Biblioteca", "$total vídeos  ·  ${grupos.size} pastas")
        }

        if (continuar.isNotEmpty()) {
            inteiro("continuar") {
                Column {
                    Secao("Continuar assistindo")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(continuar, key = { it.id }) { item ->
                            CartaoDeContinuar(item, volta) { Playback.start(contexto, item, continuar) }
                        }
                    }
                }
            }
        }

        inteiro("pastas") { Secao("Pastas", grupos.size.toString()) }
        items(grupos, key = { it.path }) { grupo ->
            CapaDePasta(grupo) { onAbrir(grupo) }
        }

    }
}

@Composable
private fun BarraDoTopo(
    onVoltar: (() -> Unit)?,
    onServidores: () -> Unit,
    onOpcoes: () -> Unit,
    onVarrer: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onVoltar != null) {
            BotaoRedondo(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", onVoltar)
        } else {
            Marca()
        }
        Spacer(Modifier.weight(1f))
        // Dentro da pasta, só o que age sobre ela: rede e nova varredura são
        // assunto da estante, e repeti-los aqui só disputaria atenção.
        if (onVoltar == null) {
            BotaoRedondo(Icons.Filled.Dns, "Servidores", onServidores)
            Spacer(Modifier.width(10.dp))
        }
        BotaoRedondo(Icons.Filled.Tune, "Exibição", onOpcoes)
        if (onVoltar == null) {
            Spacer(Modifier.width(10.dp))
            BotaoRedondo(Icons.Filled.Refresh, "Procurar de novo", onVarrer)
        }
    }
}

/** O símbolo e o nome, com o X na cor da marca — o app se apresenta. */
@Composable
private fun Marca() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // O desenho ocupa só o miolo da imagem do ícone (a borda é a área de
        // corte do Android); ampliar e recortar deixa só o símbolo.
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
            Image(
                painterResource(R.drawable.ic_launcher_foreground), null,
                modifier = Modifier.requiredSize(52.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            buildAnnotatedString {
                append("Liberty")
                withStyle(SpanStyle(color = LabTheme.accent)) { append("X") }
            },
            color = LabTheme.text, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.3).sp,
        )
    }
}

@Composable
private fun BotaoRedondo(icone: ImageVector, descricao: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).tvFocus(20.dp).clip(CircleShape)
            .background(Color.White.copy(alpha = 0.08f))
            .border(0.5.dp, LabTheme.glassBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icone, descricao, tint = LabTheme.text, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun Titulo(titulo: String, subtitulo: String) {
    Column(Modifier.padding(top = 10.dp, bottom = 2.dp)) {
        Text(titulo, style = LabTheme.headline, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(subtitulo, color = LabTheme.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Secao(titulo: String, extra: String? = null) {
    Row(Modifier.padding(top = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        // Um traço dourado antes do título: marca a seção sem precisar de
        // linha divisória atravessando a tela.
        Box(Modifier.size(width = 3.dp, height = 14.dp).clip(CircleShape).background(LabTheme.accent))
        Spacer(Modifier.width(9.dp))
        Text(titulo.uppercase(), style = LabTheme.sectionTitle.copy(color = LabTheme.text.copy(alpha = 0.8f)))
        if (extra != null) {
            Spacer(Modifier.width(8.dp))
            Text(extra, style = LabTheme.sectionTitle)
        }
    }
}

/**
 * A pasta como capa, com duas folhas atrás.
 *
 * As folhas são o que diz "pasta" sem ícone de pasta: a imagem sozinha seria
 * confundida com um vídeo, e o ícone amarelo do sistema é justamente o que
 * deixava a tela com cara de gerenciador de arquivos.
 */
@Composable
private fun CapaDePasta(grupo: VideoGroup, onClick: () -> Unit) {
    val capa = remember(grupo) { capaDa(grupo) }
    Column(Modifier.tvFocus(LabTheme.radiusCard).clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().padding(top = 10.dp).drawBehind {
                val folha = Color.White
                drawRoundRect(
                    folha.copy(alpha = 0.05f),
                    topLeft = Offset(size.width * 0.12f, -10.dp.toPx()),
                    size = Size(size.width * 0.76f, 20.dp.toPx()),
                    cornerRadius = CornerRadius(12.dp.toPx()),
                )
                drawRoundRect(
                    folha.copy(alpha = 0.10f),
                    topLeft = Offset(size.width * 0.06f, -5.dp.toPx()),
                    size = Size(size.width * 0.88f, 20.dp.toPx()),
                    cornerRadius = CornerRadius(14.dp.toPx()),
                )
            },
        ) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(1.3f)
                    .clip(RoundedCornerShape(LabTheme.radiusCard))
                    .border(0.5.dp, LabTheme.glassBorder, RoundedCornerShape(LabTheme.radiusCard)),
            ) {
                ImagemDe(capa, Modifier.fillMaxSize())
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0.3f to Color.Transparent,
                            0.75f to Color.Black.copy(alpha = 0.7f),
                            1f to Color.Black.copy(alpha = 0.92f),
                        )
                    )
                )
                Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                    Text(
                        grupo.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (grupo.items.size == 1) "1 vídeo" else "${grupo.items.size} vídeos",
                        color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun CartaoDaRede(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().tvFocus(LabTheme.radiusCard, scale = 1.02f)
            .clip(RoundedCornerShape(LabTheme.radiusCard)).labCard()
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                .background(LabTheme.accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Dns, null, tint = LabTheme.accent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Servidores SMB", color = LabTheme.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                "Os vídeos do computador ou do NAS de casa",
                color = LabTheme.muted, fontSize = 12.sp,
            )
        }
        Icon(Icons.Filled.ChevronRight, null, tint = LabTheme.faint)
    }
}

/** Continuar: a miniatura larga, a barra do quanto já foi, e quanto falta. */
@Composable
private fun CartaoDeContinuar(item: MediaItem, volta: Int, onClick: () -> Unit) {
    val contexto = LocalContext.current
    val loja = ResumeStore.get(contexto)
    val progresso = remember(item.id, volta) { loja.progress(item.origin.resumeKey) }
    val falta = remember(item.id, volta) { loja.remaining(item.origin.resumeKey) }

    Column(Modifier.width(236.dp).tvFocus(14.dp).clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp))
                .border(0.5.dp, LabTheme.glassBorder, RoundedCornerShape(14.dp)),
        ) {
            ImagemDe(item, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.18f)))
            Box(
                Modifier.align(Alignment.Center).size(44.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .border(1.dp, Color.White.copy(alpha = 0.6f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(26.dp))
            }
            BarraDeProgresso(progresso)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            item.title, color = LabTheme.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (falta != null) {
            Text("faltam ${TimeFormat.spoken(falta)}", color = LabTheme.muted, fontSize = 11.sp)
        }
    }
}

// ─── Dentro da pasta ────────────────────────────────────────────────────────

@Composable
private fun Pasta(
    grupo: VideoGroup,
    opcoes: LibraryOptions,
    volta: Int,
    raiz: Boolean,
    continuar: List<MediaItem>,
    onVoltar: () -> Unit,
    onServidores: () -> Unit,
    onOpcoes: () -> Unit,
    onVarrer: () -> Unit,
) {
    val contexto = LocalContext.current
    // A lista de reprodução segue a ordem exibida — "próxima" deve ir para o
    // que está à frente na tela, e não para uma ordem interna que ninguém vê.
    val ordenados = opcoes.sorted(grupo.items)
    val emGrade = opcoes.layout == LibraryLayout.GRID

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = margens(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(if (emGrade) 18.dp else 4.dp),
    ) {
        inteiro("barra") {
            BarraDoTopo(
                onVoltar = if (raiz) null else onVoltar,
                onServidores = onServidores, onOpcoes = onOpcoes, onVarrer = onVarrer,
            )
        }
        inteiro("titulo") {
            Box(Modifier.padding(bottom = if (emGrade) 0.dp else 10.dp)) {
                Titulo(if (raiz) "Biblioteca" else grupo.name, resumoDa(grupo))
            }
        }
        if (raiz && continuar.isNotEmpty()) {
            inteiro("continuar") {
                Column {
                    Secao("Continuar assistindo")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(continuar, key = { it.id }) { item ->
                            CartaoDeContinuar(item, volta) { Playback.start(contexto, item, continuar) }
                        }
                    }
                }
            }
            inteiro("secao") { Secao(grupo.name) }
        }
        if (emGrade) {
            items(ordenados, key = { it.id }) { item ->
                CartaoDeVideo(item, volta) { Playback.start(contexto, item, ordenados) }
            }
        } else {
            items(ordenados, key = { it.id }, span = { GridItemSpan(maxLineSpan) }) { item ->
                LinhaDeVideo(item, volta) { Playback.start(contexto, item, ordenados) }
            }
        }
    }
}

// ─── Peças compartilhadas com a pasta do servidor ───────────────────────────

/**
 * Grade: a miniatura vira o elemento principal, com a duração sobre ela — é
 * como se reconhece um vídeo gravado pelo celular, cujo nome é só data e hora.
 * Sem caixa em volta: o título solto embaixo da imagem, como numa locadora,
 * deixa a tela respirar mais que um cartão dentro de outro.
 */
@Composable
internal fun CartaoDeVideo(item: MediaItem, volta: Int = 0, onClick: () -> Unit) {
    val contexto = LocalContext.current
    val progresso = remember(item.id, volta) {
        ResumeStore.get(contexto).progress(item.origin.resumeKey)
    }
    Column(Modifier.tvFocus(14.dp).clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(14.dp))
                .border(0.5.dp, LabTheme.glassBorder, RoundedCornerShape(14.dp)),
        ) {
            ImagemDe(item, Modifier.fillMaxSize())
            Duracao(item, Modifier.align(Alignment.BottomEnd).padding(bottom = if (progresso != null) 9.dp else 7.dp, end = 7.dp))
            BarraDeProgresso(progresso)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            item.title, color = LabTheme.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 17.sp,
        )
        Spacer(Modifier.height(2.dp))
        Detalhes(item, progresso != null, volta)
    }
}

/** Lista: mais itens por tela, bom para pastas com muitos vídeos. */
@Composable
internal fun LinhaDeVideo(item: MediaItem, volta: Int = 0, onClick: () -> Unit) {
    val contexto = LocalContext.current
    val progresso = remember(item.id, volta) {
        ResumeStore.get(contexto).progress(item.origin.resumeKey)
    }
    Row(
        Modifier.fillMaxWidth().tvFocus(LabTheme.radiusSmall, scale = 1.02f)
            .clip(RoundedCornerShape(LabTheme.radiusSmall))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(124.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))
                .border(0.5.dp, LabTheme.glassBorder, RoundedCornerShape(10.dp)),
        ) {
            ImagemDe(item, Modifier.fillMaxSize())
            Duracao(item, Modifier.align(Alignment.BottomEnd).padding(5.dp))
            BarraDeProgresso(progresso)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title, color = LabTheme.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp,
            )
            Spacer(Modifier.height(3.dp))
            Detalhes(item, progresso != null, volta)
        }
    }
}

/**
 * A linha de baixo do título. Quem parou no meio quer saber quanto falta; quem
 * não começou quer saber o tamanho e de quando é.
 */
@Composable
private fun Detalhes(item: MediaItem, comRetomada: Boolean, volta: Int) {
    val contexto = LocalContext.current
    if (comRetomada) {
        val falta = remember(item.id, volta) {
            ResumeStore.get(contexto).remaining(item.origin.resumeKey)
        }
        Text(
            if (falta != null) "faltam ${TimeFormat.spoken(falta)}" else "em andamento",
            color = LabTheme.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        )
    } else {
        val texto = listOfNotNull(TimeFormat.size(item.fileSize), TimeFormat.date(item.modifiedAt))
            .joinToString("  ·  ")
        if (texto.isNotEmpty()) Text(texto, color = LabTheme.muted, fontSize = 11.sp)
    }
}

@Composable
private fun Duracao(item: MediaItem, modifier: Modifier) {
    val contexto = LocalContext.current
    // No servidor a duração só se descobre ao tirar a miniatura, e aparece
    // aqui assim que ela fica pronta.
    val duracao = item.duration ?: Thumbnails.duracao(contexto, item) ?: return
    Text(
        TimeFormat.clock(duracao),
        color = Color.White,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier.clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.62f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** A régua do quanto já foi visto, rente à base da miniatura. */
@Composable
private fun BoxScope.BarraDeProgresso(progresso: Float?) {
    if (progresso == null) return
    Box(
        Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp)
            .background(Color.White.copy(alpha = 0.25f))
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progresso.coerceAtLeast(0.03f)).background(LabTheme.accent))
    }
}

/**
 * A miniatura.
 *
 * O espaço reservado tem o mesmo tamanho da imagem final para a lista não
 * pular quando as miniaturas chegam — nada mais desagradável que a linha que
 * você ia tocar se mexer no instante do toque.
 */
@Composable
private fun ImagemDe(item: MediaItem?, modifier: Modifier) {
    val contexto = LocalContext.current
    val imagem by produceState(
        initialValue = item?.let { Thumbnails.cached(it) }, key1 = item?.id,
    ) {
        if (value == null && item != null) value = Thumbnails.load(contexto, item)
    }

    Box(
        modifier.background(
            Brush.linearGradient(listOf(Color(0xFF232228), Color(0xFF16161A)))
        ),
        contentAlignment = Alignment.Center,
    ) {
        val pronta = imagem
        if (pronta != null) {
            Image(
                bitmap = pronta.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(Icons.Filled.Movie, null, tint = LabTheme.faint, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun Vazio(onOpenServers: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // A marca no lugar do ícone genérico: é a primeira tela de quem acabou
        // de instalar, e a única chance de o app se apresentar.
        Image(
            painter = painterResource(R.drawable.logo),
            contentDescription = null,
            modifier = Modifier.width(220.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text("Nenhum vídeo no aparelho", color = LabTheme.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "O LibertyX varre tudo o que está indexado, inclusive o pendrive na USB-C. " +
                "Se você sabe que há vídeos aqui, confira a permissão de arquivos nos ajustes do Android.",
            color = LabTheme.muted,
            textAlign = TextAlign.Center,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(24.dp))
        CartaoDaRede(onOpenServers)
    }
}

/** Folha de opções, no espírito da do MX Player. */
@Composable
internal fun OpcoesDaBiblioteca(opcoes: LibraryOptions) {
    Column(Modifier.navigationBarsPadding().padding(horizontal = 22.dp).padding(top = 4.dp, bottom = 20.dp)) {
        Text("Exibição", color = LabTheme.text, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(18.dp))

        Secao("Layout")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pilula("Grade", opcoes.layout == LibraryLayout.GRID, Modifier.weight(1f)) {
                opcoes.layout = LibraryLayout.GRID
            }
            Pilula("Lista", opcoes.layout == LibraryLayout.LIST, Modifier.weight(1f)) {
                opcoes.layout = LibraryLayout.LIST
            }
        }

        Spacer(Modifier.height(20.dp))
        Secao("Ordenar por")
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LibrarySort.entries.chunked(2).forEach { linha ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    linha.forEach { opcao ->
                        Pilula(opcao.label, opcoes.sort == opcao, Modifier.weight(1f)) {
                            opcoes.sort = opcao
                        }
                    }
                    if (linha.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pilula("Crescente", opcoes.ascending, Modifier.weight(1f)) { opcoes.ascending = true }
            Pilula("Decrescente", !opcoes.ascending, Modifier.weight(1f)) { opcoes.ascending = false }
        }

        Spacer(Modifier.height(26.dp))
        // Qual build está instalado, à mão.
        //
        // Sem isto, "a mudança não funcionou" e "instalei o APK antigo" são a
        // mesma tela — e levam a investigações opostas.
        Text(
            "LibertyX Player ${BuildConfig.VERSION_NAME} · ${BuildConfig.COMMIT}",
            color = LabTheme.faint,
            fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Pilula(texto: String, ativo: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val forma = RoundedCornerShape(14.dp)
    Row(
        modifier.tvFocus(14.dp, scale = 1.03f).clip(forma)
            .background(if (ativo) LabTheme.accent.copy(alpha = 0.16f) else LabTheme.glass, forma)
            .border(if (ativo) 1.dp else 0.5.dp, if (ativo) LabTheme.accent.copy(alpha = 0.7f) else LabTheme.glassBorder, forma)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ativo) {
            Icon(Icons.Filled.Check, null, tint = LabTheme.accent, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            texto, color = if (ativo) LabTheme.accent else LabTheme.text, fontSize = 13.sp,
            fontWeight = if (ativo) FontWeight.Bold else FontWeight.Medium,
        )
    }
}
