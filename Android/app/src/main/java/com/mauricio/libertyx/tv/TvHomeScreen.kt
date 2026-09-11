package com.mauricio.libertyx.tv

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mauricio.libertyx.core.LabTheme
import com.mauricio.libertyx.core.MediaItem
import com.mauricio.libertyx.core.ResumeStore
import com.mauricio.libertyx.core.TimeFormat
import com.mauricio.libertyx.core.VideoGroup
import com.mauricio.libertyx.core.resumeKey
import com.mauricio.libertyx.library.MediaLibrary
import com.mauricio.libertyx.library.Thumbnails
import com.mauricio.libertyx.player.Playback
import com.mauricio.libertyx.smb.SmbServer
import com.mauricio.libertyx.smb.SmbServerStore

/**
 * A tela inicial na televisão.
 *
 * Três diferenças que não são de estilo, e sim de uso:
 *
 * 1. **O servidor vem primeiro.** Num celular a biblioteca local é o que
 *    importa; numa caixinha de TV quase não existe vídeo local — o acervo mora
 *    no servidor de casa. Enterrar isso atrás de um ícone de canto seria
 *    esconder justamente o que a pessoa ligou a TV para ver.
 * 2. **Faixas na horizontal**, e não grade. É como todo aparelho de TV
 *    organiza conteúdo, e é o que o controle remoto sabe percorrer: esquerda e
 *    direita dentro de um assunto, cima e baixo entre assuntos.
 * 3. **Margem de segurança nas bordas.** Muita TV ainda corta uns 5% da
 *    imagem; texto encostado na borda simplesmente não aparece.
 */
@Composable
fun TvHomeScreen(onOpenServers: () -> Unit) {
    val contexto = LocalContext.current
    val servidores = remember { SmbServerStore.get(contexto).servers }
    val primeiroFoco = remember { FocusRequester() }

    val grupos by produceState<List<VideoGroup>?>(initialValue = null) {
        value = MediaLibrary.scan(contexto)
    }

    // Sem isto o controle remoto começa sem foco em lugar nenhum: a primeira
    // seta não move nada e parece que a TV travou.
    LaunchedEffect(Unit) { runCatching { primeiroFoco.requestFocus() } }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(LabTheme.background),
        contentPadding = PaddingValues(vertical = OVERSCAN_V),
        verticalArrangement = Arrangement.spacedBy(34.dp),
    ) {
        item { Cabecalho() }

        item {
            Faixa("Servidores") {
                items(servidores, key = { it.id }) { servidor ->
                    CartaoServidor(servidor, Modifier) { onOpenServers() }
                }
                item {
                    CartaoAcao(
                        titulo = if (servidores.isEmpty()) "Conectar ao servidor de casa" else "Gerenciar servidores",
                        modifier = if (servidores.isEmpty()) Modifier.focusRequester(primeiroFoco) else Modifier,
                        onClick = onOpenServers,
                    )
                }
            }
        }

        val locais = grupos
        if (locais != null) {
            items(locais, key = { it.path }) { grupo ->
                Faixa(grupo.name.uppercase()) {
                    val ordenados = grupo.items
                    items(ordenados, key = { it.id }) { item ->
                        CartaoVideo(
                            item = item,
                            modifier = if (item.id == ordenados.first().id && servidores.isNotEmpty())
                                Modifier.focusRequester(primeiroFoco) else Modifier,
                        ) { Playback.start(contexto, item, ordenados) }
                    }
                }
            }
            if (locais.isEmpty()) {
                item { Vazio() }
            }
        }
    }
}

private val OVERSCAN_H = 48.dp
private val OVERSCAN_V = 27.dp

@Composable
private fun Cabecalho() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = OVERSCAN_H, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "LIBERTYX",
            color = LabTheme.accent,
            fontSize = 30.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 3.sp,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            "PLAYER",
            color = LabTheme.faint,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 5.sp,
        )
    }
}

/** Uma faixa: título à esquerda e os cartões correndo para a direita. */
@Composable
private fun Faixa(titulo: String, conteudo: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column {
        Text(
            titulo,
            color = LabTheme.faint,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.6.sp,
            modifier = Modifier.padding(start = OVERSCAN_H, bottom = 12.dp),
        )
        LazyRow(
            // O recuo grande à esquerda e à direita deixa o cartão focado
            // crescer sem encostar na borda da tela.
            contentPadding = PaddingValues(horizontal = OVERSCAN_H),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            conteudo()
        }
    }
}

private val CARD_W = 232.dp
private val CARD_H = 130.dp

@Composable
private fun CartaoVideo(item: MediaItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val contexto = LocalContext.current
    val imagem by produceState<android.graphics.Bitmap?>(
        initialValue = Thumbnails.cached(item), key1 = item.id,
    ) {
        if (value == null) value = Thumbnails.load(contexto, item)
    }

    Column(modifier.width(CARD_W)) {
        Box(
            Modifier
                .width(CARD_W).height(CARD_H)
                .tvFocus()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .clickable(onClick = onClick),
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
                Icon(Icons.Filled.Movie, null, tint = LabTheme.faint, modifier = Modifier.size(30.dp))
            }

            item.duration?.let { duracao ->
                Text(
                    TimeFormat.clock(duracao),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black.copy(alpha = 0.72f))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
        }

        Spacer(Modifier.height(9.dp))
        Text(
            item.title,
            color = LabTheme.text,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val retomada = remember(item.id) {
            ResumeStore.get(contexto).position(item.origin.resumeKey)
        }
        Text(
            if (retomada != null) "parou em ${TimeFormat.clock(retomada)}" else " ",
            color = LabTheme.accent,
            fontSize = 12.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun CartaoServidor(servidor: SmbServer, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.width(CARD_W)) {
        Box(
            Modifier
                .width(CARD_W).height(CARD_H)
                .tvFocus()
                .clip(RoundedCornerShape(14.dp))
                .background(LabTheme.glass)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Dns, null, tint = LabTheme.accent, modifier = Modifier.size(38.dp))
        }
        Spacer(Modifier.height(9.dp))
        Text(servidor.name, color = LabTheme.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(servidor.displayHost, color = LabTheme.muted, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun CartaoAcao(titulo: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.width(CARD_W)) {
        Box(
            Modifier
                .width(CARD_W).height(CARD_H)
                .tvFocus()
                .clip(RoundedCornerShape(14.dp))
                .background(LabTheme.glass)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Add, null, tint = LabTheme.accent, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(9.dp))
        Text(titulo, color = LabTheme.text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Vazio() {
    Column(Modifier.fillMaxWidth().padding(horizontal = OVERSCAN_H, vertical = 20.dp)) {
        Text("Nenhum vídeo neste aparelho", color = LabTheme.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Numa caixinha de TV isso é o normal — o acervo mora no servidor. " +
                "Conecte o servidor de casa acima e ele aparece aqui.",
            color = LabTheme.muted,
            fontSize = 14.sp,
        )
    }
}
