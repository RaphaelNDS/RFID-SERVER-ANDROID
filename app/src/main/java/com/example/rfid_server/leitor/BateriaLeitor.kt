package com.example.rfid_server.leitor

/** Aviso de bateria do leitor na faixa. */
enum class AlertaBateria { NORMAL, BAIXA, CRITICA }

object BateriaLeitor {

    /** Abaixo disto (%), a faixa avisa para trocar ou carregar. */
    const val LIMITE_BAIXA = 15

    /** Abaixo disto (%), o aviso fica vermelho e toca o bip de erro (uma vez). */
    const val LIMITE_CRITICA = 5

    /** Sem leitura de bateria ([nivel] null) ou carregando, não há o que avisar. */
    fun classificar(nivel: Int?, carregando: Boolean): AlertaBateria = when {
        nivel == null || nivel < 0 || carregando -> AlertaBateria.NORMAL
        nivel < LIMITE_CRITICA -> AlertaBateria.CRITICA
        nivel < LIMITE_BAIXA -> AlertaBateria.BAIXA
        else -> AlertaBateria.NORMAL
    }

    /** O bip de alerta toca só ao ENTRAR na faixa crítica (não a cada 1% que a bateria cai). */
    fun deveTocarAlerta(anterior: AlertaBateria, atual: AlertaBateria): Boolean =
        atual == AlertaBateria.CRITICA && anterior != AlertaBateria.CRITICA

    /** Nível em % a partir do ACTION_BATTERY_CHANGED (level/scale); null se o aparelho não informou. */
    fun percentual(nivel: Int, escala: Int): Int? =
        if (nivel < 0 || escala <= 0) null else (nivel * 100 / escala).coerceIn(0, 100)
}
