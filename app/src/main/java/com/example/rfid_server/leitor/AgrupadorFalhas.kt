package com.example.rfid_server.leitor

/**
 * Limita os registros de um mesmo tipo de falha enviados ao servidor (no máximo um a cada [intervaloMs]) e conta
 * os que foram omitidos, para o próximo registro dizer quantos foram.
 */
class AgrupadorFalhas(
    private val intervaloMs: Long = 60_000L,
    // Relógio monotônico: com o relógio do aparelho voltando (hora acertada), o intervalo travaria os registros
    private val relogio: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private var ultimo = Long.MIN_VALUE
    private var omitidas = 0

    /** null: não registre agora (a ocorrência foi contada). Senão: registre, e o número é de ocorrências omitidas antes. */
    @Synchronized
    fun liberar(): Int? {
        val agora = relogio()
        if (ultimo != Long.MIN_VALUE && agora - ultimo < intervaloMs) {
            omitidas++
            return null
        }
        ultimo = agora
        return omitidas.also { omitidas = 0 }
    }

    companion object {
        /** Complemento da mensagem registrada com as ocorrências omitidas ("" se nenhuma). */
        fun sufixo(omitidas: Int): String =
            if (omitidas <= 0) "" else " (+$omitidas ocorrência(s) desde o registro anterior)"
    }
}
