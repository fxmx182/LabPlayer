package com.mauricio.libertyx.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.mutableIntStateOf
import com.mauricio.libertyx.core.MediaItem
import com.mauricio.libertyx.core.MediaOrigin
import com.mauricio.libertyx.smb.SmbBrowser
import com.mauricio.libertyx.smb.SmbServerStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Miniaturas dos vídeos — do aparelho e do servidor.
 *
 * **No aparelho** o caminho barato vem primeiro: para o que está no MediaStore
 * o sistema já guarda uma miniatura pronta, e pedir a ele custa um acesso a
 * disco em vez de abrir e decodificar o arquivo. Só quando ele não tem é que o
 * `MediaMetadataRetriever` entra — e aí, no máximo duas de cada vez, senão uma
 * pasta com trezentos filmes derruba o app tentando decodificar tudo junto.
 *
 * **No servidor** não há sistema que guarde miniatura, e quem tira o quadro é
 * o próprio VLC ([QuadroDoVlc]) — o mesmo que toca o arquivo, com as mesmas
 * credenciais. Daí duas regras que o local não precisa:
 *
 * - **Cache em disco.** Cada miniatura custa abrir o arquivo pela rede e
 *   decodificar um quadro; refazer isso a cada visita à pasta seria
 *   inaceitável. A duração, descoberta de graça no caminho, fica guardada
 *   junto — é ela que permite ordenar a pasta de rede por duração.
 * - **Pausa durante a reprodução.** Tirar miniatura do servidor enquanto o
 *   filme começa a tocar do mesmo servidor disputa rede e decodificador
 *   justamente quando a reprodução precisa de tudo.
 */
object Thumbnails {

    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private val limite = Semaphore(2)

    /** Duas de cada vez também no servidor: cada uma monta um decodificador inteiro. */
    private val limiteDaRede = Semaphore(2)

    /** O que falhou nesta sessão não é tentado de novo a cada rolagem da lista. */
    private val falharam = ConcurrentHashMap.newKeySet<String>()

    private val emReproducao = MutableStateFlow(false)

    private val duracoes = ConcurrentHashMap<String, Double>()
    @Volatile private var duracoesLidas = false

    /** Só serve para o Compose redesenhar quem mostra duração quando chega uma nova. */
    private val versaoDasDuracoes = mutableIntStateOf(0)

    fun cached(item: MediaItem): Bitmap? = cache.get(item.id)

    /** Ligado pelo player enquanto um vídeo está aberto. */
    fun pausar(pausa: Boolean) {
        emReproducao.value = pausa
    }

    /** Duração descoberta ao tirar a miniatura do servidor, em segundos. */
    fun duracao(context: Context, item: MediaItem): Double? {
        versaoDasDuracoes.intValue
        lerDuracoes(context)
        return duracoes[item.id]
    }

    suspend fun load(context: Context, item: MediaItem): Bitmap? {
        cache.get(item.id)?.let { return it }

        return when (val origem = item.origin) {
            is MediaOrigin.Local -> limite.withPermit {
                withContext(Dispatchers.IO) {
                    val imagem = doSistema(context, origem) ?: doArquivo(context, origem)
                    imagem?.also { cache.put(item.id, it) }
                }
            }
            is MediaOrigin.Smb -> doServidor(context, item, origem)
            is MediaOrigin.Remote -> null
        }
    }

    private suspend fun doServidor(context: Context, item: MediaItem, origem: MediaOrigin.Smb): Bitmap? {
        if (item.id in falharam) return null

        val arquivo = File(context.cacheDir, "miniaturas/${hash(item.id)}.jpg")
        withContext(Dispatchers.IO) {
            if (arquivo.exists()) BitmapFactory.decodeFile(arquivo.path) else null
        }?.let { guardada ->
            cache.put(item.id, guardada)
            return guardada
        }

        return limiteDaRede.withPermit {
            // Espera o filme fechar em vez de desistir: ao voltar do player,
            // as miniaturas que faltavam continuam de onde pararam.
            emReproducao.first { !it }
            cache.get(item.id)?.let { return@withPermit it }

            val (uri, credenciais) = withContext(Dispatchers.IO) {
                val loja = SmbServerStore.get(context)
                val servidor = loja.byId(origem.serverId) ?: return@withContext null
                SmbBrowser.uri(servidor, origem.share, origem.path) to
                    SmbBrowser.credenciais(servidor, loja.password(servidor))
            } ?: return@withPermit null

            val resultado = QuadroDoVlc.extrair(context, uri, credenciais)
            if (resultado == null) {
                falharam += item.id
                return@withPermit null
            }

            withContext(Dispatchers.IO) {
                runCatching {
                    arquivo.parentFile?.mkdirs()
                    arquivo.outputStream().use { resultado.imagem.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                }
                if (resultado.duracaoMs > 0) {
                    context.getSharedPreferences(ARQUIVO_DURACOES, Context.MODE_PRIVATE).edit()
                        .putFloat(item.id, resultado.duracaoMs / 1000f)
                        .apply()
                }
            }
            if (resultado.duracaoMs > 0) {
                duracoes[item.id] = resultado.duracaoMs / 1000.0
                withContext(Dispatchers.Main) { versaoDasDuracoes.intValue++ }
            }
            cache.put(item.id, resultado.imagem)
            resultado.imagem
        }
    }

    private fun lerDuracoes(context: Context) {
        if (duracoesLidas) return
        duracoesLidas = true
        context.getSharedPreferences(ARQUIVO_DURACOES, Context.MODE_PRIVATE).all.forEach { (chave, valor) ->
            (valor as? Float)?.let { duracoes.putIfAbsent(chave, it.toDouble()) }
        }
    }

    private fun hash(texto: String): String =
        MessageDigest.getInstance("SHA-1").digest(texto.toByteArray())
            .joinToString("") { "%02x".format(it) }

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

    private const val ARQUIVO_DURACOES = "libertyx.duracoes"
}
