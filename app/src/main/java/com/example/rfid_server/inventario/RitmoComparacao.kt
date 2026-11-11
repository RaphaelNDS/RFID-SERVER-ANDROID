package com.example.rfid_server.inventario

/**
 * Agrupa as atualizações da tela de inventário: com milhares de etiquetas, recalcular a cada leitura pesava.
 * Devolve quanto esperar (ms) para o próximo cálculo, de forma que ele rode no máximo uma vez a cada [intervaloMs].
 */
object RitmoComparacao {

    const val INTERVALO_MS = 300L

    fun espera(agora: Long, ultimoCalculo: Long?, intervaloMs: Long = INTERVALO_MS): Long {
        if (ultimoCalculo == null) return 0
        return (ultimoCalculo + intervaloMs - agora).coerceIn(0, intervaloMs)
    }
}
