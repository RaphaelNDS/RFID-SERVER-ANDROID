package com.example.rfid_server

/**
 * Regras do modo localização: converte o RSSI lido da tag em nível de proximidade (0–100)
 * e define o texto, o ritmo do bip e a força da vibração para cada nível.
 * Sem dependência do Android, para poder ser testado no computador.
 */
object Proximidade {

    fun nivel(rssi: Int): Int = when {
        rssi >= -35 -> 100
        rssi >= -40 -> 85
        rssi >= -45 -> 70
        rssi >= -50 -> 55
        rssi >= -55 -> 40
        rssi >= -60 -> 25
        else -> 10
    }

    /** Distância relativa do TagLocationing do leitor (0 longe, 100 encostada) já na mesma escala do nível. */
    fun nivelDaDistancia(distancia: Int): Int = distancia.coerceIn(0, 100)

    fun descricao(nivel: Int): String = when {
        nivel >= 90 -> "🔥 Tag muito próxima"
        nivel >= 70 -> "Muito perto"
        nivel >= 50 -> "Aproximando"
        nivel >= 30 -> "Longe"
        else -> "Muito longe"
    }

    // Quanto mais perto, mais rápido o bip
    fun intervaloFeedbackMs(nivel: Int): Long = when {
        nivel > 80 -> 120
        nivel > 60 -> 250
        nivel > 40 -> 450
        nivel > 20 -> 700
        else -> 1200
    }

    // Quanto mais perto, mais forte a vibração
    fun duracaoVibracaoMs(nivel: Int): Long = when {
        nivel > 80 -> 80
        nivel > 60 -> 60
        nivel > 40 -> 40
        nivel > 20 -> 25
        else -> 15
    }
}
