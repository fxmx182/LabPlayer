package com.mauricio.libertyx.library

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.mauricio.libertyx.core.MediaItem
import com.mauricio.libertyx.core.MediaOrigin
import com.mauricio.libertyx.core.VideoGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Varre os vídeos do aparelho e agrupa por pasta.
 *
 * **A diferença mais importante em relação ao app de iOS.** Lá o sistema não
 * deixa varrer o aparelho: cada pasta tem que ser autorizada uma a uma no
 * seletor, e o app guarda um bookmark para cada uma. Aqui o MediaStore já
 * indexa todo vídeo de todo volume montado — inclusive o pendrive na USB-C —
 * e uma permissão só abre tudo. Toda a máquina de bookmarks do iOS
 * simplesmente não existe deste lado.
 */
object MediaLibrary {

    suspend fun scan(context: Context): List<VideoGroup> = withContext(Dispatchers.IO) {
        val colunas = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        )

        // Cada volume é consultado separadamente: o pendrive tem nome próprio
        // e não aparece em EXTERNAL_CONTENT_URI, que é só o armazenamento
        // interno. Era exatamente o caso de uso que motivou o app.
        val volumes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.getExternalVolumeNames(context).toList()
        } else {
            listOf(MediaStore.VOLUME_EXTERNAL)
        }

        val porPasta = LinkedHashMap<String, MutableList<MediaItem>>()

        for (volume in volumes) {
            val uriBase = runCatching { MediaStore.Video.Media.getContentUri(volume) }.getOrNull()
                ?: continue

            val cursor = runCatching {
                context.contentResolver.query(
                    uriBase, colunas, null, null,
                    "${MediaStore.Video.Media.BUCKET_DISPLAY_NAME} ASC, ${MediaStore.Video.Media.DISPLAY_NAME} ASC",
                )
            }.getOrNull() ?: continue

            cursor.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val iNome = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val iTamanho = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val iData = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                val iDuracao = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val iPasta = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)

                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val nome = c.getString(iNome) ?: continue
                    val pasta = c.getString(iPasta) ?: "Vídeos"
                    val uri = ContentUris.withAppendedId(uriBase, id)
                    val duracaoMs = c.getLong(iDuracao)

                    val item = MediaItem(
                        id = "$volume:$id",
                        title = nome,
                        origin = MediaOrigin.Local(uri),
                        fileSize = c.getLong(iTamanho).takeIf { it > 0 },
                        // O MediaStore guarda segundos; o resto do app fala em
                        // milissegundos desde 1970.
                        modifiedAt = c.getLong(iData) * 1000,
                        duration = (duracaoMs / 1000.0).takeIf { duracaoMs > 0 },
                    )
                    porPasta.getOrPut(pasta) { mutableListOf() }.add(item)
                }
            }
        }

        porPasta.map { (pasta, itens) ->
            VideoGroup(
                name = pasta,
                path = pasta,
                // Ordem natural: "Ep 2" antes de "Ep 10".
                items = itens.sortedWith(compareBy(NATURAL) { it.title }),
            )
        }.sortedWith(compareBy(NATURAL) { it.name })
    }

    /**
     * Comparação natural — a mesma de `localizedStandardCompare` no iOS.
     *
     * Sem ela "Temporada 10" vem antes de "Temporada 2", que é como todo app
     * que ordena texto puro erra numa lista de episódios.
     */
    val NATURAL: Comparator<String> = Comparator { a, b ->
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var fa = i
                var fb = j
                while (fa < a.length && a[fa].isDigit()) fa++
                while (fb < b.length && b[fb].isDigit()) fb++
                val na = a.substring(i, fa).trimStart('0')
                val nb = b.substring(j, fb).trimStart('0')
                if (na.length != nb.length) return@Comparator na.length - nb.length
                val cmp = na.compareTo(nb)
                if (cmp != 0) return@Comparator cmp
                i = fa
                j = fb
            } else {
                val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (cmp != 0) return@Comparator cmp
                i++
                j++
            }
        }
        (a.length - i) - (b.length - j)
    }
}
