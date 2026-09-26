package com.mauricio.libertyx.core

import android.net.Uri
import java.util.Locale
import com.mauricio.libertyx.R

/**
 * De onde um vídeo vem.
 *
 * A mesma abstração central do app de iOS, e pelo mesmo motivo: o motor nunca
 * sabe se os bytes vieram do armazenamento do aparelho, do pendrive na USB-C
 * ou do servidor de casa — ele recebe uma URI e pronto. Trocar de origem não
 * toca em nada da interface.
 */
sealed interface MediaOrigin {

    /** Arquivo local: `file://` do MediaStore ou `content://` de outro app. */
    data class Local(val uri: Uri, val path: String? = null) : MediaOrigin

    /** Compartilhamento SMB do homelab. */
    data class Smb(val serverId: String, val host: String, val share: String, val path: String) : MediaOrigin

    /** HTTP(S) direto. */
    data class Remote(val uri: Uri) : MediaOrigin
}

/**
 * Identidade estável para retomar de onde parou.
 *
 * Não é o caminho: o pendrive remontado ganha caminho novo, o `content://` de
 * outro app muda de id a cada compartilhamento, e o servidor pode ser
 * alcançado por endereços diferentes. Guardar o caminho perderia a retomada
 * justamente nos casos que mais importam.
 */
val MediaOrigin.resumeKey: String
    get() = when (this) {
        is MediaOrigin.Local -> "file:" + (path?.substringAfterLast('/') ?: uri.lastPathSegment.orEmpty())
        is MediaOrigin.Smb -> "smb:$host/$share/$path"
        is MediaOrigin.Remote -> "url:$uri"
    }

/** Um item reproduzível, já resolvido para exibição. */
data class MediaItem(
    val id: String,
    val title: String,
    val origin: MediaOrigin,
    val fileSize: Long? = null,
    val modifiedAt: Long? = null,
    /** Em segundos, quando a origem soube dizer sem abrir o arquivo. */
    val duration: Double? = null,
) {
    companion object {
        /** Extensões que consideramos vídeo ao varrer uma pasta ou o servidor. */
        val videoExtensions = setOf(
            "mp4", "m4v", "mov", "mkv", "avi", "wmv", "flv", "webm", "ts", "m2ts",
            "mts", "mpg", "mpeg", "vob", "3gp", "ogv", "rmvb", "asf", "divx", "f4v",
        )

        fun isVideo(name: String): Boolean =
            name.substringAfterLast('.', "").lowercase(Locale.ROOT) in videoExtensions
    }
}

/** Um conjunto de vídeos que vivem na mesma pasta. */
data class VideoGroup(
    val name: String,
    val path: String,
    val items: List<MediaItem>,
)

object TimeFormat {

    /** `1:02:03` num filme, `02:03` num clipe — sem hora zerada na frente. */
    fun clock(seconds: Double): String {
        if (!seconds.isFinite() || seconds < 0) return "0:00"
        val total = seconds.toLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    /** Quanto falta ou quanto se pulou, sempre com sinal. */
    fun signed(delta: Double): String {
        val sinal = if (delta >= 0) "+" else "−"
        return sinal + clock(kotlin.math.abs(delta))
    }

    fun size(bytes: Long?): String? {
        if (bytes == null || bytes <= 0) return null
        val unidades = listOf("B", "KB", "MB", "GB", "TB")
        var valor = bytes.toDouble()
        var i = 0
        while (valor >= 1024 && i < unidades.lastIndex) {
            valor /= 1024
            i++
        }
        // A vírgula ou o ponto do idioma do aparelho: "1,5 GB" aqui, "1.5 GB" lá.
        return if (i <= 1) String.format(Locale.getDefault(), "%.0f %s", valor, unidades[i])
        else String.format(Locale.getDefault(), "%.1f %s", valor, unidades[i])
    }

    /** "12 min", "1 h 05" — duração dita como se fala, e não como relógio. */
    fun spoken(seconds: Double): String {
        val minutos = (seconds / 60).toLong().coerceAtLeast(1)
        return if (minutos < 60) Textos.get(R.string.tempo_min, minutos.toInt())
        else Textos.get(R.string.tempo_h_min, (minutos / 60).toInt(), (minutos % 60).toInt())
    }

    /**
     * "3 set" aqui, "Sep 3" em inglês — e o ano só quando não é este.
     *
     * A ordem de dia e mês é do idioma, por isso o padrão sai do sistema
     * (`getBestDateTimePattern`) e não de uma máscara fixa.
     */
    fun date(millis: Long?): String? {
        if (millis == null || millis <= 0) return null
        val idioma = Locale.getDefault()
        val agora = java.util.Calendar.getInstance()
        val entao = java.util.Calendar.getInstance().apply { timeInMillis = millis }
        val esqueleto = if (agora.get(java.util.Calendar.YEAR) == entao.get(java.util.Calendar.YEAR)) "dMMM" else "dMMMy"
        val padrao = android.text.format.DateFormat.getBestDateTimePattern(idioma, esqueleto)
        return java.text.SimpleDateFormat(padrao, idioma).format(java.util.Date(millis)).replace(".", "")
    }
}
