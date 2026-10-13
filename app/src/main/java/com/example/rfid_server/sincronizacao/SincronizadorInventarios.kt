package com.example.rfid_server.sincronizacao

import android.util.Log
import com.example.rfid_server.dados.InventarioDao
import com.example.rfid_server.dados.InventarioEntity
import com.example.rfid_server.service.MotivoFalha
import com.example.rfid_server.service.RespostaServidor
import com.example.rfid_server.service.TagService

/**
 * Leva ao servidor os inventários guardados no coletor, na ordem certa para cada um:
 * abertura → etiquetas lidas (em lotes) → finalização. Depois de tudo confirmado, apaga do aparelho.
 * Chamado pelo [Sincronizador], que já roda em segundo plano e cuida das novas tentativas.
 */
class SincronizadorInventarios(
    private val dao: InventarioDao,
    private val tagService: TagService
) {

    /** Processa todos os inventários pendentes. Devolve a falha que deve interromper a sincronização, ou null. */
    fun processar(): RespostaServidor.Falha? {
        val pendentes = dao.comPendencias()
        if (pendentes.isEmpty()) return null

        Log.i(TAG, "Sincronizando ${pendentes.size} inventário(s) guardado(s) no coletor")

        for (inventario in pendentes) {
            val falha = processar(inventario) ?: continue

            // Recusa definitiva deste inventário: registra e segue para os outros, sem travar a fila
            if (falha.motivo == MotivoFalha.REQUISICAO_RECUSADA || falha.motivo == MotivoFalha.SESSAO_INVALIDA) {
                Log.e(TAG, "Servidor recusou o inventário ${inventario.id} (${inventario.local}): ${falha.mensagem}. Ele fica guardado no coletor")
                dao.registrarErro(inventario.id, falha.mensagem)
                continue
            }

            dao.registrarErro(inventario.id, falha.mensagem)
            return falha
        }

        val apagados = dao.apagarConcluidos()
        if (apagados > 0) Log.i(TAG, "$apagados inventário(s) finalizado(s) e enviado(s) por completo removido(s) do coletor")

        return null
    }

    private fun processar(inventario: InventarioEntity): RespostaServidor.Falha? {
        val id = inventario.id

        if (!inventario.aberturaEnviada) {
            when (val r = tagService.abrirInventarioAgora(id, inventario.local, inventario.iniciadoEm, inventario.sessaoOperador)) {
                is RespostaServidor.Falha -> return r
                // Só aconteceria com servidor sem a API de inventário (o 404 do POST vira Falha no TagService)
                RespostaServidor.NaoEncontrado -> return RespostaServidor.Falha(MotivoFalha.ERRO_SERVIDOR, "Servidor sem suporte a inventário")
                is RespostaServidor.Sucesso -> {
                    dao.definirAberturaEnviada(id, true)
                    Log.i(TAG, "Inventário $id (${inventario.local}) registrado no servidor")
                }
            }
        }

        var enviados = 0
        while (true) {
            val lote = dao.itensNaoEnviados(id, TAMANHO_LOTE)
            if (lote.isEmpty()) break

            // Um operador pode continuar o inventário de outro: cada grupo vai com a sessão de quem leu
            for ((sessao, grupo) in lote.groupBy { it.sessaoOperador }) {
                when (val r = tagService.enviarItensInventarioAgora(id, grupo.map { it.epc to it.lidoEm }, sessao)) {
                    is RespostaServidor.Falha -> return r
                    RespostaServidor.NaoEncontrado -> return reabrir(inventario)
                    is RespostaServidor.Sucesso -> {
                        dao.marcarItensEnviados(grupo.map { it.id })
                        enviados += grupo.size
                    }
                }
            }
        }
        if (enviados > 0) Log.i(TAG, "Inventário $id: $enviados etiqueta(s) enviada(s) ao servidor")

        val finalizadoEm = inventario.finalizadoEm
        if (finalizadoEm != null && !inventario.finalizacaoEnviada) {
            when (val r = tagService.finalizarInventarioAgora(id, finalizadoEm, inventario.sessaoFinalizacao)) {
                is RespostaServidor.Falha -> return r
                RespostaServidor.NaoEncontrado -> return reabrir(inventario)
                is RespostaServidor.Sucesso -> {
                    dao.marcarFinalizacaoEnviada(id)
                    Log.i(TAG, "Inventário $id (${inventario.local}) finalizado no servidor")
                }
            }
        }

        return null
    }

    /**
     * O servidor não conhece o inventário (ex.: banco do servidor restaurado de um backup antigo).
     * Marca para abrir de novo na próxima passada; as etiquetas não enviadas continuam guardadas.
     */
    private fun reabrir(inventario: InventarioEntity): RespostaServidor.Falha {
        Log.w(TAG, "Servidor não conhece o inventário ${inventario.id} (${inventario.local}): será registrado de novo na próxima sincronização")
        dao.definirAberturaEnviada(inventario.id, false)
        return RespostaServidor.Falha(MotivoFalha.ERRO_SERVIDOR, "Inventário desconhecido no servidor; reenviando")
    }

    companion object {
        private const val TAG = "SINCRONIZACAO"

        /** Etiquetas por requisição (o servidor aceita até 500). */
        internal const val TAMANHO_LOTE = 200
    }
}
