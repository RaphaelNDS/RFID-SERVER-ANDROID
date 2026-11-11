package com.example.rfid_server.leitor

/**
 * Evita que uma rajada de etiquetas novas (dezenas por segundo com o gatilho apertado) vire uma
 * rajada de bips encavalados: libera no máximo um aviso a cada [intervaloMs].
 * Sem dependência do Android, para poder ser testado no computador.
 */
class LimitadorAviso(
    private val intervaloMs: Long,
    // Relógio monotônico: com o relógio do aparelho voltando (hora acertada), o intervalo travaria os avisos
    private val relogio: () -> Long = { System.nanoTime() / 1_000_000 }
) {

    private var ultimo = Long.MIN_VALUE

    @Synchronized
    fun liberar(): Boolean {
        val agora = relogio()
        if (ultimo != Long.MIN_VALUE && agora - ultimo < intervaloMs) return false
        ultimo = agora
        return true
    }
}
