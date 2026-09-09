package com.mauricio.viperplayer.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mauricio.viperplayer.core.LabTheme

/**
 * O realce de foco — a peça que faz uma tela de TV ser usável.
 *
 * No celular o dedo aponta para onde vai agir, e o app não precisa dizer nada.
 * A três metros de distância, com um controle remoto, **o foco é a única coisa
 * que diz onde você está**: sem ele o usuário aperta uma direção e não sabe o
 * que se moveu. Por isso o realce é deliberadamente exagerado — borda da cor da
 * marca, e um crescimento que se percebe de longe.
 *
 * No celular isto não aparece nunca: dedo não dá foco a nada.
 */
fun Modifier.tvFocus(
    radius: Dp = 14.dp,
    scale: Float = 1.06f,
): Modifier = composed {
    var focado by remember { mutableStateOf(false) }

    val escala by animateFloatAsState(
        targetValue = if (focado) scale else 1f,
        label = "escala do foco",
    )
    val cor by animateColorAsState(
        targetValue = if (focado) LabTheme.accent else Color.Transparent,
        label = "borda do foco",
    )

    this
        .onFocusChanged { focado = it.isFocused }
        .scale(escala)
        .border(if (focado) 2.5.dp else 0.dp, cor, RoundedCornerShape(radius))
}

/**
 * Deixa as setas saírem de dentro de um campo de texto.
 *
 * O Compose entrega as setas ao cursor do campo — comportamento certo num
 * teclado de computador, e uma armadilha num controle remoto: o usuário entra
 * no primeiro campo do formulário e **não sai mais dele**. Como os campos aqui
 * são todos de uma linha só, não há cursor vertical para mover, e cima/baixo
 * podem significar o que significam no resto da tela: trocar de campo.
 *
 * `onPreviewKeyEvent` porque a interceptação precisa acontecer antes de o campo
 * consumir a tecla — depois já é tarde.
 */
fun Modifier.setasTrocamDeCampo(): Modifier = composed {
    val foco = LocalFocusManager.current
    onPreviewKeyEvent { evento ->
        if (evento.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (evento.key) {
            Key.DirectionDown -> { foco.moveFocus(FocusDirection.Down); true }
            Key.DirectionUp -> { foco.moveFocus(FocusDirection.Up); true }
            else -> false
        }
    }
}

/** Fonte de interação sem ondulação: o efeito de toque não faz sentido na TV. */
@Composable
fun rememberTvInteraction(): MutableInteractionSource =
    remember { MutableInteractionSource() }
