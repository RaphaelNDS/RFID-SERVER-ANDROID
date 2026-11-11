package com.example.rfid_server.telas

import android.content.Context
import android.content.res.Configuration
import com.example.rfid_server.configuracoes.AppConfig

/**
 * "Letra maior": aumenta o texto das telas do app (o que está em sp; o Localizar fica de fora, já é de texto grande)
 * em [ESCALA], por cima do tamanho
 * escolhido no Android. Para quem tem dificuldade de ler a tela pequena do coletor sem mexer nas configurações do
 * aparelho (que valem para os outros apps também). Cada tela chama [aplicar] no attachBaseContext.
 */
object TamanhoTexto {

    /** 15% maior: o suficiente para ler melhor sem quebrar as telas de 320dp (conferido nas fotos do coletor). */
    const val ESCALA = 1.15f

    fun aplicar(base: Context): Context {
        if (!AppConfig.isLetraMaior(base)) return base
        val config = Configuration(base.resources.configuration)
        config.fontScale = base.resources.configuration.fontScale * ESCALA
        return base.createConfigurationContext(config)
    }
}
