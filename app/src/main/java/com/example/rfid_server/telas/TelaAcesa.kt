package com.example.rfid_server.telas

import android.os.Handler

/**
 * Tela acesa só enquanto o operador lê: ao abrir a tela de leitura e a cada uso do gatilho a tela fica acesa, e
 * [DEPOIS_DA_LEITURA_MS] depois de soltar o gatilho volta a apagar como o Android normal. Antes ficava acesa o
 * tempo todo com Leitura, Inventário ou Localizar abertos, o que gastava de 25% a 40% da bateria no turno.
 * O gatilho não conta como toque na tela para o Android, por isso a tela precisa ser mantida acesa enquanto lê.
 *
 * [ligar] e [desligar] põem e tiram o FLAG_KEEP_SCREEN_ON da janela; tudo na thread do [handler] (principal).
 */
class TelaAcesa(
    private val handler: Handler,
    private val ligar: () -> Unit,
    private val desligar: () -> Unit
) {
    private val apagar = Runnable { desligar() }

    /** Tela aberta (ou voltou para ela): acesa por [DEPOIS_DA_LEITURA_MS], como se tivesse acabado de ler. */
    fun aoAbrir() = aoSoltar()

    /**
     * Gatilho apertado: acesa enquanto lê. Se o "soltar" nunca chegar (leitor caiu com o gatilho apertado),
     * apaga depois de [MAXIMO_LENDO_MS] para não ficar acesa para sempre.
     */
    fun aoApertar() {
        ligar()
        handler.removeCallbacks(apagar)
        handler.postDelayed(apagar, MAXIMO_LENDO_MS)
    }

    /** Gatilho solto: continua acesa por [DEPOIS_DA_LEITURA_MS] (conferir a lista, ler de novo) e depois apaga. */
    fun aoSoltar() {
        ligar()
        handler.removeCallbacks(apagar)
        handler.postDelayed(apagar, DEPOIS_DA_LEITURA_MS)
    }

    /** Tela saiu da frente: não segura mais nada. */
    fun parar() {
        handler.removeCallbacks(apagar)
        desligar()
    }

    companion object {
        const val DEPOIS_DA_LEITURA_MS = 2 * 60_000L
        const val MAXIMO_LENDO_MS = 10 * 60_000L
    }
}
