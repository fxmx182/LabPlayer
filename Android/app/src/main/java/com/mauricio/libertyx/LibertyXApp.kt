package com.mauricio.libertyx

import android.app.Application

/**
 * Nada acontece aqui de propósito.
 *
 * A tentação é subir o `LibVLC` no início do app para a primeira reprodução
 * começar mais rápido. Não vale: são dezenas de megabytes de biblioteca nativa
 * carregados antes de a primeira tela aparecer, e quem abre o app para ver a
 * lista paga isso sem ganhar nada. O motor sobe quando alguém toca num vídeo.
 */
class LibertyXApp : Application()
