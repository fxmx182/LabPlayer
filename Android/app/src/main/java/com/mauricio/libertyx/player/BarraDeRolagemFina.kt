package com.mauricio.libertyx.player

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.SeekBar
import kotlin.math.abs

/**
 * A barra de rolagem, com rolagem fina.
 *
 * Numa barra comum, cada pixel de largura vale a mesma fração do vídeo. Num
 * filme de duas horas numa barra de mil pixels, isso dá uns sete segundos por
 * pixel — mais fino do que a ponta do dedo alcança, e é por isso que acertar um
 * tempo específico era difícil: não era falta de jeito, era falta de resolução.
 *
 * A saída é a mesma dos apps de música e vídeo da Apple, e do MX Player:
 * quanto mais o dedo se afasta da barra enquanto arrasta, mais devagar ela
 * anda. Colado nela, velocidade normal; subindo, metade, um quarto, um décimo.
 * O dedo escolhe a precisão sem soltar.
 *
 * O toque é tratado aqui, e não pelo `SeekBar`: o dele é sempre absoluto — a
 * bolinha pula para baixo do dedo —, e com isso reduzir a velocidade no meio do
 * arrasto daria um salto no vídeo justamente quando se pede precisão. Os avisos
 * saem por retornos próprios; o `OnSeekBarChangeListener` continua servindo ao
 * controle remoto, que mexe na barra pelas setas.
 */
class BarraDeRolagemFina @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.seekBarStyle,
) : SeekBar(context, attrs, defStyleAttr) {

    var aoComecar: (() -> Unit)? = null
    var aoArrastar: ((Int) -> Unit)? = null
    var aoSoltar: (() -> Unit)? = null
    /** 1, 0.5, 0.25 ou 0.1 — quanto do movimento do dedo vira movimento da barra. */
    var aoMudarPrecisao: ((Float) -> Unit)? = null

    private var precisao = 1f
    private var ultimoX = 0f
    private var valor = 0f

    private val trilha: Float get() = (width - paddingLeft - paddingRight).toFloat()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // A barra vive dentro da fileira de baixo, que rola junto com
                // o resto: sem isto, um arrasto vertical para afinar sairia da
                // barra no meio do caminho.
                parent?.requestDisallowInterceptTouchEvent(true)
                isPressed = true
                ultimoX = event.x
                mudarPrecisao(1f)
                aoComecar?.invoke()
                // Um toque leva o vídeo até ali: o dedo aponta o lugar antes
                // de afinar.
                valor = posicaoDoDedo(event.x)
                aplicar()
            }

            MotionEvent.ACTION_MOVE -> {
                // Degraus, e não uma curva contínua: com curva, a precisão
                // mudaria a cada tremida vertical e o arrasto ficaria
                // imprevisível.
                val distancia = abs(event.y - height / 2f) / resources.displayMetrics.density
                mudarPrecisao(
                    when {
                        distancia < 50 -> 1f
                        distancia < 100 -> 0.5f
                        distancia < 150 -> 0.25f
                        else -> 0.1f
                    }
                )
                if (trilha > 0) {
                    valor = (valor + (event.x - ultimoX) / trilha * max * precisao)
                        .coerceIn(0f, max.toFloat())
                    aplicar()
                }
                ultimoX = event.x
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                mudarPrecisao(1f)
                aoSoltar?.invoke()
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun posicaoDoDedo(x: Float): Float =
        if (trilha <= 0) valor else ((x - paddingLeft) / trilha * max).coerceIn(0f, max.toFloat())

    private fun aplicar() {
        progress = valor.toInt()
        aoArrastar?.invoke(progress)
    }

    private fun mudarPrecisao(nova: Float) {
        if (nova == precisao) return
        precisao = nova
        aoMudarPrecisao?.invoke(nova)
    }
}
