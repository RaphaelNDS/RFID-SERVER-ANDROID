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
                    // A sessão de quem pediu acabou (fim do turno, saiu, outro login): com ela o servidor nunca aceita.
                    // Antes ficava "aguardando envio" 7 dias sem deixar pedir de novo
                    resposta.motivo == MotivoFalha.SESSAO_INVALIDA -> recusar(item, MENSAGEM_SESSAO_ENCERRADA)
                    // Erro do servidor: fica para a próxima passada (o prazo vale também). Erros seguidos demais = o
                    // servidor está com problema: para a etapa em vez de esperar o tempo limite de cada pedido
                    else -> {
                        Log.w(TAG, "Transferência da etiqueta ${item.epc} para ${item.local} não enviada agora: ${resposta.mensagem}")
                        if (erros.falhou(resposta)) {
                            Log.w(TAG, "Erros seguidos ao enviar transferências: o resto fica para a próxima passada")
                            return null
                        }
                    }
                }
            }
        }
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
