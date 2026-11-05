package com.example.rfid_server.sincronizacao

import android.util.Log

/**
 * O servidor recusou uma sessão durante a sincronização (401 SESSAO_OPERADOR ou item RECUSADA do lote, com o campo
 * "motivo"). Se for a SESSÃO ATUAL do aparelho e o motivo disser que ela não serve mais
 * ([MotivoSessao.encerraSessaoAtual]: usuário desativado ou excluído, sessão encerrada ou expirada no servidor, servidor
 * que não a conhece), encerra a sessão local: senão o operador seguia lendo e tudo ia para recusadas (ou ficava retido)
 * com o mesmo token. As telas abertas voltam ao login (ver AvisoSessao), que mostra o [motivo][encerrar].
 *
 * A fila não muda: as leituras já guardadas seguem a regra de sempre (prazo de 24 h para as que podem voltar a valer).
 * Só as novas, feitas depois de entrar de novo, vão com a sessão nova.
 */
class FimDaSessaoAtual(
    /** Token da sessão atual do aparelho (null = ninguém logado). */
    private val tokenAtual: () -> String?,
    /** Referência que o banco local guarda no lugar do token (CofreSessoes.referencia). */
    private val referenciaDe: (String) -> String,
    /**
     * Encerra a sessão local se o token guardado ainda for [token] (um login novo no meio do caminho não é desfeito) e
     * guarda o motivo do servidor para a tela de login. Devolve true se encerrou.
     */
    private val encerrar: (token: String, motivoSessao: String) -> Boolean
) {

    /**
     * [sessao] é o que está gravado no registro recusado: a referência ("sessao1:...") ou, nos registros de antes da
     * 1.6.1, o token puro. Devolve true se a sessão atual foi encerrada. Nunca lança: um problema aqui não pode
     * interromper a sincronização.
     */
    fun aoRecusar(sessao: String, motivoSessao: String?, mensagem: String): Boolean = try {
        if (!MotivoSessao.encerraSessaoAtual(motivoSessao)) {
            false
        } else {
            val token = tokenAtual()
            if (token == null || (sessao != token && sessao != referenciaDe(token))) {
                false
            } else {
                encerrar(token, motivoSessao!!).also { encerrou ->
                    if (encerrou) {
                        Log.w(TAG, "Servidor recusou a sessão atual do operador ($motivoSessao: $mensagem): sessão encerrada " +
                            "no coletor, é preciso entrar de novo. As leituras já guardadas continuam na fila")
                    }
                }
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Não foi possível conferir se a sessão recusada pelo servidor é a atual", e)
        false
    }

    companion object {
        private const val TAG = "SESSAO"
    }
}
