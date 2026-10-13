package com.example.rfid_server.sincronizacao

import android.util.Log
import com.example.rfid_server.dados.CadastroDao
import com.example.rfid_server.dados.LeituraDao
import com.example.rfid_server.service.MotivoFalha
import com.example.rfid_server.service.RespostaServidor
import com.example.rfid_server.service.TagService

/**
 * Leva ao servidor os cadastros de etiqueta feitos no coletor. Roda ANTES das leituras: assim a
 * leitura de uma etiqueta recém-cadastrada chega ao servidor já como cadastrada (e entra nos relatórios).
 */
class SincronizadorCadastros(
    private val cadastros: CadastroDao,
    private val leituras: LeituraDao,
    private val tagService: TagService
) {

    /** Devolve a falha que deve interromper a sincronização, ou null. */
    fun processar(): RespostaServidor.Falha? {
        val pendentes = cadastros.pendentes()
        if (pendentes.isEmpty()) return null

        Log.i(TAG, "Enviando ${pendentes.size} cadastro(s) de etiqueta feito(s) no coletor")

        for (cadastro in pendentes) {
            when (val resposta = tagService.cadastrarTagAgora(cadastro)) {
                is RespostaServidor.Sucesso -> {
                    val atualizadas = leituras.marcarCadastradaPorEpc(
                        cadastro.epc, cadastro.codigoInterno, cadastro.tipoNome, cadastro.marcaNome,
                        cadastro.modeloNome, cadastro.patrimonio, cadastro.numeroSerie
                    )
                    cadastros.apagar(cadastro.id)
                    Log.i(
                        TAG,
                        "Etiqueta ${cadastro.epc} cadastrada no servidor (patrimônio ${cadastro.patrimonio.ifBlank { "—" }}); " +
                            "$atualizadas leitura(s) dela passam a cadastradas"
                    )
                }

                // Recusa deste cadastro: fica guardado com o motivo para o operador corrigir, e a fila segue
                is RespostaServidor.Falha -> if (resposta.motivo == MotivoFalha.REQUISICAO_RECUSADA || resposta.motivo == MotivoFalha.SESSAO_INVALIDA) {
                    Log.w(TAG, "Cadastro da etiqueta ${cadastro.epc} recusado pelo servidor: ${resposta.mensagem}")
                    cadastros.marcarRecusado(cadastro.id, resposta.mensagem)
                } else {
                    cadastros.registrarErro(cadastro.id, resposta.mensagem)
                    return resposta
                }

                RespostaServidor.NaoEncontrado -> Unit // cadastrarTagAgora não devolve NaoEncontrado
            }
        }

        return null
    }

    companion object {
        private const val TAG = "SINCRONIZACAO"
    }
}
