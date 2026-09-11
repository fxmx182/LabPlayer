package com.mauricio.libertyx.smb

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * Procura servidores SMB na rede local.
 *
 * Duas técnicas somadas, porque cada uma sozinha deixa buraco — é o mesmo
 * desenho do irmão de iOS, e pelas mesmas razões:
 *
 * - **mDNS** (`_smb._tcp`) acha quem se anuncia e traz o nome legível da
 *   máquina. Mas um Samba em Linux sem Avahi instalado não aparece.
 * - **Varredura da sub-rede na porta 445** acha qualquer coisa que aceite
 *   conexão SMB, anunciando-se ou não. Em troca, só sabe o endereço.
 *
 * Juntas cobrem o NAS e o Windows (que se anunciam) e o servidor caseiro em
 * Linux (que normalmente não).
 *
 * **O que muda em relação ao iOS:** lá a rede é o Wi-Fi e o código pergunta
 * pela interface `en0`. Aqui não dá: uma caixinha de Android TV quase sempre
 * está no cabo, e fixar o Wi-Fi deixaria a descoberta sem funcionar
 * justamente no aparelho que mais precisa dela. Quem responde qual é a rede em
 * uso é o sistema.
 *
 * **Android de PC** (emulador, BlueStacks, Waydroid) vive numa rede virtual,
 * atrás do PC: o anúncio mDNS não atravessa e a varredura só enxerga a própria
 * rede virtual — enquanto conectar num endereço da casa, digitado à mão,
 * funciona normalmente. Por isso, fora da rede do aparelho a varredura também
 * percorre a rede dos servidores já salvos e, quando a rede do aparelho é
 * virtual, as faixas que os roteadores de casa usam de fábrica.
 */
class SmbDiscovery(
    private val context: Context,
    /** Endereços dos servidores já salvos — a rede deles também é varrida. */
    private val conhecidos: List<String> = emptyList(),
) {

    data class Encontrado(
        val host: String,
        /** Nome da máquina quando o mDNS informa; senão, o próprio endereço. */
        val nome: String,
        val viaMdns: Boolean,
    )

    /**
     * Procura até quem chamou desistir (a busca morre com o escopo).
     *
     * Os resultados chegam um a um, e não numa lista no fim: numa rede local o
     * primeiro servidor costuma responder em milissegundos, e mostrá-lo já é
     * a diferença entre "achou na hora" e "ficou pensando por dez segundos".
     */
    suspend fun procurar(aoEncontrar: (Encontrado) -> Unit) = coroutineScope {
        launch { porMdns(aoEncontrar) }
        launch { porVarredura(aoEncontrar) }
    }

    // ------------------------------------------------------------------
    // mDNS
    // ------------------------------------------------------------------

    private suspend fun porMdns(aoEncontrar: (Encontrado) -> Unit) {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return

        suspendCancellableCoroutine<Unit> { cont ->
            val ouvinte = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(tipo: String) {}
                override fun onDiscoveryStopped(tipo: String) {}
                override fun onStartDiscoveryFailed(tipo: String, erro: Int) = encerrar(cont)
                override fun onStopDiscoveryFailed(tipo: String, erro: Int) = encerrar(cont)
                override fun onServiceLost(info: NsdServiceInfo) {}

                override fun onServiceFound(info: NsdServiceInfo) {
                    // O anúncio traz só o nome; o endereço vem da resolução.
                    // Usamos o IP resolvido, e não `nome.local`: nem todo
                    // Android resolve nomes .local por conta própria, e um
                    // endereço que não resolve é pior que nenhum.
                    @Suppress("DEPRECATION")
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, erro: Int) {}
                        override fun onServiceResolved(resolvido: NsdServiceInfo) {
                            // Só IPv4. A mesma máquina se anuncia também pelo
                            // IPv6 e apareceria duas vezes na lista — e a URL
                            // do servidor não sabe carregar endereço IPv6.
                            val ip = resolvido.host as? Inet4Address ?: return
                            val endereco = ip.hostAddress ?: return
                            aoEncontrar(
                                Encontrado(
                                    host = endereco,
                                    nome = resolvido.serviceName ?: endereco,
                                    viaMdns = true,
                                )
                            )
                        }
                    })
                }
            }

            runCatching {
                nsd.discoverServices("_smb._tcp.", NsdManager.PROTOCOL_DNS_SD, ouvinte)
            }.onFailure { encerrar(cont) }

            cont.invokeOnCancellation { runCatching { nsd.stopServiceDiscovery(ouvinte) } }
        }
    }

    private fun encerrar(cont: CancellableContinuation<Unit>) {
        if (cont.isActive) cont.resumeWith(Result.success(Unit))
    }

    // ------------------------------------------------------------------
    // Varredura da sub-rede
    // ------------------------------------------------------------------

    private suspend fun porVarredura(aoEncontrar: (Encontrado) -> Unit) = withContext(Dispatchers.IO) {
        val propria = prefixoDaRede()
        val virtual = propria == null || ehRedeVirtual(propria)

        // Prefixo → prazo de cada tentativa, em ordem de prioridade: o semáforo
        // atende na ordem de chegada, então a rede do aparelho é varrida antes
        // das outras. Nas redes adivinhadas o prazo é menor, porque a maioria
        // delas não existe e cada endereço morto custa o prazo inteiro.
        val alvos = LinkedHashMap<String, Int>()
        propria?.let { alvos[it] = 700 }
        conhecidos.mapNotNull(::prefixoDoEndereco).forEach { alvos.putIfAbsent(it, 700) }
        if (virtual) REDES_DE_CASA.forEach { alvos.putIfAbsent(it, 400) }

        // 32 por vez numa rede de verdade: rápido o bastante para varrer 254
        // endereços em segundos, e leve o bastante para não afogar o Wi-Fi.
        // Atrás de uma rede virtual quem abre as conexões é o PC, e há uma
        // dúzia de redes a percorrer.
        val limite = Semaphore(if (virtual) 96 else 32)
        coroutineScope {
            for ((prefixo, prazo) in alvos) {
                for (n in 1..254) {
                    launch {
                        limite.withPermit {
                            val endereco = "$prefixo.$n"
                            if (responde(endereco, prazo)) {
                                aoEncontrar(Encontrado(endereco, endereco, viaMdns = false))
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Uma conexão TCP que completa na porta 445 é um servidor SMB — não é
     * preciso autenticar para saber que ele existe.
     *
     * O prazo é curto de propósito: numa rede local quem responde responde em
     * milissegundos, e esperar mais só faz a busca inteira demorar.
     */
    private fun responde(host: String, prazoMs: Int): Boolean = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, 445), prazoMs)
            true
        }
    }.getOrDefault(false)

    /** A rede de um servidor salvo, se ele estiver numa rede privada IPv4. */
    private fun prefixoDoEndereco(host: String): String? = runCatching {
        val ip = InetAddress.getByName(host) as? Inet4Address ?: return null
        if (!ip.isSiteLocalAddress) return null
        ip.hostAddress?.substringBeforeLast('.')
    }.getOrNull()

    /**
     * Redes que só existem dentro de um PC.
     *
     * 10.0.2 é a do emulador do Android e do BlueStacks, 10.0.3 a do
     * Genymotion, 192.168.240 a do Waydroid, e 172.16–31 a do Subsistema do
     * Windows e das redes de contêiner. Nenhuma delas é a de um roteador de
     * casa.
     */
    private fun ehRedeVirtual(prefixo: String): Boolean {
        val partes = prefixo.split(".").map { it.toIntOrNull() ?: return false }
        if (partes.size != 3) return false
        val (a, b, c) = partes
        return (a == 10 && b == 0 && c in 2..3) ||
            (a == 192 && b == 168 && c == 240) ||
            (a == 172 && b in 16..31)
    }

    private companion object {
        /** As faixas de fábrica dos roteadores mais comuns, das mais às menos. */
        val REDES_DE_CASA = listOf(
            "192.168.0", "192.168.1", "192.168.15", "192.168.100", "192.168.2",
            "192.168.3", "192.168.10", "192.168.50", "192.168.18", "192.168.31",
            "10.0.0", "10.0.1",
        )
    }

    /**
     * Os três primeiros octetos do endereço deste aparelho na rede em uso.
     *
     * Vem do sistema, e não de um nome de interface fixo: no celular é `wlan0`,
     * na caixinha de TV é quase sempre `eth0`, e num aparelho com VPN há mais
     * de uma. Perguntar qual é a rede ativa acerta nos três casos.
     */
    private fun prefixoDaRede(): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val doSistema = runCatching {
            val rede = cm?.activeNetwork ?: return@runCatching null
            cm.getLinkProperties(rede)?.linkAddresses
                ?.map { it.address }
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        }.getOrNull()

        val ip = doSistema ?: runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull() ?: return null

        val partes = ip.split(".")
        return if (partes.size == 4) partes.take(3).joinToString(".") else null
    }
}
