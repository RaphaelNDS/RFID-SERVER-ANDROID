package com.example.rfid_server.sincronizacao

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.rfid_server.dados.BancoLocal
import com.example.rfid_server.dados.LeituraDao
import com.example.rfid_server.dados.LeituraEntity
import com.example.rfid_server.dados.Situacao
import com.example.rfid_server.service.Autoria
import com.example.rfid_server.service.MotivoFalha
import com.example.rfid_server.service.RespostaServidor
import com.example.rfid_server.service.ResultadoLote
import com.example.rfid_server.service.StatusLote
import com.example.rfid_server.service.TagService
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class EstadoSincronizacao(val descricao: String) {
    SINCRONIZADO("Tudo enviado ao servidor"),
    SEM_CONEXAO("Sem conexão com o servidor"),
    CHAVE_INVALIDA("Servidor recusou a chave do coletor"),
    ENDERECO_INVALIDO("Endereço do servidor inválido"),
    ERRO_SERVIDOR("Erro no servidor"),
    CERTIFICADO("Certificado do servidor não confirmado ou diferente: veja as Configurações")
}

/**
 * Leva ao servidor as leituras guardadas no aparelho (tabela "leituras"):
 *  1. consulta cada tag pendente (cadastrada ou não?);
 *  2. envia a leitura (cadastrada, com a hora real em que foi lida) ou a tag não cadastrada.
 *
 * Se o servidor não responder, para na hora (não adianta tentar as outras) e agenda nova
 * tentativa para quando a rede voltar (SincronizacaoWorker). Nada é apagado antes de chegar ao servidor.
 */
class Sincronizador internal constructor(
    private val dao: LeituraDao,
    private val tagService: TagService,
    private val executor: Executor,
    private val agendarNovaTentativa: () -> Unit,
    /** Etapa 3, depois das leituras avulsas: inventários por local. */
    private val inventarios: SincronizadorInventarios? = null,
    /** Etapa 1, antes das leituras: cadastros de etiqueta feitos no coletor. */
    private val cadastros: SincronizadorCadastros? = null
) {

    private val _estado = MutableLiveData<EstadoSincronizacao>()
    val estado: LiveData<EstadoSincronizacao> = _estado

    // Várias leituras seguidas pedem sincronização: junta os pedidos numa única passada
    private val pedidoPendente = AtomicBoolean(false)

    /** Pede uma sincronização em segundo plano (pode chamar de qualquer thread, quantas vezes quiser). */
    fun solicitar() {
        pedidoPendente.set(true)
        executor.execute {
            if (pedidoPendente.getAndSet(false)) processarPendentes()
        }
    }

    /**
     * Processa a fila agora, bloqueando a thread. Usado pelo [solicitar] e pelo SincronizacaoWorker.
     * Synchronized: o worker e o app podem chamar ao mesmo tempo, e a mesma leitura não pode ir duas vezes.
     */
    @Synchronized
    fun processarPendentes(): EstadoSincronizacao {
        // Ordem: cadastros (a leitura de uma etiqueta recém-cadastrada já chega como cadastrada) →
        // leituras avulsas → inventários. Uma falha de comunicação para tudo.
        val falha = cadastros?.processar() ?: processarLeituras() ?: inventarios?.processar()

        if (falha != null) {
            agendarNovaTentativa()
            return atualizarEstado(estadoPara(falha.motivo))
        }

        return atualizarEstado(EstadoSincronizacao.SINCRONIZADO)
    }

    /**
     * Etapa 2: leituras avulsas. Devolve a falha que deve interromper a sincronização, ou null.
     *
     * Leituras com operador vão em lotes de até [TAMANHO_LOTE], um operador por lote (uma requisição por lote,
     * em vez de 2 por tag). Vão uma por vez (rotas individuais): as de antes do login de operador (o servidor
     * só as aceita nessas rotas), as de um lote que o servidor recusou pela sessão (cada uma é conferida
     * sozinha) e todas, se o servidor for de uma versão sem a rota de lote.
     */
    private fun processarLeituras(): RespostaServidor.Falha? {
        val pendentes = dao.pendentes()
        if (pendentes.isEmpty()) return null

        Log.i(TAG, "Sincronização iniciada: ${pendentes.size} leitura(s) pendente(s)")

        val (comOperador, semOperador) = pendentes.partition { it.sessaoOperador != null }
        val lotes = comOperador.groupBy { it.sessaoOperador!! }.flatMap { (sessao, leituras) ->
            leituras.chunked(TAMANHO_LOTE).map { sessao to it }
        }

        val umaPorVez = semOperador.toMutableList()
        var errosNoServidor = 0
        var lotesEnviados = 0
        var loteDisponivel = true

        for ((sessao, lote) in lotes) {
            if (!loteDisponivel) {
                umaPorVez += lote
                continue
            }

            when (val resposta = tagService.enviarLoteAgora(lote.map { it.epc to it.lidoEm }, sessao)) {
                is RespostaServidor.Sucesso -> {
                    errosNoServidor += aplicarResultados(lote, resposta.valor)
                    lotesEnviados++
                }

                RespostaServidor.NaoEncontrado -> {
                    loteDisponivel = false
                    umaPorVez += lote
                }

                is RespostaServidor.Falha -> {
                    // Lote inteiro recusado (sessão que não vale para a leitura mais recente, operador sem permissão):
                    // cada leitura é conferida sozinha, e as recusadas saem da fila sem travar as outras
                    if (resposta.motivo == MotivoFalha.SESSAO_INVALIDA || resposta.motivo == MotivoFalha.REQUISICAO_RECUSADA) {
                        Log.w(TAG, "Servidor recusou um lote de ${lote.size} leitura(s) (${resposta.mensagem}): enviando uma por vez")
                        umaPorVez += lote
                        continue
                    }

                    lote.forEach { dao.registrarErro(it.id, resposta.mensagem) }
                    Log.w(TAG, "Sincronização interrompida: ${resposta.mensagem}. ${pendentes.size} leitura(s) continuam guardadas no aparelho")
                    return resposta
                }
            }
        }

        // Ordem original da fila (a mais antiga primeiro)
        umaPorVez.sortBy { it.id }
        var enviadasUmaPorVez = 0
        for (leitura in umaPorVez) {
            val falha = processar(leitura)

            if (falha != null) {
                val restantes = umaPorVez.size - enviadasUmaPorVez
                Log.w(TAG, "Sincronização interrompida: ${falha.mensagem}. $restantes leitura(s) continuam guardadas no aparelho")
                return falha
            }

            enviadasUmaPorVez++
        }

        val apagadas = dao.apagarArquivadasConcluidas()
        Log.i(TAG, "Sincronização concluída: ${pendentes.size} leitura(s) processada(s)" +
            (if (lotesEnviados > 0) " ($lotesEnviados lote(s)" + (if (enviadasUmaPorVez > 0) " e $enviadasUmaPorVez uma por vez)" else ")") else "") +
            if (apagadas > 0) "; $apagadas leitura(s) de sessões anteriores removidas do aparelho" else "")

        // Falha passageira em algumas leituras: ficam na fila e a nova tentativa é agendada
        if (errosNoServidor > 0) {
            return RespostaServidor.Falha(MotivoFalha.ERRO_SERVIDOR, "O servidor não conseguiu processar $errosNoServidor leitura(s): nova tentativa depois")
        }
        return null
    }

    /** Grava o resultado de cada leitura do lote. Devolve quantas tiveram falha passageira (continuam na fila). */
    private fun aplicarResultados(lote: List<LeituraEntity>, resultados: List<ResultadoLote>): Int {
        val porIndice = resultados.associateBy { it.indice }
        var cadastradas = 0
        var naoCadastradas = 0
        var recusadas = 0
        var erros = 0

        lote.forEachIndexed { indice, leitura ->
            val resultado = porIndice[indice]

            when (resultado?.status) {
                StatusLote.CADASTRADA -> {
                    val tag = resultado.tag
                    if (tag != null) {
                        dao.marcarCadastrada(leitura.id, tag.codigoInterno, tag.tipo, tag.marca, tag.modelo, tag.patrimonio, tag.numeroSerie)
                    }
                    dao.marcarEnviada(leitura.id)
                    cadastradas++
                }

                StatusLote.NAO_CADASTRADA -> {
                    dao.marcarNaoCadastrada(leitura.id)
                    dao.marcarEnviada(leitura.id)
                    naoCadastradas++
                }

                StatusLote.RECUSADA -> {
                    val motivo = resultado.mensagem ?: "Recusada pelo servidor"
                    Log.e(TAG, "Servidor recusou a tag ${leitura.epc} ($motivo): ela não será reenviada")
                    dao.marcarRecusada(leitura.id, motivo)
                    recusadas++
                }

                StatusLote.ERRO, null -> {
                    dao.registrarErro(leitura.id, resultado?.mensagem ?: "Sem resposta do servidor para esta leitura")
                    erros++
                }
            }
        }

        Log.i(TAG, "Lote de ${lote.size} leitura(s) enviado: $cadastradas cadastrada(s), $naoCadastradas não cadastrada(s)" +
            (if (recusadas > 0) ", $recusadas recusada(s)" else "") +
            if (erros > 0) ", $erros com erro no servidor (ficam para a próxima tentativa)" else "")
        return erros
    }

    /** Leva uma leitura até o fim. Devolve a falha que deve interromper a sincronização, ou null. */
    private fun processar(leitura: LeituraEntity): RespostaServidor.Falha? {
        var situacao = leitura.situacao

        // A classificação é gravada assim que chega: a tag aparece na tela mesmo que o envio abaixo falhe
        if (situacao == Situacao.PENDENTE) {
            when (val resposta = tagService.buscarTagAgora(leitura.epc, Autoria(leitura.sessaoOperador, leitura.lidoEm))) {
                is RespostaServidor.Sucesso -> {
                    val tag = resposta.valor
                    Log.i(TAG, "Tag ${leitura.epc} cadastrada: patrimônio ${tag.patrimonio}")
                    dao.marcarCadastrada(
                        leitura.id, tag.codigoInterno, tag.tipo, tag.marca,
                        tag.modelo, tag.patrimonio, tag.numeroSerie
                    )
                    situacao = Situacao.CADASTRADA
                }

                RespostaServidor.NaoEncontrado -> {
                    Log.i(TAG, "Tag ${leitura.epc} não cadastrada")
                    dao.marcarNaoCadastrada(leitura.id)
                    situacao = Situacao.NAO_CADASTRADA
                }

                is RespostaServidor.Falha -> return tratarFalha(leitura, resposta)
            }
        }

        if (!leitura.envioConcluido) {
            val resposta = when (situacao) {
                Situacao.CADASTRADA -> tagService.enviarLeituraAgora(leitura.epc, leitura.lidoEm, leitura.sessaoOperador)
                Situacao.NAO_CADASTRADA -> tagService.enviarNaoCadastradaAgora(leitura.epc, Autoria(leitura.sessaoOperador, leitura.lidoEm))
                else -> RespostaServidor.Sucesso(Unit)
            }

            if (resposta is RespostaServidor.Falha) return tratarFalha(leitura, resposta)

            dao.marcarEnviada(leitura.id)
        }

        return null
    }

    private fun tratarFalha(leitura: LeituraEntity, falha: RespostaServidor.Falha): RespostaServidor.Falha? {

        // Recusa definitiva desta tag (ex.: 400): tentar de novo não adianta e travaria a fila inteira
        // Sessão inválida PARA ESTA leitura (ex.: usuário excluído) também é definitiva: não trava a fila
        if (falha.motivo == MotivoFalha.REQUISICAO_RECUSADA || falha.motivo == MotivoFalha.SESSAO_INVALIDA) {
            Log.e(TAG, "Servidor recusou a tag ${leitura.epc} (${falha.mensagem}): ela não será reenviada")
            dao.marcarRecusada(leitura.id, falha.mensagem)
            return null
        }

        dao.registrarErro(leitura.id, falha.mensagem)
        return falha
    }

    private fun atualizarEstado(novo: EstadoSincronizacao): EstadoSincronizacao {
        _estado.postValue(novo)
        return novo
    }

    private fun estadoPara(motivo: MotivoFalha) = when (motivo) {
        MotivoFalha.SEM_CONEXAO -> EstadoSincronizacao.SEM_CONEXAO
        MotivoFalha.CHAVE_INVALIDA -> EstadoSincronizacao.CHAVE_INVALIDA
        MotivoFalha.ENDERECO_INVALIDO -> EstadoSincronizacao.ENDERECO_INVALIDO
        MotivoFalha.ERRO_SERVIDOR, MotivoFalha.REQUISICAO_RECUSADA, MotivoFalha.SESSAO_INVALIDA -> EstadoSincronizacao.ERRO_SERVIDOR
        MotivoFalha.CERTIFICADO -> EstadoSincronizacao.CERTIFICADO
    }

    companion object {
        private const val TAG = "SINCRONIZACAO"

        /** Leituras por requisição (o servidor aceita até 500). */
        internal const val TAMANHO_LOTE = 100

        @Volatile
        private var instancia: Sincronizador? = null

        fun get(context: Context): Sincronizador =
            instancia ?: synchronized(this) {
                instancia ?: context.applicationContext.let { app ->
                    val banco = BancoLocal.get(app)
                    val tagService = TagService(app)
                    Sincronizador(
                        dao = banco.leituraDao(),
                        tagService = tagService,
                        executor = Executors.newSingleThreadExecutor(),
                        agendarNovaTentativa = { SincronizacaoWorker.agendarNovaTentativa(app) },
                        inventarios = SincronizadorInventarios(banco.inventarioDao(), tagService),
                        cadastros = SincronizadorCadastros(banco.cadastroDao(), banco.leituraDao(), tagService)
                    )
                }.also { instancia = it }
            }
    }
}
