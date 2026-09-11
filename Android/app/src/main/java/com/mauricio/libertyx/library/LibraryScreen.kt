package com.mauricio.libertyx.library

import androidx.compose.foundation.Image
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlinx.coroutines.launch

/**
 * Tela inicial: todos os vídeos do aparelho, agrupados por pasta.
 *
 * Aqui não há a dança de autorizar pasta por pasta que o iOS obriga — o
 * MediaStore já indexou tudo, inclusive o pendrive. A tela vazia, portanto,
 * quer dizer outra coisa: ou o aparelho não tem vídeo, ou a permissão foi
 * negada. Nunca "o sistema não deixa procurar".
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

    suspend fun varrer() {
        varrendo = true
        grupos = MediaLibrary.scan(contexto)
        varrendo = false
    }

    LaunchedEffect(Unit) { varrer() }
    LaunchedEffect(grupos) { opcoes.abrirSePastaUnica(grupos) }

    Scaffold(
        containerColor = LabTheme.background,
        topBar = {
            TopAppBar(
                title = { Text("LibertyX", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    // O servidor à esquerda, separado das ações locais: são dois
                    // mundos diferentes, e misturá-los num menu só esconderia a
                    // rede — que é metade do motivo do app existir.
                    IconButton(onClick = onOpenServers) {
                        Icon(Icons.Filled.Dns, contentDescription = "Servidores")
                    }
                },
                actions = {
                    IconButton(onClick = { mostrandoOpcoes = true }) {
                        Icon(Icons.Filled.Tune, contentDescription = "Visualização")
                    }
                    IconButton(onClick = { escopo.launch { varrer() } }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Varrer de novo")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = LabTheme.background,
                    titleContentColor = LabTheme.text,
                    actionIconContentColor = LabTheme.muted,
                    navigationIconContentColor = LabTheme.muted,
                ),
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                grupos.isEmpty() && !varrendo -> Vazio(onOpenServers)
                opcoes.layout == LibraryLayout.GRID -> Grade(grupos, opcoes)
                else -> Linhas(grupos, opcoes)
            }

            if (varrendo) {
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp)
                        .clip(CircleShape).labCard(20.dp)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Procurando vídeos…", fontSize = 12.sp, color = LabTheme.muted)
                }
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
        Spacer(Modifier.height(16.dp))
        Text("Nenhum vídeo no aparelho", color = LabTheme.text, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "O LibertyX varre tudo o que está indexado, inclusive o pendrive na USB-C. " +
                "Se você sabe que há vídeos aqui, confira a permissão de arquivos nos ajustes do Android.",
            color = LabTheme.muted,
            textAlign = TextAlign.Center,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(20.dp))
        Row(
            Modifier.clip(RoundedCornerShape(12.dp)).labCard(12.dp)
                .clickable(onClick = onOpenServers)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Dns, null, tint = LabTheme.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Conectar a um servidor SMB", color = LabTheme.text, fontSize = 14.sp)
        }
    }
}

/** Lista compacta: mais itens por tela, bom para pastas com muitos vídeos. */
@Composable
private fun Linhas(grupos: List<VideoGroup>, opcoes: LibraryOptions) {
    val contexto = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        for (grupo in grupos) {
            item(key = "h:${grupo.path}") {
                Cabecalho(grupo, opcoes.estaAberta(grupo.path)) { opcoes.alternar(grupo.path) }
            }
            if (opcoes.estaAberta(grupo.path)) {
                val ordenados = opcoes.sorted(grupo.items)
                items(ordenados, key = { it.id }) { item ->
                    // A lista de reprodução segue a ordem exibida — "próxima"
                    // deve ir para o que está à frente na tela, e não para uma
                    // ordem interna que ninguém vê.
                    LinhaDeVideo(item) { Playback.start(contexto, item, ordenados) }
                }
            }
        }
    }
}

/**
 * Grade: a miniatura vira o elemento principal, com a duração sobre ela — é
 * como se reconhece um vídeo gravado pelo celular, cujo nome é só data e hora.
 */
@Composable
private fun Grade(grupos: List<VideoGroup>, opcoes: LibraryOptions) {
    val contexto = LocalContext.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 158.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        for (grupo in grupos) {
            item(key = "h:${grupo.path}", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Cabecalho(grupo, opcoes.estaAberta(grupo.path)) { opcoes.alternar(grupo.path) }
            }
            if (opcoes.estaAberta(grupo.path)) {
                val ordenados = opcoes.sorted(grupo.items)
                items(ordenados, key = { it.id }) { item ->
                    CartaoDeVideo(item) { Playback.start(contexto, item, ordenados) }
                }
            }
        }
    }
}

@Composable
private fun Cabecalho(grupo: VideoGroup, aberta: Boolean, onAlternar: () -> Unit) {
    // A seta gira em vez de trocar de desenho: o movimento diz que a mesma
    // coisa mudou de estado, e não que apareceu outro botão.
    val giro by animateFloatAsState(if (aberta) 90f else 0f, label = "seta da pasta")

    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onAlternar)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.ChevronRight, null,
            tint = if (aberta) LabTheme.accent else LabTheme.muted,
            modifier = Modifier.size(18.dp).rotate(giro),
        )
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Filled.Folder, null, tint = LabTheme.faint, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(6.dp))
        Text(grupo.name.uppercase(), style = LabTheme.sectionTitle)
        Spacer(Modifier.weight(1f))
        // A contagem numa pastilha em vez de solta: vira informação, e não um
        // número perdido na ponta da linha.
        Text(
            "${grupo.items.size}",
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = LabTheme.muted,
            modifier = Modifier.clip(CircleShape).labCard(20.dp)
                .padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun LinhaDeVideo(item: MediaItem, onClick: () -> Unit) {
    val contexto = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Miniatura(item, Modifier.width(72.dp).height(44.dp), RoundedCornerShape(6.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title, color = LabTheme.text, fontSize = 14.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Row {
                TimeFormat.size(item.fileSize)?.let {
                    Text(it, color = LabTheme.muted, fontSize = 11.sp)
                }
                // Mostrar que há retomada guardada evita a dúvida de "será que
                // ele volta de onde parei?".
                val retomada = remember(item.id) {
                    ResumeStore.get(contexto).position(item.origin.resumeKey)
                }
                if (retomada != null) {
                    Text(
                        " · parou em ${TimeFormat.clock(retomada)}",
                        color = LabTheme.accent, fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun CartaoDeVideo(item: MediaItem, onClick: () -> Unit) {
    val contexto = LocalContext.current
    Column(
        Modifier.clip(RoundedCornerShape(LabTheme.radiusCard)).labCard()
            .clickable(onClick = onClick),
    ) {
        Box {
            Miniatura(
                item,
                Modifier.fillMaxWidth().height(96.dp),
                RoundedCornerShape(topStart = LabTheme.radiusCard, topEnd = LabTheme.radiusCard),
            )
            item.duration?.let { duracao ->
                Text(
                    TimeFormat.clock(duracao),
                    color = androidx.compose.ui.graphics.Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(7.dp)
                        .clip(CircleShape)
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
        Column(Modifier.padding(10.dp)) {
            Text(
                item.title, color = LabTheme.text, fontSize = 13.sp,
                fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            val retomada = remember(item.id) {
                ResumeStore.get(contexto).position(item.origin.resumeKey)
            }
            if (retomada != null) {
                Text(
                    "parou em ${TimeFormat.clock(retomada)}",
                    color = LabTheme.accent, fontSize = 10.sp,
                )
            }
        }
    }
}

/**
 * Miniatura da lista.
 *
 * O espaço reservado tem o mesmo tamanho da imagem final para a lista não
 * pular quando as miniaturas chegam — nada mais desagradável que a linha que
 * você ia tocar se mexer no instante do toque.
 */
@Composable
private fun Miniatura(item: MediaItem, modifier: Modifier, forma: RoundedCornerShape) {
    val contexto = LocalContext.current
    val imagem by produceState<android.graphics.Bitmap?>(
        initialValue = Thumbnails.cached(item), key1 = item.id,
    ) {
        if (value == null) value = Thumbnails.load(contexto, item)
    }

    Box(
        modifier.clip(forma).background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.06f)),
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
            Icon(Icons.Filled.Movie, null, tint = LabTheme.faint, modifier = Modifier.size(20.dp))
        }
    }
}

/** Folha de opções, no espírito da do MX Player. */
@Composable
private fun OpcoesDaBiblioteca(opcoes: LibraryOptions) {
    Column(Modifier.padding(20.dp).padding(bottom = 24.dp)) {
        Text("Layout", color = LabTheme.text, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pilula("Lista", opcoes.layout == LibraryLayout.LIST, Modifier.weight(1f)) {
                opcoes.layout = LibraryLayout.LIST
            }
            Pilula("Grade", opcoes.layout == LibraryLayout.GRID, Modifier.weight(1f)) {
                opcoes.layout = LibraryLayout.GRID
            }
        }

        Spacer(Modifier.height(22.dp))
        Text("Ordenar", color = LabTheme.text, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

        Spacer(Modifier.height(24.dp))
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
    Row(
        modifier.clip(RoundedCornerShape(10.dp))
            .background(
                if (ativo) LabTheme.accent.copy(alpha = 0.18f) else LabTheme.glass,
                RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ativo) {
            Icon(Icons.Filled.Check, null, tint = LabTheme.accent, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(texto, color = if (ativo) LabTheme.accent else LabTheme.text, fontSize = 13.sp)
    }
}
