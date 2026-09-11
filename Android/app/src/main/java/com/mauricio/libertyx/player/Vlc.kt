package com.mauricio.libertyx.player

import android.content.Context
import org.videolan.libvlc.LibVLC

/**
 * A instância única do motor.
 *
 * O `LibVLC` carrega uma biblioteca nativa de dezenas de megabytes e monta o
 * catálogo de módulos; criar um por tela custaria isso a cada abertura. Uma só,
 * viva enquanto o processo viver — é assim que o próprio VLC do Android faz.
 */
object Vlc {

    @Volatile private var instance: LibVLC? = null

    fun get(context: Context): LibVLC =
        instance ?: synchronized(this) {
            instance ?: LibVLC(context.applicationContext, options()).also { instance = it }
        }

    private fun options() = arrayListOf(
        // Acelerar sem deixar a voz fina. É o gesto de "segurar para 2×", que
        // não serviria para nada se a fala virasse guincho.
        "--audio-time-stretch",
        // O VLC do Android liga isto sozinho e vale a pena: em imagem parada
        // pelo scrub, sem ele o quadro sai borrado.
        "--avcodec-skiploopfilter", "0",
        // Reconectar sozinho: o filme não pode acabar porque o Wi-Fi piscou.
        "--http-reconnect",
        // Legenda ao lado do arquivo entra sozinha, como no MX Player.
        "--sub-autodetect-file",
        // Log só o essencial: em depuração o VLC é escandalosamente falante,
        // e o logcat cheio esconde justamente a linha que interessa.
        "-v",
    )
}
