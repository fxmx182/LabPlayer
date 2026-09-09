package com.mauricio.viperplayer.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.mauricio.viperplayer.core.MediaItem
import com.mauricio.viperplayer.core.MediaOrigin

/**
 * A fila de reprodução, entre a biblioteca e o player.
 *
 * Um objeto em memória, e não uma lista dentro do Intent: `MediaItem` teria que
 * virar `Parcelable` só para atravessar o processo, e a fila de uma pasta cheia
 * estouraria o limite de tamanho de uma transação do Android — que falha com um
 * erro que não menciona nem a lista nem o tamanho.
 */
object Playback {

    var queue: List<MediaItem> = emptyList()
        private set

    var index: Int = 0

    fun start(context: Context, item: MediaItem, playlist: List<MediaItem>) {
        // Sem lista, o próprio vídeo é a lista — assim o resto do código não
        // precisa tratar o caso vazio em todo lugar.
        val lista = playlist.ifEmpty { listOf(item) }
        queue = lista
        index = lista.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
        context.startActivity(Intent(context, PlayerActivity::class.java))
    }

    val current: MediaItem? get() = queue.getOrNull(index)

    /** Um vídeo aberto por outro app ("abrir com"), sem lista em volta. */
    fun single(item: MediaItem) {
        queue = listOf(item)
        index = 0
    }

    /** Monta o item a partir da URI que outro app mandou. */
    fun itemFromUri(context: Context, uri: Uri): MediaItem {
        val nome = displayName(context, uri)
        val origem = when (uri.scheme?.lowercase()) {
            "file", "content" -> MediaOrigin.Local(uri)
            else -> MediaOrigin.Remote(uri)
        }
        return MediaItem(id = uri.toString(), title = nome, origin = origem)
    }

    private fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) return c.getString(i)
                }
            }
        }
        return uri.lastPathSegment ?: uri.toString()
    }
}
