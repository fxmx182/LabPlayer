package com.mauricio.viperplayer.core

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * A linguagem visual do app.
 *
 * A mesma do irmão de iOS, e vale repetir o porquê de lá: a estrutura foi
 * emprestada do painel do datacenter — superfícies de vidro sobre fundo
 * profundo, cantos generosos, tipografia apertada — e a cor veio do próprio
 * ícone. O que muda deste lado é o resto: lá o sistema entrega folhas e listas
 * prontas com essa cara; aqui o Material tem outra, e o que fica é a
 * identidade, não a imitação de um app de iPhone.
 */
object LabTheme {

    /** O amarelo da marca, colhido do ícone. */
    val accent = Color(0xFFFBC501)

    val green = Color(0xFF30D158)
    val orange = Color(0xFFFF9F0A)
    val red = Color(0xFFFF453A)

    /** Os três níveis de texto do modo escuro. */
    val text = Color.White.copy(alpha = 0.94f)
    val muted = Color(0xFFEBEBF5).copy(alpha = 0.60f)
    val faint = Color(0xFFEBEBF5).copy(alpha = 0.38f)

    /** Vidro: a borda um pouco mais viva que o preenchimento — é essa
     *  diferença que dá a impressão de espessura. */
    val glass = Color.White.copy(alpha = 0.07f)
    val glassBorder = Color.White.copy(alpha = 0.13f)

    val background = Color(0xFF0E0E10)
    val surface = Color(0xFF17171B)

    val radiusCard = 18.dp
    val radiusSmall = 12.dp

    /** Título de seção: caixa alta, pequeno, espaçado — o contrário da
     *  tipografia do corpo, que é apertada. A diferença entre as duas é o que
     *  organiza a tela sem precisar de linha divisória. */
    val sectionTitle = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        color = faint,
    )
}

/** Uma superfície de vidro — cartão, linha de lista, painel. */
fun Modifier.labCard(radius: androidx.compose.ui.unit.Dp = LabTheme.radiusCard): Modifier =
    this.background(LabTheme.glass, RoundedCornerShape(radius))
        .border(0.5.dp, LabTheme.glassBorder, RoundedCornerShape(radius))

@Composable
fun ViperTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = LabTheme.accent,
            onPrimary = Color.Black,
            background = LabTheme.background,
            onBackground = LabTheme.text,
            surface = LabTheme.surface,
            onSurface = LabTheme.text,
            surfaceVariant = LabTheme.glass,
            onSurfaceVariant = LabTheme.muted,
            error = LabTheme.red,
        ),
        content = content,
    )
}
