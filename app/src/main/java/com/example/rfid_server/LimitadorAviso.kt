package com.example.rfid_server

/**
 * Evita que uma rajada de etiquetas novas (dezenas por segundo com o gatilho apertado) vire uma
 * rajada de bips encavalados: libera no máximo um aviso a cada [intervaloMs].
 * Sem dependência do Android, para poder ser testado no computador.
 */
class LimitadorAviso(
    private val intervaloMs: Long,
    private val relogio: () -> Long = System::currentTimeMillis
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
