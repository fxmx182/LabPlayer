package com.mauricio.viperplayer.library

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.setValue
import com.mauricio.viperplayer.core.MediaItem
import com.mauricio.viperplayer.core.VideoGroup
import com.mauricio.viperplayer.core.Prefs

enum class LibraryLayout { LIST, GRID }

enum class LibrarySort(val label: String) {
    TITLE("Título"), DATE("Data"), SIZE("Tamanho"), DURATION("Duração")
}

/**
 * Como a biblioteca é exibida.
 *
 * Preferência do usuário, guardada entre sessões — trocar de modo a cada
 * abertura do app seria pior que não ter a opção.
 */
class LibraryOptions(context: Context) {

    private val prefs = Prefs.get(context)

    var layout by mutableStateOf(
        runCatching { LibraryLayout.valueOf(prefs.getString(K_LAYOUT, null) ?: "") }
            .getOrDefault(LibraryLayout.GRID)
    )
    var sort by mutableStateOf(
        runCatching { LibrarySort.valueOf(prefs.getString(K_SORT, null) ?: "") }
            .getOrDefault(LibrarySort.TITLE)
    )
    var ascending by mutableStateOf(prefs.getBoolean(K_ASC, true))

    /**
     * Quais pastas estão abertas.
     *
     * Guardado entre sessões de propósito: quem tem trinta pastas e assiste de
     * uma delas não quer reabri-la toda vez que volta ao app. É o mesmo
     * princípio do resto das preferências — o app lembra o que você fez.
     */
    private val abertas = mutableStateSetOf<String>().apply {
        addAll(prefs.getStringSet(K_ABERTAS, emptySet()).orEmpty())
    }

    fun estaAberta(caminho: String) = caminho in abertas

    fun alternar(caminho: String) {
        if (!abertas.remove(caminho)) abertas.add(caminho)
        prefs.edit().putStringSet(K_ABERTAS, abertas.toSet()).apply()
    }

    /**
     * Uma pasta só abre sozinha.
     *
     * Recolher tudo é o certo em quem tem muitas pastas — é o motivo da
     * mudança —, mas abrir o app numa única linha fechada, sem nada abaixo,
     * parece tela vazia e não lista recolhida.
     */
    fun abrirSePastaUnica(grupos: List<VideoGroup>) {
        if (grupos.size == 1 && abertas.isEmpty()) alternar(grupos.first().path)
    }

    fun save() {
        prefs.edit()
            .putString(K_LAYOUT, layout.name)
            .putString(K_SORT, sort.name)
            .putBoolean(K_ASC, ascending)
            .apply()
    }

    fun sorted(itens: List<MediaItem>): List<MediaItem> {
        val ordenados = when (sort) {
            LibrarySort.TITLE -> itens.sortedWith(compareBy(MediaLibrary.NATURAL) { it.title })
            LibrarySort.DATE -> itens.sortedBy { it.modifiedAt ?: 0L }
            LibrarySort.SIZE -> itens.sortedBy { it.fileSize ?: 0L }
            // Sem duração conhecida ainda, o item vai para o fim em vez de
            // embaralhar a lista.
            LibrarySort.DURATION -> itens.sortedBy { it.duration ?: Double.MAX_VALUE }
        }
        return if (ascending) ordenados else ordenados.reversed()
    }

    private companion object {
        const val K_LAYOUT = "library.layout"
        const val K_SORT = "library.sort"
        const val K_ASC = "library.ascending"
        const val K_ABERTAS = "library.abertas"
    }
}
