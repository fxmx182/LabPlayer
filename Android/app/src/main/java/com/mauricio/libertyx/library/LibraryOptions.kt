package com.mauricio.libertyx.library

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mauricio.libertyx.core.MediaItem
import com.mauricio.libertyx.core.Prefs
import com.mauricio.libertyx.core.Textos
import com.mauricio.libertyx.R
import androidx.annotation.StringRes

enum class LibraryLayout { LIST, GRID }

enum class LibrarySort(@StringRes private val labelRes: Int) {
    TITLE(R.string.ordem_titulo), DATE(R.string.ordem_data), SIZE(R.string.ordem_tamanho), DURATION(R.string.ordem_duracao);

    val label: String get() = Textos.get(labelRes)
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
    }
}
