package com.example.rfid_server.activities

import com.example.rfid_server.sincronizacao.EstadoSincronizacao

/**
 * O que a faixa de sincronização da tela principal oferece ao operador (lógica pura, testável sem Android).
 *
 *  - "Enviar agora": há leituras aguardando e tentar de novo pode resolver (sem conexão, erro do servidor...).
 *  - "Chame o responsável pelo coletor": o problema só se resolve nas Configurações (protegidas por PIN),
 *    então repetir o envio não adianta e o operador não tem o que fazer sozinho.
 *  - Nenhum botão com a sessão recusada pelo servidor: quem resolve é o operador sair e entrar de novo.
 *
 * O aviso do relógio sozinho (sem leituras aguardando) não tem ação: depende de acertar a hora do aparelho.
 */
object RegrasFaixaSincronizacao {

    data class Acoes(val enviarAgora: Boolean, val chamarResponsavel: Boolean)

    fun acoes(pendentes: Int, estado: EstadoSincronizacao?): Acoes {
        if (pendentes <= 0) return Acoes(enviarAgora = false, chamarResponsavel = false)
        val dependeDasConfiguracoes = estado?.dependeDasConfiguracoes == true
        // Sessão recusada pelo servidor: "Enviar agora" iria com a mesma sessão; a própria descrição diz o que fazer
        val resolveEntrandoDeNovo = estado?.resolveEntrandoDeNovo == true
        return Acoes(enviarAgora = !dependeDasConfiguracoes && !resolveEntrandoDeNovo, chamarResponsavel = dependeDasConfiguracoes)
    }
}
