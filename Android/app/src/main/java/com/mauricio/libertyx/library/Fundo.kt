package com.mauricio.libertyx.library

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.mauricio.libertyx.core.LabTheme
import com.mauricio.libertyx.core.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * O fundo das telas de navegação: a capa do que se está vendo, desfocada.
 *
 * Um fundo preto liso deixa a tela parecendo planilha; uma imagem fixa
 * qualquer vira papel de parede e envelhece na primeira semana. A capa do
 * próprio acervo muda com ele — na biblioteca é o vídeo que você estava
 * assistindo, dentro da pasta é a capa da pasta —, então o fundo diz onde você
 * está sem uma palavra. É o que o app da Apple TV e o Plex fazem, e por isso
 * parece "de cinema" sem ninguém saber explicar por quê.
 *
 * Desfocada por conta própria, e não com `Modifier.blur`: esse só existe do
 * Android 12 em diante, e o app vai até o 7. Reduzir a imagem a uma tira de
 * poucos pixels, borrar ali e deixar a placa de vídeo ampliar dá o mesmo
 * resultado em qualquer aparelho — e custa quase nada.
 */
@Composable
fun FundoDeCapa(capa: MediaItem?, modifier: Modifier = Modifier) {
    val contexto = LocalContext.current

    // Guarda a última imagem em vez de voltar a nada enquanto a próxima
    // carrega: sem isso o fundo piscaria preto a cada troca de pasta.
    var imagem by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(capa?.id) {
        if (capa == null) {
            imagem = null
            return@LaunchedEffect
        }
        desfocadas[capa.id]?.let { imagem = it; return@LaunchedEffect }
        val original = Thumbnails.load(contexto, capa) ?: return@LaunchedEffect
        imagem = withContext(Dispatchers.Default) { desfocar(original) }
            .also { desfocadas[capa.id] = it }
    }

    Box(modifier.fillMaxSize().background(LabTheme.background)) {
        Crossfade(imagem, animationSpec = tween(700), label = "capa do fundo") { atual ->
            if (atual != null) {
                Image(
                    bitmap = atual,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(0.6f).alpha(0.55f),
                )
            }
        }

        // A capa se dissolve no fundo antes do meio da tela: é ambiente, e
        // não pode disputar a leitura com as miniaturas por cima dela.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to LabTheme.background.copy(alpha = 0.30f),
                    0.28f to LabTheme.background.copy(alpha = 0.62f),
                    0.58f to LabTheme.background,
                )
            )
        )

        // Um halo dourado vindo do canto, da cor da marca. Sem capa, é ele
        // que impede o fundo de ser só preto.
        Box(
            Modifier.fillMaxSize().drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(LabTheme.accent.copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(size.width * 0.05f, -size.height * 0.02f),
                        radius = size.width * 1.05f,
                    )
                )
            }
        )
    }
}

private val desfocadas = ConcurrentHashMap<String, ImageBitmap>()

/** Três passadas de média numa imagem de 48 pixels de largura: vira bruma. */
private fun desfocar(original: Bitmap): ImageBitmap {
    val largura = 48
    val altura = (largura * original.height / original.width.coerceAtLeast(1)).coerceIn(12, 96)
    val pequena = Bitmap.createScaledBitmap(original, largura, altura, true)
    val px = IntArray(largura * altura)
    pequena.getPixels(px, 0, largura, 0, 0, largura, altura)
    repeat(3) {
        caixa(px, largura, altura, raio = 3, horizontal = true)
        caixa(px, largura, altura, raio = 3, horizontal = false)
    }
    val saida = Bitmap.createBitmap(largura, altura, Bitmap.Config.ARGB_8888)
    saida.setPixels(px, 0, largura, 0, 0, largura, altura)
    return saida.asImageBitmap()
}

private fun caixa(px: IntArray, w: Int, h: Int, raio: Int, horizontal: Boolean) {
    val linhas = if (horizontal) h else w
    val tamanho = if (horizontal) w else h
    val tmp = IntArray(tamanho)
    for (l in 0 until linhas) {
        for (i in 0 until tamanho) {
            var r = 0; var g = 0; var b = 0; var n = 0
            for (k in -raio..raio) {
                val j = (i + k).coerceIn(0, tamanho - 1)
                val c = px[if (horizontal) l * w + j else j * w + l]
                r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
            }
            tmp[i] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
        for (i in 0 until tamanho) px[if (horizontal) l * w + i else i * w + l] = tmp[i]
    }
}
