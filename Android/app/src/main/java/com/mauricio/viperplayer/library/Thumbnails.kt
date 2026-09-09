package com.mauricio.viperplayer.library

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import android.util.Size
import com.mauricio.viperplayer.core.MediaItem
import com.mauricio.viperplayer.core.MediaOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Miniaturas dos vídeos.
 *
 * O caminho barato vem primeiro: para o que está no MediaStore o sistema já
 * guarda uma miniatura pronta, e pedir a ele custa um acesso a disco em vez de
 * abrir e decodificar o arquivo. Só quando ele não tem é que o
 * `MediaMetadataRetriever` entra — e aí, no máximo duas de cada vez, senão uma
 * pasta com trezentos filmes derruba o app tentando decodificar tudo junto.
 *
 * O que **não** existe aqui: miniatura de vídeo no servidor SMB. Gerar uma
 * exigiria baixar o começo do arquivo pela rede, item por item, ao abrir a
 * pasta. Quem navega no servidor vê o ícone de filme — honesto, e a lista abre
 * na hora.
 */
object Thumbnails {

    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private val limite = Semaphore(2)

    fun cached(item: MediaItem): Bitmap? = cache.get(item.id)

    suspend fun load(context: Context, item: MediaItem): Bitmap? {
        cache.get(item.id)?.let { return it }
        val origem = item.origin as? MediaOrigin.Local ?: return null

        return limite.withPermit {
            withContext(Dispatchers.IO) {
                val imagem = doSistema(context, origem) ?: doArquivo(context, origem)
                imagem?.also { cache.put(item.id, it) }
            }
        }
    }

    private fun doSistema(context: Context, origem: MediaOrigin.Local): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            context.contentResolver.loadThumbnail(origem.uri, Size(320, 180), null)
        }.getOrNull()
    }

    private fun doArquivo(context: Context, origem: MediaOrigin.Local): Bitmap? = runCatching {
        MediaMetadataRetriever().use { leitor ->
            leitor.setDataSource(context, origem.uri)
            // Um terço do caminho: o começo do arquivo costuma ser tela preta,
            // logotipo de estúdio ou barra de cor.
            val duracao = leitor.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val instante = if (duracao > 0) duracao * 1000 / 3 else 3_000_000L
            leitor.getFrameAtTime(instante, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }
    }.getOrNull()

    /** `MediaMetadataRetriever` só virou `AutoCloseable` no Android 10. */
    private inline fun <T> MediaMetadataRetriever.use(bloco: (MediaMetadataRetriever) -> T): T =
        try {
            bloco(this)
        } finally {
            runCatching { release() }
        }
}
