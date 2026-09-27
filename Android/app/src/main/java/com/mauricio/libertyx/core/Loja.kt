package com.mauricio.libertyx.core

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A conversa com a Google Play sobre a compra do Pro.
 *
 * Sem servidor próprio: a compra fica na conta Google de quem pagou, e o app
 * pergunta a ela a cada abertura. É o que faz o Pro valer em todo aparelho da
 * mesma conta — celular, tablet e a TV — e voltar sozinho num celular novo.
 *
 * Três armadilhas da Play que este arquivo existe para não cair:
 * - compra não **reconhecida** em três dias é estornada automaticamente;
 * - no Brasil muita compra é por boleto ou Pix e chega **pendente** — só libera
 *   quando vira paga, e pode virar dias depois, com o app fechado;
 * - aparelho sem Play (Fire TV, emulador, APK instalado à mão) não conecta;
 *   aí vale o que foi guardado da última resposta ([Pro.registrarCompra]).
 */
object Loja {

    /** O produto cadastrado no Play Console: compra única, não consumível. */
    const val PRODUTO = "libertyx_pro"

    sealed interface Situacao {
        data object Conectando : Situacao
        /** Sem Play neste aparelho, ou fora da loja. */
        data object Indisponivel : Situacao
        data class Pronta(val preco: String) : Situacao
    }

    private lateinit var cliente: BillingClient
    private var detalhes: ProductDetails? = null

    private val _situacao = MutableStateFlow<Situacao>(Situacao.Conectando)
    val situacao: StateFlow<Situacao> = _situacao.asStateFlow()

    /** Pagou por boleto/Pix e ainda não compensou. */
    private val _pendente = MutableStateFlow(false)
    val pendente: StateFlow<Boolean> = _pendente.asStateFlow()

    fun iniciar(contexto: Context) {
        cliente = BillingClient.newBuilder(contexto.applicationContext)
            .setListener { resultado, compras ->
                if (resultado.responseCode == BillingResponseCode.OK && compras != null) processar(compras)
            }
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
        conectar()
    }

    private fun conectar() {
        _situacao.value = Situacao.Conectando
        cliente.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(resultado: BillingResult) {
                if (resultado.responseCode != BillingResponseCode.OK) {
                    _situacao.value = Situacao.Indisponivel
                    return
                }
                atualizarCompras()
                buscarPreco()
            }

            override fun onBillingServiceDisconnected() {
                // A reconexão automática cuida da próxima chamada.
            }
        })
    }

    /** Refaz a pergunta — ao abrir a tela do Pro e no "restaurar compra". */
    fun atualizar() {
        if (!::cliente.isInitialized) return
        if (cliente.isReady) {
            atualizarCompras()
            if (detalhes == null) buscarPreco()
        } else {
            conectar()
        }
    }

    private fun buscarPreco() {
        val pedido = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUTO)
                        .setProductType(ProductType.INAPP)
                        .build()
                )
            )
            .build()
        cliente.queryProductDetailsAsync(pedido) { resultado, resposta ->
            val produto = resposta.productDetailsList.firstOrNull()
            val preco = produto?.oneTimePurchaseOfferDetails?.formattedPrice
            detalhes = produto
            _situacao.value =
                if (resultado.responseCode == BillingResponseCode.OK && preco != null) Situacao.Pronta(preco)
                else Situacao.Indisponivel
        }
    }

    private fun atualizarCompras() {
        cliente.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(ProductType.INAPP).build()
        ) { resultado, compras ->
            // Só a resposta OK conta. Um erro de rede com lista vazia não
            // pode tirar o Pro de quem pagou.
            if (resultado.responseCode == BillingResponseCode.OK) processar(compras, completa = true)
        }
    }

    /**
     * @param completa a lista é tudo o que a conta tem (consulta), e não só a
     *   compra que acabou de acontecer — só então a ausência quer dizer "não
     *   comprou" (ou foi reembolsado).
     */
    private fun processar(compras: List<Purchase>, completa: Boolean = false) {
        val nossas = compras.filter { PRODUTO in it.products }
        val paga = nossas.firstOrNull { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        _pendente.value = paga == null && nossas.any { it.purchaseState == Purchase.PurchaseState.PENDING }

        if (paga != null) {
            Pro.registrarCompra(true)
            if (!paga.isAcknowledged) reconhecer(paga)
        } else if (completa) {
            Pro.registrarCompra(false)
        }
    }

    private fun reconhecer(compra: Purchase) {
        cliente.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder().setPurchaseToken(compra.purchaseToken).build()
        ) { /* Se falhar, a próxima abertura tenta de novo: há três dias. */ }
    }

    /** @return false quando não há como comprar aqui (a tela oferece a Play Store). */
    fun comprar(atividade: Activity): Boolean {
        val produto = detalhes ?: return false
        if (!cliente.isReady) return false
        val parametros = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(produto).build())
            )
            .build()
        return cliente.launchBillingFlow(atividade, parametros).responseCode == BillingResponseCode.OK
    }
}
