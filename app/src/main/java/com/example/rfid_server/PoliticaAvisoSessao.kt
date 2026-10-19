package com.example.rfid_server

/**
 * Quando avisar o operador de que a sessão (12h) vai acabar: com 30 min e de novo com 5 min, uma vez cada
 * por sessão (trocar de tela não repete o aviso). Sem dependência do Android, para os testes.
 */
object PoliticaAvisoSessao {

    /** Minutos antes do fim em que o operador é avisado, do primeiro para o último. */
    val AVISOS_MINUTOS = listOf(30, 5)

    // token da sessão → avisos já dados (minutos)
    private val avisados = mutableMapOf<String, MutableSet<Int>>()

    /**
     * O aviso que vale agora ([AVISOS_MINUTOS]), se ainda não foi dado nesta sessão; null se não há aviso.
     * Já marca como dado. Sessão já terminada: null (a tela volta para o login sozinha).
     */
    @Synchronized
    fun avisoDevido(token: String, expiraEm: Long, agora: Long): Int? {
        val restanteMs = expiraEm - agora
        if (restanteMs <= 0) return null

        val jaDados = avisados.getOrPut(token) { mutableSetOf() }
        // O aviso mais próximo do fim que já chegou (com 4 min restantes, vale o de 5, não o de 30)
        val devido = AVISOS_MINUTOS.filter { restanteMs <= it * 60_000L }.minOrNull() ?: return null
        if (devido in jaDados) return null

        // Dar o de 5 min também conta como dado o de 30 (não mostra dois avisos seguidos)
        jaDados += AVISOS_MINUTOS.filter { it >= devido }
        return devido
    }

    /** "18:30", "2 h 05 min", "12 min" para as mensagens. */
    fun restanteLegivel(restanteMs: Long): String {
        val minutos = (restanteMs / 60_000L).coerceAtLeast(0)
        return if (minutos >= 60) "${minutos / 60} h %02d min".format(minutos % 60) else "$minutos min"
    }

    /** Para os testes. */
    @Synchronized
    internal fun esquecer() = avisados.clear()
}
