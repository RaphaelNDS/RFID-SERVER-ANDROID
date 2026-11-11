package com.example.rfid_server.leitor

/**
 * Como o [LimitadorAviso], mas com o intervalo escolhido a cada aviso: no Localizar o bip fica mais
 * rápido quanto mais perto da etiqueta ([Proximidade.intervaloFeedbackMs]). O primeiro aviso sempre sai.
 * Sem dependência do Android, para poder ser testado no computador.
 */
class RitmoAviso(private val relogio: () -> Long = System::currentTimeMillis) {

    private var ultimo = Long.MIN_VALUE

    @Synchronized
    fun liberar(intervaloMs: Long): Boolean {
        val agora = relogio()
        if (ultimo != Long.MIN_VALUE && agora - ultimo < intervaloMs) return false
        ultimo = agora
        return true
    }
}
