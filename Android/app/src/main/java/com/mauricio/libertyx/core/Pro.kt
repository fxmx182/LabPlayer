package com.mauricio.libertyx.core

import android.content.Context
import com.mauricio.libertyx.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * O LibertyX Pro: o que está liberado, e por quê.
 *
 * O app é grátis e completo como player de vídeo do aparelho. O Pro é uma
 * compra única que libera cinco coisas ([Recurso]); nos primeiros sete dias
 * elas vêm liberadas de graça, para quem instalou experimentar antes de pagar.
 *
 * **O teste é do app, não da Play.** A Play só oferece período grátis em
 * assinatura; para compra única não existe. Então o app anota o dia da
 * primeira abertura e conta a partir dele. A anotação vai no backup automático
 * do Android, o que faz reinstalar na mesma conta não recomeçar a contagem —
 * não é à prova de quem insiste, e não precisa ser: o preço é de um lanche.
 *
 * **Terminado o teste, nada some.** Bloqueia só o que é Pro; a biblioteca, o
 * player, os gestos e a busca quadro a quadro continuam. Um app que se tranca
 * inteiro depois de uma semana converte mais e ganha avaliação de uma
 * estrela na mesma proporção.
 */
object Pro {

    /** O que o Pro libera. A ordem é a da tela de compra. */
    enum class Recurso { REDE, SEGUNDO_PLANO, JANELA, DORMIR, CAPTURA }

    sealed interface Estado {
        /** Comprado, ou o APK do dono ([BuildConfig.LIBERADO]). */
        data object Comprado : Estado
        data class Teste(val diasRestantes: Int) : Estado
        data object Expirado : Estado
    }

    val DIAS_DE_TESTE = BuildConfig.DIAS_DE_TESTE
    private const val DIA = 24L * 60 * 60 * 1000

    private const val KEY_INICIO = "pro.inicioDoTeste"
    private const val KEY_COMPRADO = "pro.comprado"

    private lateinit var app: Context
    private val _estado = MutableStateFlow<Estado>(Estado.Expirado)
    val estado: StateFlow<Estado> = _estado.asStateFlow()

    fun iniciar(contexto: Context) {
        app = contexto.applicationContext
        val prefs = Prefs.get(app)
        // A primeira abertura depois de instalar — ou de atualizar da versão
        // que ainda não tinha o Pro: quem já usava ganha a semana também.
        if (!prefs.contains(KEY_INICIO)) {
            prefs.edit().putLong(KEY_INICIO, System.currentTimeMillis()).apply()
        }
        recalcular()
    }

    /** Liberado agora, seja por compra, seja pelo teste. */
    val ativo: Boolean get() = estado.value !is Estado.Expirado

    /**
     * O que a Play respondeu sobre a compra.
     *
     * Guardado, e não só consultado: sem rede, ou num aparelho sem Play (Fire
     * TV, emulador), a Play não responde, e quem pagou não pode perder o Pro
     * por isso. Um reembolso chega do mesmo jeito — na próxima vez que ela
     * responder, com a lista vazia.
     */
    fun registrarCompra(comprado: Boolean) {
        Prefs.get(app).edit().putBoolean(KEY_COMPRADO, comprado).apply()
        recalcular()
    }

    fun recalcular() {
        val prefs = Prefs.get(app)
        _estado.value = when {
            BuildConfig.LIBERADO || prefs.getBoolean(KEY_COMPRADO, false) -> Estado.Comprado
            else -> {
                val inicio = prefs.getLong(KEY_INICIO, System.currentTimeMillis())
                // Relógio atrasado não vira dia negativo nem teste maior.
                val passados = ((System.currentTimeMillis() - inicio).coerceAtLeast(0) / DIA).toInt()
                val restam = DIAS_DE_TESTE - passados
                if (restam > 0) Estado.Teste(restam) else Estado.Expirado
            }
        }
    }
}
