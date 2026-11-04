package com.example.rfid_server.sincronizacao

import android.util.Log
import com.example.rfid_server.dados.InventarioDao
import com.example.rfid_server.dados.InventarioEntity
import com.example.rfid_server.erros.TipoErro
import com.example.rfid_server.service.MotivoFalha
import com.example.rfid_server.service.RespostaServidor
import com.example.rfid_server.service.TagService
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Leva ao servidor os inventários guardados no coletor, na ordem certa para cada um:
 * abertura → etiquetas lidas (em lotes) → finalização. Depois de tudo confirmado, apaga do aparelho.
 * Chamado pelo [Sincronizador], que já roda em segundo plano e cuida das novas tentativas.
 */
class SincronizadorInventarios(
    private val dao: InventarioDao,
    private val tagService: TagService,
    /** Guarda um erro para a tela de erros do coletor no servidor (tipo, mensagem). */
    private val registrarErro: (String, String) -> Unit = { _, _ -> },
    private val relogio: () -> Long = System::currentTimeMillis
) {

    /** Processa todos os inventários pendentes. Devolve a falha que deve interromper a sincronização, ou null. */
    fun processar(): RespostaServidor.Falha? {
        val pendentes = dao.comPendencias()
        if (pendentes.isEmpty()) return limpar()

        Log.i(TAG, "Sincronizando ${pendentes.size} inventário(s) guardado(s) no coletor")

        for (inventario in pendentes) {
            val falha = processar(inventario) ?: continue

            // Recusa definitiva deste inventário: registra e segue para os outros, sem travar a fila
            if (falha.motivo == MotivoFalha.REQUISICAO_RECUSADA || falha.motivo == MotivoFalha.SESSAO_INVALIDA) {
                recusado(inventario, falha.mensagem)
                continue
            }

            dao.registrarErro(inventario.id, falha.mensagem)
            return falha
        }

        return limpar()
    }

    private fun limpar(): RespostaServidor.Falha? {
        val apagados = dao.apagarConcluidos()
        if (apagados > 0) Log.i(TAG, "$apagados inventário(s) finalizado(s) e enviado(s) por completo removido(s) do coletor")

        val recusadosApagados = dao.apagarRecusadosAntigos(relogio() - GUARDA_RECUSADOS_MS)
        if (recusadosApagados > 0) Log.i(TAG, "$recusadosApagados inventário(s) recusado(s) pelo servidor há mais de 30 dias removido(s) do coletor")

        return null
    }

    /**
     * O servidor recusou de vez (400/403, sessão inválida). Antes, o inventário ficava na fila para sempre e era
     * recusado de novo a cada sincronização. Agora o coletor desiste depois de [LIMITE_RECUSAS] recusas seguidas
     * espalhadas por pelo menos [PRAZO_DESISTIR_MS]: um problema passageiro do servidor (ex.: uma versão com
     * defeito, corrigida no mesmo dia) não faz perder o inventário.
     */
    private fun recusado(inventario: InventarioEntity, motivo: String) {
        val agora = relogio()
        dao.registrarRecusa(inventario.id, motivo, agora)

        // Relê do banco: a cópia do início da passada pode estar velha (ex.: uma etapa aceita nesta mesma
        // passada zerou as recusas), e contar a partir dela faria o coletor desistir antes da hora
        val atual = dao.inventarioAgora(inventario.id) ?: return
        val recusas = atual.recusas
        val desde = atual.primeiraRecusaEm ?: agora

        if (recusas < LIMITE_RECUSAS || agora - desde < PRAZO_DESISTIR_MS) {
            Log.e(TAG, "Servidor recusou o inventário ${inventario.id} (${inventario.local}): $motivo. " +
                "Fica guardado no coletor e será tentado de novo (recusa $recusas)")
            return
        }

        dao.desistir(inventario.id, agora)
        val mensagem = "Inventário de ${inventario.local} recusado pelo servidor $recusas vezes desde " +
            "${horario(desde)}: $motivo. O coletor parou de enviá-lo; fica guardado no aparelho por 30 dias"
        Log.e(TAG, "$mensagem (inventário ${inventario.id})")
        registrarErro(TipoErro.ENVIO_RECUSADO, mensagem)
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
                    aceito(inventario)
                    Log.i(TAG, "Inventário $id (${inventario.local}) registrado no servidor")
                }
            }
        }

        var enviados = 0
        var recusadas = 0
        // Paginação pelo id: as etiquetas recusadas nesta passada continuam não enviadas e ficam para trás
        var aposId = 0L
        while (true) {
            val lote = dao.itensNaoEnviados(id, aposId, TAMANHO_LOTE)
            if (lote.isEmpty()) break
            aposId = lote.last().id

            // Um operador pode continuar o inventário de outro: cada grupo vai com a sessão de quem leu
            for ((sessao, grupo) in lote.groupBy { it.sessaoOperador }) {
                when (val r = tagService.enviarItensInventarioAgora(id, grupo.map { it.epc to it.lidoEm }, sessao)) {
                    is RespostaServidor.Falha -> {
                        // Recusa só deste grupo (ex.: o operador que leu foi desativado): as etiquetas dos outros
                        // operadores e a finalização seguem. Falha de comunicação para tudo, como antes
                        if (r.motivo != MotivoFalha.REQUISICAO_RECUSADA && r.motivo != MotivoFalha.SESSAO_INVALIDA) return r
                        itensRecusados(inventario, grupo.map { it.id }, r.mensagem, r.motivoSessao)
                        recusadas += grupo.size
                    }
                    RespostaServidor.NaoEncontrado -> return reabrir(inventario)
                    is RespostaServidor.Sucesso -> {
                        dao.marcarItensEnviados(grupo.map { it.id })
                        aceito(inventario)
                        enviados += grupo.size
                    }
                }
            }
        }
        if (enviados > 0) Log.i(TAG, "Inventário $id: $enviados etiqueta(s) enviada(s) ao servidor")
        if (recusadas > 0) Log.w(TAG, "Inventário $id: $recusadas etiqueta(s) recusada(s) pelo servidor ficam guardadas para nova tentativa")

        val finalizadoEm = inventario.finalizadoEm
        if (finalizadoEm != null && !inventario.finalizacaoEnviada) {
            when (val r = tagService.finalizarInventarioAgora(id, finalizadoEm, inventario.sessaoFinalizacao)) {
                is RespostaServidor.Falha -> return r
                RespostaServidor.NaoEncontrado -> return reabrir(inventario)
                is RespostaServidor.Sucesso -> {
                    dao.marcarFinalizacaoEnviada(id)
                    aceito(inventario)
                    Log.i(TAG, "Inventário $id (${inventario.local}) finalizado no servidor")
                }
            }
        }

        return null
    }

    /**
     * O servidor recusou um grupo de etiquetas (mesma sessão). Elas continuam na fila e são tentadas de novo nas
     * próximas passadas: um problema passageiro (ex.: banco do servidor restaurado sem as sessões) não faz perder
     * nada. O coletor só desiste delas com a mesma regra do inventário: [LIMITE_RECUSAS] recusas espalhadas por
     * pelo menos [PRAZO_DESISTIR_MS]. Não mexe no contador do inventário, que é das etapas dele (abertura e finalização).
     * Exceção: se o servidor disser que a sessão de quem leu nunca mais vai valer ([motivoSessao] definitivo, ex.:
     * DESATIVADO), desiste na hora, porque esperar não muda nada.
     */
    private fun itensRecusados(inventario: InventarioEntity, ids: List<Long>, motivo: String, motivoSessao: String?) {
        val agora = relogio()
        dao.registrarRecusaItens(ids, motivo, agora)

        val definitivo = MotivoSessao.definitivo(motivoSessao)
        val desistidas = if (definitivo) dao.desistirItens(ids, 1, agora)
            else dao.desistirItens(ids, LIMITE_RECUSAS, agora - PRAZO_DESISTIR_MS)
        if (desistidas == 0) {
            Log.e(TAG, "Servidor recusou ${ids.size} etiqueta(s) do inventário ${inventario.id} (${inventario.local}): $motivo. " +
                "Ficam guardadas no coletor e serão tentadas de novo")
            return
        }

        val mensagem = "$desistidas etiqueta(s) do inventário de ${inventario.local} recusada(s) pelo servidor" +
            (if (definitivo) " (sessão do operador: $motivoSessao)" else " $LIMITE_RECUSAS vezes ou mais em mais de um dia") +
            ": $motivo. O coletor parou de enviá-las"
        Log.e(TAG, "$mensagem (inventário ${inventario.id})")
        registrarErro(TipoErro.ENVIO_RECUSADO, mensagem)
    }

    /** Alguma etapa foi aceita: as recusas anteriores não eram definitivas. */
    private fun aceito(inventario: InventarioEntity) {
        if (inventario.recusas > 0) dao.zerarRecusas(inventario.id)
    }

    /**
     * O servidor não conhece o inventário (ex.: banco do servidor restaurado de um backup antigo).
     * Marca para abrir de novo na próxima passada, com TODAS as etiquetas (as já enviadas se perderam lá junto
     * com o inventário) e a finalização. Só as etiquetas de que o coletor já desistiu por recusa ficam de fora.
     */
    private fun reabrir(inventario: InventarioEntity): RespostaServidor.Falha {
        val reenviadas = dao.prepararReabertura(inventario.id, LIMITE_RECUSAS)
        Log.w(TAG, "Servidor não conhece o inventário ${inventario.id} (${inventario.local}): será registrado de novo " +
            "na próxima sincronização, com todas as etiquetas ($reenviadas já enviada(s) antes vão de novo)")
        return RespostaServidor.Falha(MotivoFalha.ERRO_SERVIDOR, "Inventário desconhecido no servidor; reenviando")
    }

    companion object {
        private const val TAG = "SINCRONIZACAO"

        /** Etiquetas por requisição (o servidor aceita até 500). */
        internal const val TAMANHO_LOTE = 200

        /** Recusas seguidas, espalhadas por pelo menos [PRAZO_DESISTIR_MS], para o coletor desistir de um inventário. */
        internal const val LIMITE_RECUSAS = 3
        internal const val PRAZO_DESISTIR_MS = 24L * 60 * 60 * 1000

        /** Inventário recusado de vez fica no aparelho por 30 dias (para conferir) e depois é apagado. */
        internal const val GUARDA_RECUSADOS_MS = 30L * 24 * 60 * 60 * 1000

        private val FORMATO_HORARIO = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

        private fun horario(epochMs: Long): String =
            FORMATO_HORARIO.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
    }
}
