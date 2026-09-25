package com.mauricio.libertyx.core

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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Typography
import com.mauricio.libertyx.R

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

    /**
     * A letra do app.
     *
     * A Roboto do sistema é a letra de todo app de Android — e de nenhum em
     * particular. A Manrope tem o mesmo desenho limpo, mas com o "a" e o "g"
     * mais abertos e números de largura constante, o que deixa a duração e o
     * tamanho alinhados sem esforço. Estática em cinco pesos, e não variável:
     * a variável só varia do Android 8 em diante, e o app vai até o 7.
     */
    val font = FontFamily(
        Font(R.font.manrope_regular, FontWeight.Normal),
        Font(R.font.manrope_medium, FontWeight.Medium),
        Font(R.font.manrope_semibold, FontWeight.SemiBold),
        Font(R.font.manrope_bold, FontWeight.Bold),
        Font(R.font.manrope_extrabold, FontWeight.ExtraBold),
    )

    val radiusCard = 18.dp
    val radiusSmall = 12.dp

    /** Título de seção: caixa alta, pequeno, espaçado — o contrário da
     *  tipografia do corpo, que é apertada. A diferença entre as duas é o que
     *  organiza a tela sem precisar de linha divisória. */
    val sectionTitle = TextStyle(
        fontFamily = font,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.6.sp,
        color = faint,
    )

    /** O título grande de cada tela — pesado e apertado, como capa de revista. */
    val headline = TextStyle(
        fontFamily = font,
        fontSize = 32.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.8).sp,
        color = text,
    )
}

/** Uma superfície de vidro — cartão, linha de lista, painel. */
fun Modifier.labCard(radius: androidx.compose.ui.unit.Dp = LabTheme.radiusCard): Modifier =
    this.background(LabTheme.glass, RoundedCornerShape(radius))
        .border(0.5.dp, LabTheme.glassBorder, RoundedCornerShape(radius))

@Composable
fun LibertyXTheme(content: @Composable () -> Unit) {
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
        typography = tipografia(),
        content = content,
    )
}

/** Todos os estilos do Material com a letra do app — assim nenhum texto escapa. */
private fun tipografia(): Typography {
    val base = Typography()
    fun TextStyle.nossa() = copy(fontFamily = LabTheme.font)
    return Typography(
        displayLarge = base.displayLarge.nossa(),
        displayMedium = base.displayMedium.nossa(),
        displaySmall = base.displaySmall.nossa(),
        headlineLarge = base.headlineLarge.nossa(),
        headlineMedium = base.headlineMedium.nossa(),
        headlineSmall = base.headlineSmall.nossa(),
        titleLarge = base.titleLarge.nossa(),
        titleMedium = base.titleMedium.nossa(),
        titleSmall = base.titleSmall.nossa(),
        bodyLarge = base.bodyLarge.nossa(),
        bodyMedium = base.bodyMedium.nossa(),
        bodySmall = base.bodySmall.nossa(),
        labelLarge = base.labelLarge.nossa(),
        labelMedium = base.labelMedium.nossa(),
        labelSmall = base.labelSmall.nossa(),
    )
}
