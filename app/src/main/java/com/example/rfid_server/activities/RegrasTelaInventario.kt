package com.example.rfid_server.activities

/** Decisões de apresentação da tela de inventário em andamento (testadas no computador). */
object RegrasTelaInventario {

    /**
     * A instrução "Aperte o gatilho…" só aparece até a primeira leitura: depois disso o operador já sabe o que
     * fazer, e a linha vira espaço para a lista do que falta.
     */
    fun mostrarInstrucao(totalLidas: Int): Boolean = totalLidas == 0
}
