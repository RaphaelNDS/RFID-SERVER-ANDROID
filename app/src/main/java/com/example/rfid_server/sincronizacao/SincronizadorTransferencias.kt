package com.example.rfid_server.sincronizacao

import android.content.Context
import android.util.Log
import com.example.rfid_server.erros.TipoErro
import com.example.rfid_server.service.MotivoFalha
import com.example.rfid_server.service.RespostaServidor
import com.example.rfid_server.service.ResultadoTransferencia
import java.io.File

/**
 * Etapa da sincronização depois das justificativas: envia as transferências pedidas no inventário
 * ([FilaTransferencias]), uma por etiqueta.
 *  - aceita: fica FEITA, com o nº da guia (ou "já estava no local");
 *  - recusa (404 sem cadastro, 409 bem baixado, 400 local desconhecido, 403 sem permissão): fica RECUSADA, com o
 *    motivo do servidor para a aba Lidas, e vai para a tela de erros;
 *  - sessão de quem pediu recusada pelo servidor (encerrada): RECUSADA, para o operador poder pedir de novo;
 *  - erro do servidor: fica PENDENTE para a próxima passada (sai depois de [PRAZO_MS]); erros seguidos param a etapa;
 *  - falha do ambiente (rede, chave, certificado): para a passada, como nas outras etapas.
 */
class SincronizadorTransferencias(
    private val fila: FilaTransferencias,
    private val enviar: (epc: String, local: String, observacao: String?, sessao: String?) -> RespostaServidor<ResultadoTransferencia>,
    private val registrarErro: (String, String) -> Unit = { _, _ -> },
    private val relogio: () -> Long = System::currentTimeMillis
) {

    /** Falha que deve interromper a sincronização, ou null. */
    fun processar(): RespostaServidor.Falha? {
        val descartadas = fila.descartarAntigas(relogio() - PRAZO_MS)
        if (descartadas > 0) {
            Log.w(TAG, "$descartadas transferência(s) descartada(s): o servidor não aceitou em ${PRAZO_MS / 86_400_000} dias")
            registrarErro(TipoErro.ENVIO_RECUSADO, "Transferências de bem descartadas: o servidor não aceitou no prazo")
        }

        val erros = RegraFalhas.Contador()
        var comErro = 0
        for (item in fila.pendentes()) {
            when (val resposta = enviar(item.epc, item.local, item.observacao, item.sessao)) {
                is RespostaServidor.Sucesso -> {
                    erros.deuCerto()
                    val r = resposta.valor
                    fila.concluir(item, r.guia, r.jaEstava)
                    if (r.jaEstava) Log.i(TAG, "Etiqueta ${item.epc} já estava em ${item.local} no servidor: nenhuma guia nova")
                    else Log.i(TAG, "Etiqueta ${item.epc} transferida para ${item.local} no servidor (guia ${r.guia ?: "sem número"})")
                }

                // O servidor manda 404 com o motivo (etiqueta sem cadastro): vem como recusa. Este caso não acontece hoje
                RespostaServidor.NaoEncontrado -> recusar(item, "Etiqueta sem cadastro no servidor")

                is RespostaServidor.Falha -> when {
                    RegraFalhas.paraTudo(resposta) -> return resposta
                    resposta.motivo == MotivoFalha.REQUISICAO_RECUSADA -> recusar(item, resposta.mensagem)
                    // Sessão recusada por um motivo que pode voltar (ex.: DESCONHECIDA depois de restaurar o banco do servidor):
                    // espera, como as leituras, até [ESPERA_SESSAO_MS] desde o pedido
                    resposta.motivo == MotivoFalha.SESSAO_INVALIDA && MotivoSessao.podeVoltar(resposta.motivoSessao) &&
                        relogio() - item.criadoEm < ESPERA_SESSAO_MS ->
                        Log.w(TAG, "Transferência da etiqueta ${item.epc}: sessão recusada (${resposta.motivoSessao ?: "sem motivo"}), tenta de novo depois")
                    // A sessão de quem pediu acabou de vez (fim do turno, saiu, desativado) ou passou do prazo: com ela o
                    // servidor nunca aceita. Recusada, deixa pedir de novo (antes ficava "aguardando envio" 7 dias)
                    resposta.motivo == MotivoFalha.SESSAO_INVALIDA -> recusar(item, MENSAGEM_SESSAO_ENCERRADA)
                    // Erro do servidor: fica para a próxima passada (o prazo vale também). Erros seguidos demais = o
                    // servidor está com problema: para a etapa em vez de esperar o tempo limite de cada pedido
                    else -> {
                        Log.w(TAG, "Transferência da etiqueta ${item.epc} para ${item.local} não enviada agora: ${resposta.mensagem}")
                        comErro++
                        if (erros.falhou(resposta)) {
                            Log.w(TAG, "Erros seguidos ao enviar transferências: o resto fica para a próxima passada")
                            return resposta
                        }
                    }
                }
            }
        }
        // Erro do servidor em algum pedido: a passada não está "tudo enviado" e a nova tentativa é agendada (antes devolvia
        // null e a tela dizia "Tudo enviado" com a transferência parada até a sincronização periódica)
        if (comErro > 0) return RespostaServidor.Falha(MotivoFalha.ERRO_SERVIDOR, "O servidor não conseguiu processar $comErro transferência(s): nova tentativa depois")
        return null
    }

    private fun recusar(item: FilaTransferencias.Item, mensagem: String) {
        fila.recusar(item, mensagem)
        Log.w(TAG, "Servidor recusou a transferência da etiqueta ${item.epc} para ${item.local}: $mensagem")
        registrarErro(TipoErro.ENVIO_RECUSADO, "Servidor recusou a transferência de um bem: $mensagem")
    }

    companion object {
        private const val TAG = "SINCRONIZACAO"
        /** Tempo que um pedido fica na fila (pendente, ou respondido para a aba Lidas mostrar). */
        const val PRAZO_MS = 7L * 24 * 60 * 60 * 1000

        /** Sessão recusada por motivo que pode voltar: espera isto desde o pedido (o mesmo prazo das leituras). */
        const val ESPERA_SESSAO_MS = 24L * 60 * 60 * 1000

        const val MENSAGEM_SESSAO_ENCERRADA = "a sessão de quem pediu terminou antes do envio; entre de novo e peça outra vez"

        @Volatile
        private var fila: FilaTransferencias? = null

        /** A fila do app (um arquivo só, usado pela tela do inventário e pela sincronização). */
        fun fila(context: Context): FilaTransferencias =
            fila ?: synchronized(this) {
                fila ?: FilaTransferencias(File(context.applicationContext.filesDir, "transferencias.json")).also { fila = it }
            }
    }
}
