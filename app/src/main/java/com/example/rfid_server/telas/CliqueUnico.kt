package com.example.rfid_server.telas

import android.os.SystemClock

/**
 * Ignora o segundo toque de um toque duplo (comum com luva) nas ações que abrem outra tela ou um diálogo: sem isso
 * abriam duas telas de Cadastro empilhadas (a de baixo, com os dados antigos, parecia não ter salvo), dois
 * Localizar, duas perguntas de Finalizar... Só na thread principal (cliques).
 */
object CliqueUnico {

    /** Toques com menos que isso desde o último aceito são ignorados. */
    const val INTERVALO_MS = 700L

    private var ultimo: Long? = null

    /** true = pode seguir com a ação; false = é o segundo toque de um toque duplo. */
    fun permitir(agora: Long = SystemClock.elapsedRealtime()): Boolean {
        if (!permitido(ultimo, agora)) return false
        ultimo = agora
        return true
    }

    internal fun permitido(ultimo: Long?, agora: Long): Boolean =
        ultimo == null || agora < ultimo || agora - ultimo >= INTERVALO_MS
}
