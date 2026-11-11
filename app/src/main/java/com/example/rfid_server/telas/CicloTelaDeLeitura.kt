package com.example.rfid_server.telas

/**
 * Ordem das chamadas ao entrar e sair de uma tela de leitura (Principal, Inventário, Localizar), sem
 * dependência do Android para poder ser testada no computador. Quem liga isso ao ciclo de vida da tela é
 * a [TelaDeLeitura].
 *
 * Ao retomar: confere a sessão (vencida: a tela já foi mandada para o login e nada mais acontece),
 * liga o leitor para esta tela (se [lerAoRetomar]), roda [aoRetomar] e inicia o aviso de fim de sessão.
 *
 * Ao pausar: roda [antesDePausar] (ex.: sair do modo localização, que precisa da tela ainda dona das
 * leituras), desliga o leitor desta tela, roda [aoPausar] e para o aviso de fim de sessão.
 */
class CicloTelaDeLeitura(
    private val exigirSessao: () -> Boolean,
    private val ligarLeitor: () -> Unit,
    private val desligarLeitor: () -> Unit,
    private val iniciarAvisoSessao: () -> Unit,
    private val pararAvisoSessao: () -> Unit,
    private val lerAoRetomar: () -> Boolean = { true },
    private val aoRetomar: () -> Unit = {},
    private val antesDePausar: () -> Unit = {},
    private val aoPausar: () -> Unit = {}
) {

    fun retomar() {
        if (!exigirSessao()) return
        if (lerAoRetomar()) ligarLeitor()
        aoRetomar()
        iniciarAvisoSessao()
    }

    fun pausar() {
        antesDePausar()
        desligarLeitor()
        aoPausar()
        pararAvisoSessao()
    }
}
