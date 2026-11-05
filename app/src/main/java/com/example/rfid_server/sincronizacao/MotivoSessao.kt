package com.example.rfid_server.sincronizacao

/**
 * Motivo que o servidor manda quando recusa a sessão do operador (campo "motivo" do 401 SESSAO_OPERADOR e dos
 * itens RECUSADA do lote). Decide se vale esperar (as leituras ficam na fila por um prazo) ou se a recusa é
 * definitiva (esperar não muda nada).
 */
object MotivoSessao {

    /**
     * Recusas que nunca vão passar: o usuário foi desativado ou excluído, a sessão acabou (Sair, 12 h) ou a ação
     * chegou depois do prazo de sincronização do servidor.
     */
    private val DEFINITIVOS = setOf("ENCERRADA", "EXPIRADA", "ANTIGA_DEMAIS", "USUARIO_INEXISTENTE", "DESATIVADO", "SEM_OPERADOR")

    /**
     * true = pode ser problema do servidor e vale esperar: DESCONHECIDA (banco restaurado, outro servidor),
     * OUTRO_COLETOR (coletor cadastrado com outro nome no servidor novo). Sem motivo (servidor de antes do campo)
     * ou com um motivo que este app não conhece (servidor mais novo), também espera: é o comportamento seguro.
     */
    fun podeVoltar(motivo: String?): Boolean = motivo == null || motivo !in DEFINITIVOS

    /** Recusa de sessão que o servidor garante ser definitiva (só com o campo "motivo" preenchido). */
    fun definitivo(motivo: String?): Boolean = !podeVoltar(motivo)

    /**
     * Motivos que, quando a recusa é da SESSÃO ATUAL do aparelho, querem dizer que ela não serve mais neste servidor:
     * o operador precisa entrar de novo (o app encerra a sessão local). Ficam de fora:
     *  - ANTIGA_DEMAIS: a recusa é da ação (chegou depois do prazo de sincronização), não da sessão, que segue valendo;
     *  - SEM_OPERADOR: a requisição foi sem o cabeçalho da sessão (não diz nada sobre a sessão atual);
     *  - motivo ausente (servidor antigo) ou desconhecido (servidor mais novo): não dá para saber, mantém a sessão.
     */
    private val ENCERRAM_SESSAO_ATUAL = setOf("ENCERRADA", "EXPIRADA", "USUARIO_INEXISTENTE", "DESATIVADO", "DESCONHECIDA", "OUTRO_COLETOR")

    /** A recusa com este [motivo], se for da sessão atual, encerra a sessão local (ver [ENCERRAM_SESSAO_ATUAL]). */
    fun encerraSessaoAtual(motivo: String?): Boolean = motivo != null && motivo in ENCERRAM_SESSAO_ATUAL
}
