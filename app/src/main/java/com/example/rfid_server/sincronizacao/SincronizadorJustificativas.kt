package com.example.rfid_server.sincronizacao

import android.content.Context
import android.util.Log
import com.example.rfid_server.erros.TipoErro
import com.example.rfid_server.service.MotivoFalha
import com.example.rfid_server.service.RespostaServidor
import com.example.rfid_server.service.ResultadoJustificativas
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Etapa da sincronização depois dos inventários: envia as justificativas dos não encontrados ([FilaJustificativas]),
 * por inventário e por quem justificou, em lotes de até [MAXIMO_POR_ENVIO].
 *  - aceitas: saem da fila (as "invalidas" do servidor também saem: recusa definitiva, vai para a tela de erros);
 *  - 404 (servidor anterior à 1.5.0, ou o inventário ainda não chegou): ficam, e saem depois de [PRAZO_MS];
 *  - recusa (400/403, ex.: outro coletor): saem, com registro na tela de erros;
 *  - falha do ambiente (rede, chave, certificado): para a passada, como nas outras etapas.
 */
class SincronizadorJustificativas(
    private val fila: FilaJustificativas,
    private val enviar: (inventarioId: String, itens: JSONArray, sessao: String?) -> RespostaServidor<ResultadoJustificativas>,
    private val registrarErro: (String, String) -> Unit = { _, _ -> },
    private val relogio: () -> Long = System::currentTimeMillis
) {

    /** Falha que deve interromper a sincronização, ou null. */
    fun processar(): RespostaServidor.Falha? {
        val descartadas = fila.descartarAntigas(relogio() - PRAZO_MS)
        if (descartadas > 0) {
            Log.w(TAG, "$descartadas justificativa(s) descartada(s): o servidor não aceitou em ${PRAZO_MS / 86_400_000} dias")
            registrarErro(TipoErro.ENVIO_RECUSADO, "Justificativas de inventário descartadas: o servidor não aceitou no prazo")
        }

        val grupos = fila.pendentes().groupBy { it.inventarioId to it.sessao }
        for ((chave, itens) in grupos) {
            val (inventarioId, sessao) = chave
            for (lote in itens.chunked(MAXIMO_POR_ENVIO)) {
                val corpo = JSONArray().apply {
                    lote.forEach { item ->
                        put(JSONObject().put("tag", item.epc).put("motivo", item.motivo.name).apply { item.observacao?.let { put("observacao", it) } })
                    }
                }
                when (val resposta = enviar(inventarioId, corpo, sessao)) {
                    is RespostaServidor.Sucesso -> {
                        fila.remover(inventarioId, lote.map { it.epc })
                        Log.i(TAG, "${resposta.valor.gravadas} justificativa(s) do inventário $inventarioId gravada(s) no servidor")
                        if (resposta.valor.invalidas.isNotEmpty()) {
                            Log.w(TAG, "Servidor recusou ${resposta.valor.invalidas.size} justificativa(s) do inventário $inventarioId: ${resposta.valor.invalidas}")
                            registrarErro(TipoErro.ENVIO_RECUSADO, "Servidor recusou justificativa(s) de inventário (etiqueta ou motivo inválido)")
                        }
                    }

                    RespostaServidor.NaoEncontrado ->
                        Log.i(TAG, "Servidor ainda não aceita as justificativas do inventário $inventarioId (versão antiga ou inventário não enviado): ficam guardadas")

                    is RespostaServidor.Falha -> when {
                        RegraFalhas.paraTudo(resposta) -> return resposta
                        resposta.motivo == MotivoFalha.REQUISICAO_RECUSADA -> {
                            fila.remover(inventarioId, lote.map { it.epc })
                            Log.w(TAG, "Servidor recusou as justificativas do inventário $inventarioId: ${resposta.mensagem}")
                            registrarErro(TipoErro.ENVIO_RECUSADO, "Servidor recusou justificativas de inventário: ${resposta.mensagem}")
                        }
                        // Erro do servidor ou sessão recusada: ficam para a próxima passada (o prazo vale para elas também)
                        else -> Log.w(TAG, "Justificativas do inventário $inventarioId não enviadas agora: ${resposta.mensagem}")
                    }
                }
            }
        }
        return null
    }

    companion object {
        private const val TAG = "SINCRONIZACAO"
        const val MAXIMO_POR_ENVIO = 2000
        /** Tempo máximo na fila sem o servidor aceitar (ex.: servidor ainda sem a rota). */
        const val PRAZO_MS = 7L * 24 * 60 * 60 * 1000

        @Volatile
        private var fila: FilaJustificativas? = null

        /** A fila do app (um arquivo só, usado pela tela do inventário e pela sincronização). */
        fun fila(context: Context): FilaJustificativas =
            fila ?: synchronized(this) {
                fila ?: FilaJustificativas(File(context.applicationContext.filesDir, "justificativas.json")).also { fila = it }
            }
    }
}
