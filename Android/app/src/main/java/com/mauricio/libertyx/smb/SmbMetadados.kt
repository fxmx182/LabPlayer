package com.mauricio.libertyx.smb

import android.util.Log
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.auth.NtlmAuthenticator
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.TimeUnit

/**
 * Tamanho e data dos arquivos de uma pasta do servidor.
 *
 * Existe só porque o VLC não conta: a listagem que ele devolve traz nome e
 * tipo, e nada mais. Sem tamanho e data, a pasta de rede só poderia ser
 * ordenada por nome — as opções da biblioteca ficariam pela metade justamente
 * onde a coleção costuma ser maior.
 *
 * **Quem lista continua sendo o VLC** (ver [SmbBrowser]): este cliente não
 * decide o que aparece nem o que toca. Ele só completa dois campos, e se falhar
 * — servidor que ele não entende, autenticação que só o VLC acerta — a pasta
 * abre igual, sem tamanho e sem data. Por isso ele nunca lança: devolve `null`.
 */
object SmbMetadados {

    data class Info(val tamanho: Long, val modificadoEm: Long)

    private const val TAG = "LibertyX"

    private fun configuracao() = SmbConfig.builder()
        // O MD4 do NTLM não existe na criptografia do Android; o do Bouncy
        // Castle vem junto com a biblioteca.
        .withSecurityProvider(BCSecurityProvider())
        // Só NTLM: o Kerberos puxaria classes de `javax` que o Android não
        // tem, e servidor de casa não usa Kerberos.
        .withAuthenticators(NtlmAuthenticator.Factory())
        .withDfsEnabled(false)
        .withTimeout(6, TimeUnit.SECONDS)
        .withSoTimeout(6, TimeUnit.SECONDS)

    private val cliente by lazy { SMBClient(configuracao().build()) }

    /**
     * Para convidado, SMB 2 sem o 3.
     *
     * No SMB 3 o smbj deriva as chaves da sessão a partir da senha — e sessão
     * de convidado não tem chave, então ele cai num `NullPointerException`
     * antes de listar qualquer coisa. No 2.1 não há derivação, e para ler
     * tamanho e data não faz diferença nenhuma.
     */
    private val clienteSemSmb3 by lazy {
        SMBClient(configuracao().withDialects(SMB2Dialect.SMB_2_1, SMB2Dialect.SMB_2_0_2).build())
    }

    suspend fun listar(server: SmbServer, senha: String?, share: String, pasta: String): Map<String, Info>? =
        runInterruptible(Dispatchers.IO) {
            try {
                // Usuário em branco é convidado para o servidor, marcado ou não.
                if (!server.isGuest && server.username.isNotBlank()) {
                    // O mesmo grupo de trabalho que o VLC recebe — ver SmbBrowser.credenciais.
                    val conta = AuthenticationContext(server.username, (senha ?: "").toCharArray(), "WORKGROUP")
                    ler(cliente, server, share, pasta, conta)
                } else {
                    // Há Samba que só aceita "guest" e há o que só aceita anônimo.
                    runCatching { ler(cliente, server, share, pasta, AuthenticationContext.guest()) }
                        .recoverCatching { ler(clienteSemSmb3, server, share, pasta, AuthenticationContext.guest()) }
                        .recoverCatching { ler(clienteSemSmb3, server, share, pasta, AuthenticationContext.anonymous()) }
                        .getOrThrow()
                }
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "tamanho e data indisponíveis em ${server.displayHost}/$share: $e")
                null
            }
        }

    private fun ler(
        cliente: SMBClient,
        server: SmbServer,
        share: String,
        pasta: String,
        conta: AuthenticationContext,
    ): Map<String, Info> =
        cliente.connect(server.host, server.port).use { conexao: Connection ->
            conexao.authenticate(conta).use { sessao: Session ->
                (sessao.connectShare(share) as DiskShare).use { disco ->
                    disco.list(pasta.replace('/', '\\'))
                        .filter { it.fileName != "." && it.fileName != ".." }
                        .associate { info ->
                            info.fileName to Info(info.endOfFile, info.lastWriteTime.toEpochMillis())
                        }
                }
            }
        }
}
