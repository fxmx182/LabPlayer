package com.mauricio.viperplayer.smb

import android.content.Context
import android.net.Uri
import com.mauricio.viperplayer.core.MediaItem
import com.mauricio.viperplayer.library.MediaLibrary
import com.mauricio.viperplayer.player.Vlc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.videolan.libvlc.Media
import org.videolan.libvlc.interfaces.IMedia
import java.net.URLEncoder

/**
 * Navegação no servidor SMB — feita pelo próprio VLC.
 *
 * A alternativa era embarcar um cliente SMB em Java só para listar pastas. Ele
 * daria erros de autenticação mais legíveis e o tamanho dos arquivos, e em
 * troca traria o pior modo de falhar que este app pode ter: a pasta abre e o
 * vídeo não, porque quem lista e quem toca são implementações diferentes de
 * SMB, com versões e dialetos diferentes. Aqui, se a pasta apareceu, o VLC
 * chegou no servidor — e é ele que vai tocar.
 */
object SmbBrowser {

    data class Entry(
        val name: String,
        val uri: Uri,
        val isDirectory: Boolean,
        /** Caminho relativo ao share, para montar a origem do item. */
        val path: String,
        val durationMs: Long = 0,
    )

    /** Raiz do servidor: a lista de compartilhamentos. */
    fun rootUri(server: SmbServer): Uri = Uri.parse(buildUrl(server, share = null, path = ""))

    fun uri(server: SmbServer, share: String, path: String): Uri =
        Uri.parse(buildUrl(server, share, path))

    /**
     * As credenciais, como **opções**, e nunca dentro da URL.
     *
     * Foi aqui que o app deixou de conectar em servidor com senha, e o sintoma
     * enganava: o VLC avisa `Password in a URI is DEPRECATED` e **segue sem a
     * senha**. A tentativa então chega ao servidor como anônima — e no log do
     * servidor não aparece nem uma falha de autenticação do usuário, porque o
     * usuário nunca foi enviado. Parecia problema de rede.
     *
     * De quebra, some toda uma classe de defeito: senha com `@`, `/`, `:` ou
     * acento não precisa mais sobreviver a uma codificação de URL.
     */
    fun credenciais(server: SmbServer, password: String?): List<String> {
        if (server.isGuest) return emptyList()
        return buildList {
            add(":smb-user=${server.username}")
            if (!password.isNullOrEmpty()) add(":smb-pwd=$password")
            // O grupo de trabalho padrão do Samba. Sem ele, o libsmb2 manda
            // domínio vazio e há servidor que recusa por isso.
            add(":smb-domain=WORKGROUP")
        }
    }

    /**
     * Monta a URL que o VLC entende — só endereço e caminho.
     */
    private fun buildUrl(server: SmbServer, share: String?, path: String): String {
        val porta = if (server.port == 445) "" else ":${server.port}"
        val partes = buildList {
            if (share != null) add(esc(share))
            addAll(path.split('/').filter { it.isNotEmpty() }.map { esc(it) })
        }.joinToString("/")
        val cauda = if (partes.isEmpty()) "" else "/$partes"
        return "smb://${server.host}$porta$cauda"
    }

    private fun esc(texto: String): String =
        URLEncoder.encode(texto, "UTF-8").replace("+", "%20")

    /**
     * Lista uma pasta (ou os shares, na raiz).
     *
     * Devolve `null` quando o servidor não respondeu — que é diferente de uma
     * pasta vazia, e a tela precisa distinguir os dois para não dizer "nada
     * aqui" quando o problema foi a senha.
     */
    suspend fun list(
        context: Context,
        uri: Uri,
        credenciais: List<String> = emptyList(),
        timeoutMs: Long = 15_000,
    ): List<Entry>? =
        withContext(Dispatchers.IO) {
            val libVlc = Vlc.get(context)
            val media = Media(libVlc, uri)
            credenciais.forEach { media.addOption(it) }
            try {
                val status = withTimeoutOrNull(timeoutMs) { parse(media) }
                    ?: IMedia.ParsedStatus.Timeout

                // O que vale é o que veio, não o que ele disse que veio: há
                // servidor que devolve a lista e ainda assim carimba o estado
                // como "pulado". Só quando não veio nada é que o estado decide
                // entre "pasta vazia" e "não deu para falar com o servidor".
                val lista = media.subItems()
                    ?: return@withContext if (status == IMedia.ParsedStatus.Done) emptyList() else null
                val entradas = mutableListOf<Entry>()
                try {
                    for (i in 0 until lista.count) {
                        val filho = lista.getMediaAt(i) ?: continue
                        try {
                            val nome = filho.getMeta(IMedia.Meta.Title)
                                ?: filho.uri.lastPathSegment
                                ?: continue
                            val ehPasta = filho.type == IMedia.Type.Directory
                            entradas += Entry(
                                name = nome,
                                uri = filho.uri,
                                isDirectory = ehPasta,
                                path = nome,
                                durationMs = filho.duration,
                            )
                        } finally {
                            filho.release()
                        }
                    }
                } finally {
                    lista.release()
                }

                if (entradas.isEmpty() && status != IMedia.ParsedStatus.Done) {
                    return@withContext null
                }

                // Pastas primeiro, depois ordem natural — o mesmo critério da
                // biblioteca local, para a lista não parecer de outro app.
                entradas.sortedWith(
                    compareByDescending<Entry> { it.isDirectory }
                        .thenComparing({ it.name }, MediaLibrary.NATURAL)
                )
            } finally {
                media.release()
            }
        }

    /** Só os vídeos de uma listagem, já como itens tocáveis. */
    fun playableItems(server: SmbServer, share: String, dirPath: String, entries: List<Entry>): List<MediaItem> =
        entries.filter { !it.isDirectory && MediaItem.isVideo(it.name) }
            .map { entrada ->
                val caminho = if (dirPath.isEmpty()) entrada.name else "$dirPath/${entrada.name}"
                MediaItem(
                    id = "smb:${server.id}:$share:$caminho",
                    title = entrada.name,
                    origin = com.mauricio.viperplayer.core.MediaOrigin.Smb(
                        serverId = server.id, host = server.host, share = share, path = caminho,
                    ),
                    duration = (entrada.durationMs / 1000.0).takeIf { entrada.durationMs > 0 },
                )
            }

    /**
     * Espera o VLC terminar de ler a pasta.
     *
     * `parse()` síncrono existe, mas bloqueia sem prazo: um servidor que não
     * responde travaria a tela para sempre. Com o evento, quem controla o
     * tempo é o `withTimeout` de quem chamou.
     */
    /**
     * Espera o VLC terminar de ler a pasta e devolve o estado dele.
     *
     * O estado importa e é fácil de errar: em `IMedia.ParsedStatus`, **`Done`
     * vale 4**, não 3 — 3 é `Timeout`. Comparar com o número errado faz toda
     * listagem bem-sucedida ser tratada como falha, e o sintoma é o pior
     * possível: o servidor responde, o VLC lê tudo, e a tela diz "não deu para
     * falar com o servidor". Custou uma tarde. Use a constante, nunca o número.
     */
    private suspend fun parse(media: Media): Int = suspendCancellableCoroutine { cont ->
        media.setEventListener { evento ->
            if (evento.type == IMedia.Event.ParsedChanged) {
                media.setEventListener(null)
                if (cont.isActive) cont.resumeWith(Result.success(evento.parsedStatus))
            }
        }
        // ParseNetwork é o que autoriza sair da máquina; DoInteract deixa o VLC
        // pedir credenciais em vez de desistir calado quando elas faltam.
        val iniciou = media.parseAsync(IMedia.Parse.ParseNetwork or IMedia.Parse.DoInteract)
        if (!iniciou && cont.isActive) {
            media.setEventListener(null)
            cont.resumeWith(Result.success(IMedia.ParsedStatus.Failed))
        }
        cont.invokeOnCancellation { runCatching { media.setEventListener(null) } }
    }
}
