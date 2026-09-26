package com.mauricio.libertyx

import android.app.Application
import com.mauricio.libertyx.core.Textos

/**
 * Quase nada acontece aqui de propósito.
 *
 * A tentação é subir o `LibVLC` no início do app para a primeira reprodução
 * começar mais rápido. Não vale: são dezenas de megabytes de biblioteca nativa
 * carregados antes de a primeira tela aparecer, e quem abre o app para ver a
 * lista paga isso sem ganhar nada. O motor sobe quando alguém toca num vídeo.
 *
 * Só os textos são ligados aqui: formatação de data e mensagens de erro vivem
 * em objetos sem tela, e precisam do idioma do aparelho mesmo assim.
 */
class LibertyXApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Textos.app = this
    }
}
