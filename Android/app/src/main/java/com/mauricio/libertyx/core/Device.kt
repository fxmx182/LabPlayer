package com.mauricio.libertyx.core

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.content.pm.PackageManager

/**
 * Em que tipo de aparelho o app está rodando.
 *
 * Existe um APK só para celular e televisão, e não dois. O motor, o SMB, a
 * retomada e as preferências são exatamente os mesmos — o que muda é quem
 * comanda: um dedo na tela ou um controle remoto a três metros. Dividir isso em
 * dois aplicativos duplicaria tudo o que é igual para atender ao que é
 * diferente em duas telas.
 */
object Device {

    /**
     * Televisão, e não celular grande.
     *
     * A pergunta certa é o modo de interface que o sistema declara, não o
     * tamanho da tela: um tablet de 12 polegadas é maior que muita TV e mesmo
     * assim se opera com o dedo. `UI_MODE_TYPE_TELEVISION` é o que o Android TV
     * responde, e o recurso `leanback` cobre aparelhos antigos que não
     * respondiam direito.
     */
    fun isTv(context: Context): Boolean {
        val modo = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (modo?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
        val pm = context.packageManager
        return pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }
}
