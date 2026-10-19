package com.example.rfid_server

/**
 * Regras da reconexão automática do leitor RFID: quanto esperar antes de cada nova tentativa e
 * quais tentativas vão para o log (sem isso, um leitor desligado encheria o log a cada poucos segundos).
 * Sem dependência do Android, para poder ser testado no computador.
 */
object ReconexaoLeitor {

    /** Espera depois de o leitor avisar que desconectou (encaixe, bateria): ele costuma voltar logo. */
    const val ESPERA_APOS_DESCONEXAO_MS = 2_000L

    /** Espera antes da próxima tentativa, depois de [tentativa] tentativas sem sucesso (1, 2, 3...). */
    fun esperaMs(tentativa: Int): Long = when {
        tentativa <= 2 -> 3_000
        tentativa <= 5 -> 5_000
        else -> 10_000
    }

    /** Registra no log as 3 primeiras falhas e depois só 1 a cada 6 (cerca de uma por minuto). */
    fun deveRegistrar(tentativa: Int): Boolean = tentativa <= 3 || tentativa % 6 == 0
}
