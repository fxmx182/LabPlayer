package com.mauricio.libertyx.core

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * Os textos do app para quem não tem tela: formatação, erros do motor,
 * notificação, Android Auto.
 *
 * Nas telas em Compose vale `stringResource`, que refaz a tela quando o idioma
 * muda; aqui é o contexto do aplicativo, que o sistema atualiza junto.
 *
 * O padrão é inglês (`values/`), para qualquer idioma sem tradução; português
 * e espanhol vêm de `values-pt/` e `values-es/`. Texto novo entra nos três.
 */
object Textos {
    lateinit var app: Context

    fun get(@StringRes id: Int, vararg args: Any): String = app.getString(id, *args)

    fun plural(@PluralsRes id: Int, n: Int, vararg args: Any): String =
        app.resources.getQuantityString(id, n, *args)
}
