package com.mauricio.viperplayer.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** Um lugar só para abrir as preferências, para não espalhar o nome do arquivo. */
object Prefs {
    private const val FILE = "viper.prefs"

    @Volatile private var instance: SharedPreferences? = null

    fun get(context: Context): SharedPreferences =
        instance ?: synchronized(this) {
            instance ?: context.applicationContext
                .getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .also { instance = it }
        }
}

/**
 * Preferências do reprodutor que sobrevivem ao fechamento do app.
 *
 * Só entra aqui o que é gosto pessoal; estado de um vídeo específico é assunto
 * do [ResumeStore].
 */
class PlayerPreferences(context: Context) {

    private val prefs = Prefs.get(context)

    /**
     * Quanto tempo os controles ficam na tela sem toque.
     *
     * Não existe um número certo: o MX Player também não escolhe um, ele
     * oferece a opção justamente porque quem assiste deitado no escuro quer
     * uma coisa e quem está mexendo no vídeo quer outra. Copiamos o ajuste,
     * não o padrão dele — que é mais curto e seria um passo para trás.
     */
    enum class AutoHide(val seconds: Double, val title: String) {
        DOIS(2.0, "2 segundos"),
        CINCO(5.0, "5 segundos"),
        DEZ(10.0, "10 segundos"),
        NUNCA(0.0, "Nunca");

        /** `null` quer dizer "não agende nada". */
        val delayMillis: Long? get() = if (this == NUNCA) null else (seconds * 1000).toLong()
    }

    var autoHide: AutoHide
        get() = runCatching { AutoHide.valueOf(prefs.getString(KEY_AUTO_HIDE, null) ?: "") }
            .getOrDefault(AutoHide.DEZ)
        set(value) = prefs.edit().putString(KEY_AUTO_HIDE, value.name).apply()

    /**
     * Quantas vezes mais rápido enquanto o dedo fica na tela.
     *
     * Dois é o padrão porque é onde a fala ainda se entende. Quem usa isso
     * para pular um trecho chato prefere mais; quem usa para ouvir um pouco
     * mais rápido prefere menos — não há número que sirva aos dois.
     */
    var holdSpeed: Float
        get() = prefs.getFloat(KEY_HOLD_SPEED, 2f).let { if (it in holdSpeedOptions) it else 2f }
        set(value) = prefs.edit().putFloat(KEY_HOLD_SPEED, value).apply()

    /**
     * Buscar no quadro exato durante o arrasto.
     *
     * É a razão de o projeto existir: o VLC comum pula de keyframe em
     * keyframe, e o dedo parece arrastar aos pulos. A busca exata decodifica
     * até o quadro pedido — mais cara, e é por isso que é opção: num arquivo
     * pela rede, o custo aparece.
     */
    var preciseScrub: Boolean
        get() = prefs.getBoolean(KEY_PRECISE, true)
        set(value) = prefs.edit().putBoolean(KEY_PRECISE, value).apply()

    /** Retomar direto, sem perguntar. Quem sempre responde a mesma coisa cansa da pergunta. */
    var askToResume: Boolean
        get() = prefs.getBoolean(KEY_ASK_RESUME, true)
        set(value) = prefs.edit().putBoolean(KEY_ASK_RESUME, value).apply()

    companion object {
        val holdSpeedOptions = listOf(1.5f, 2f, 2.5f, 3f, 4f)
        private const val KEY_AUTO_HIDE = "controls.autoHide"
        private const val KEY_HOLD_SPEED = "playback.holdSpeed"
        private const val KEY_PRECISE = "playback.preciseScrub"
        private const val KEY_ASK_RESUME = "playback.askResume"
    }
}

/**
 * Onde cada vídeo parou.
 *
 * A chave é a [resumeKey] da origem, não o caminho — ver o comentário lá.
 */
class ResumeStore private constructor(context: Context) {

    private val prefs = Prefs.get(context)
    private val marcas = HashMap<String, Marca>()

    private data class Marca(val position: Double, val duration: Double, val updatedAt: Long)

    init {
        val texto = prefs.getString(KEY, null)
        if (texto != null) runCatching {
            val raiz = JSONObject(texto)
            for (chave in raiz.keys()) {
                val o = raiz.getJSONObject(chave)
                marcas[chave] = Marca(o.getDouble("p"), o.getDouble("d"), o.getLong("t"))
            }
        }
    }

    /**
     * Posição para retomar, ou `null` quando não vale a pena.
     *
     * Os dois limites vêm da experiência de uso: retomar aos 5 segundos não
     * economiza nada, e retomar a 30 segundos do fim joga direto nos créditos.
     */
    fun position(key: String): Double? {
        val marca = marcas[key] ?: return null
        if (marca.position <= 30) return null
        if (marca.duration > 0 && marca.position > marca.duration - 30) return null
        return marca.position
    }

    fun save(position: Double, duration: Double, key: String) {
        if (!position.isFinite() || position <= 0) return
        marcas[key] = Marca(position, duration, System.currentTimeMillis())
        persist()
    }

    /** Ao terminar: quem assistiu até o fim não quer voltar para os créditos. */
    fun clear(key: String) {
        if (marcas.remove(key) != null) persist()
    }

    private fun persist() {
        // Poda os mais antigos: sem teto, a lista cresce para sempre.
        if (marcas.size > LIMITE) {
            val sobra = marcas.entries.sortedByDescending { it.value.updatedAt }.take(LIMITE)
            marcas.clear()
            sobra.forEach { marcas[it.key] = it.value }
        }
        val raiz = JSONObject()
        for ((chave, marca) in marcas) {
            raiz.put(chave, JSONObject().apply {
                put("p", marca.position); put("d", marca.duration); put("t", marca.updatedAt)
            })
        }
        prefs.edit().putString(KEY, raiz.toString()).apply()
    }

    companion object {
        private const val KEY = "viper.resume"
        private const val LIMITE = 300

        @Volatile private var instance: ResumeStore? = null

        fun get(context: Context): ResumeStore =
            instance ?: synchronized(this) {
                instance ?: ResumeStore(context.applicationContext).also { instance = it }
            }
    }
}
