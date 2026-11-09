package com.example.rfid_server.sincronizacao

import com.example.rfid_server.service.MotivoFalha
import com.example.rfid_server.service.RespostaServidor

/**
 * Quando uma falha de envio para a sincronização inteira e quando para só o item.
 *
 * - Falhas do ambiente (sem rede, chave do coletor, endereço, certificado): valem para todos os itens, então param
 *   tudo na hora; tentar os outros só repetiria a mesma falha.
 * - Erro do servidor num item (5xx, resposta inválida): pode ser defeito com aquele item só (ex.: um cadastro com um
 *   modelo que o servidor não consegue gravar). Antes ele parava a fila inteira, para sempre, e depois do prazo de
 *   72 h do servidor as outras leituras eram recusadas. Agora o item fica na fila com o erro e os outros seguem.
 *   [LIMITE_ERROS_SEGUIDOS] erros seguidos indicam que o problema é do servidor como um todo: aí para.
 */
internal object RegraFalhas {

    const val LIMITE_ERROS_SEGUIDOS = 3

    fun paraTudo(falha: RespostaServidor.Falha): Boolean = falha.motivo in AMBIENTE

    // MUITAS_TENTATIVAS (429): o servidor bloqueou o coletor inteiro; cada requisição a mais só mantém o bloqueio
    private val AMBIENTE = setOf(
        MotivoFalha.SEM_CONEXAO, MotivoFalha.SERVIDOR_FORA_DO_AR, MotivoFalha.CHAVE_INVALIDA, MotivoFalha.ENDERECO_INVALIDO, MotivoFalha.CERTIFICADO,
        MotivoFalha.MUITAS_TENTATIVAS
    )

    /** Conta os erros seguidos de uma etapa e guarda o primeiro, para devolver no fim. */
    class Contador {
        var primeira: RespostaServidor.Falha? = null
            private set
        private var seguidos = 0

        /** Mais um item com erro. Devolve true se é para parar a etapa (ambiente ou erros seguidos demais). */
        fun falhou(falha: RespostaServidor.Falha): Boolean {
            if (primeira == null) primeira = falha
            if (paraTudo(falha)) return true
            seguidos++
            return seguidos >= LIMITE_ERROS_SEGUIDOS
        }

        fun deuCerto() {
            seguidos = 0
        }
    }
}
