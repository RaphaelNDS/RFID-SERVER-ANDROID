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
}
