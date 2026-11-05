package com.example.rfid_server.sincronizacao

import com.example.rfid_server.ExecutorProtegido
import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.rfid_server.dados.BancoLocal
import com.example.rfid_server.dados.LeituraDao
import com.example.rfid_server.dados.LeituraEntity
import com.example.rfid_server.dados.Situacao
import com.example.rfid_server.erros.RegistroErros
import com.example.rfid_server.erros.TipoErro
import com.example.rfid_server.service.Autoria
import com.example.rfid_server.service.MotivoFalha
import com.example.rfid_server.service.RespostaServidor
import com.example.rfid_server.service.ResultadoLote
import com.example.rfid_server.service.StatusLote
import com.example.rfid_server.service.TagService
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** [dependeDasConfiguracoes]: só resolve quando alguém mexe nas Configurações do coletor (repetir sozinho não adianta). */
enum class EstadoSincronizacao(val descricao: String, val dependeDasConfiguracoes: Boolean = false) {
    SINCRONIZADO("Tudo enviado ao servidor"),
    SEM_CONEXAO("Sem conexão com o servidor"),
    CHAVE_INVALIDA("Servidor recusou a chave do coletor", dependeDasConfiguracoes = true),
    ENDERECO_INVALIDO("Endereço do servidor inválido", dependeDasConfiguracoes = true),
    ERRO_SERVIDOR("Erro no servidor"),
    CERTIFICADO("Certificado do servidor não confirmado ou diferente: veja as Configurações", dependeDasConfiguracoes = true)
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
    private val cadastros: SincronizadorCadastros? = null,
    /** Guarda um erro para o servidor (tipo, mensagem): recusas definitivas e erros do servidor. */
    private val registrarErro: (String, String) -> Unit = { _, _ -> },
    /**
     * Envia ao servidor os erros do app guardados: no fim de cada passada, menos quando a falha é do ambiente (sem
     * rede, chave, endereço, certificado). Também quando a passada termina com exceção.
     */
    private val enviarErros: () -> Unit = {},
    private val relogio: () -> Long = System::currentTimeMillis,
    /** Desde quando cada sessão está sendo recusada pelo servidor (as leituras dela esperam um prazo antes de virar recusadas). */
    private val recusasDeSessao: RecusasDeSessao = RecusasDeSessaoEmMemoria(),
    /**
     * Roda o bloco numa transação do banco (em produção, runInTransaction; nos testes, direto). O resultado de um
     * lote é gravado de uma vez: se o app morrer no meio, nenhuma leitura aceita fica na fila para ir de novo.
     */
    private val emTransacao: (() -> Unit) -> Unit = { it() }
) {

    private val _estado = MutableLiveData<EstadoSincronizacao>()
    val estado: LiveData<EstadoSincronizacao> = _estado

    // Várias leituras seguidas pedem sincronização: junta os pedidos numa única passada
    private val pedidoPendente = AtomicBoolean(false)

    // Um por passada (só a thread do executor mexe nele)
    private var disjuntor = DisjuntorRecusas()

    // Recusas da passada (id, motivo): viram aviso para o servidor só no fim, quando se sabe se o disjuntor disparou
    private val avisosDeRecusa = mutableListOf<Pair<Long, String>>()

    // Sessões recusadas pelo servidor ainda dentro do prazo: as leituras delas ficam na fila (só a thread do executor mexe)
    private val sessoesRetidas = mutableSetOf<String>()
    private var leiturasRetidas = 0
    private var motivoRetencao: String? = null

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
     *
     * [agendarSeFalhar] = false no SincronizacaoWorker: ele mesmo pede a nova tentativa (Result.retry), e agendar
     * aqui criaria uma segunda tentativa quando quem roda é a periódica.
     */
    @Synchronized
    fun processarPendentes(agendarSeFalhar: Boolean = true): EstadoSincronizacao {
        // Ordem: cadastros (a leitura de uma etiqueta recém-cadastrada já chega como cadastrada) →
        // leituras avulsas → inventários. Uma falha do ambiente (rede, chave, endereço, certificado) para tudo;
        // erro do servidor numa etapa não impede as seguintes (um cadastro com defeito não segura as leituras).
        // Leituras retidas por sessão recusada não impedem os inventários, mas a passada não está "sincronizada"
        disjuntor = DisjuntorRecusas()
        val falhas = mutableListOf<RespostaServidor.Falha>()
        var falha: RespostaServidor.Falha? = null
        try {
            for (etapa in listOf({ cadastros?.processar() }, { processarLeituras() }, { inventarios?.processar() }, { falhaDaRetencao() })) {
                val f = etapa() ?: continue
                falhas += f
                // O disjuntor das leituras (servidor recusando tudo) também para: os inventários seriam recusados igual
                if (RegraFalhas.paraTudo(f) || disjuntor.disparou) break
            }
            falha = falhas.firstOrNull { RegraFalhas.paraTudo(it) } ?: falhas.firstOrNull()

            // Erro do servidor (5xx, resposta inválida): vai para a tela de erros do coletor no servidor.
            // Sem conexão, chave, endereço ou certificado não: são do ambiente e já aparecem na tela do coletor
            if (falha?.motivo == MotivoFalha.ERRO_SERVIDOR) registrarErro(TipoErro.ERRO_SERVIDOR, "Sincronização interrompida: ${falha.mensagem}")
        } finally {
            // Uma vez por passada, mesmo se a passada terminar com exceção (ex.: banco local cheio): os erros guardados
            // vão ao servidor sempre que ele pode estar respondendo. Falha do ambiente (sem rede, chave, endereço,
            // certificado) não: o envio daria a mesma falha
            if (falhas.none { RegraFalhas.paraTudo(it) }) {
                try {
                    enviarErros()
                } catch (e: Throwable) {
                    // Não esconde a exceção da passada (se houver) nem o resultado
                    Log.e(TAG, "Falha ao enviar os erros do app guardados", e)
                }
            }
        }

        if (falha != null) {
            val estado = estadoPara(falha.motivo)
            // Chave, endereço e certificado só mudam pelas Configurações (que pedem a sincronização ao salvar);
            // tentar de novo antes disso só acordaria o coletor à toa. A periódica de 15 min continua de rede de segurança
            if (agendarSeFalhar && !estado.dependeDasConfiguracoes) agendarNovaTentativa()
            return atualizarEstado(estado)
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
        disjuntor = DisjuntorRecusas()
        avisosDeRecusa.clear()
        sessoesRetidas.clear()
        leiturasRetidas = 0
        motivoRetencao = null

        val falha = enviarLeituras()

        // As que o disjuntor devolveu à fila não eram recusas de verdade: não viram aviso
        val devolvidas = if (disjuntor.disparou) disjuntor.idsDaSequencia().toSet() else emptySet()
        avisosDeRecusa.filter { (id, _) -> id !in devolvidas }.forEach { (_, motivo) ->
            // Sem o EPC na mensagem: recusas iguais de várias tags viram um registro só, com o número de vezes
            registrarErro(TipoErro.ENVIO_RECUSADO, "Servidor recusou uma leitura: $motivo")
        }
        return falha
    }

    private fun enviarLeituras(): RespostaServidor.Falha? {
        val pendentes = dao.pendentes()
        recusasDeSessao.manterSomente(pendentes.mapNotNull { it.sessaoOperador }.toSet())
        if (pendentes.isEmpty()) return null

        Log.i(TAG, "Sincronização iniciada: ${pendentes.size} leitura(s) pendente(s)")

        // Já enviadas que só esperam a classificação (ex.: aceitas no lote com status desconhecido) vão uma por vez:
        // lá só se consulta a tag. No lote seriam registradas de novo
        val (comOperador, semOperador) = pendentes.partition { it.sessaoOperador != null && !it.envioConcluido }
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
                    val retidasAntes = leiturasRetidas
                    errosNoServidor += aplicarResultados(lote, resposta.valor)
                    // Lote aceito: a sessão vale e a recusa anterior dela é esquecida. Menos se algum item deste lote
                    // foi retido pela sessão (motivo que pode voltar): aí o prazo de 24 h continua contando de onde
                    // estava. Esquecer antes de aplicar o resultado recomeçava o prazo a cada passada, para sempre
                    if (leiturasRetidas == retidasAntes) recusasDeSessao.esquecer(sessao)
                    lotesEnviados++
                    if (disjuntor.disparou) return dispararDisjuntor()
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
        val contador = RegraFalhas.Contador()
        for (leitura in umaPorVez) {
            // Sessão recusada nesta passada e ainda no prazo: as outras leituras dela nem são tentadas agora
            if (leitura.sessaoOperador in sessoesRetidas) {
                leiturasRetidas++
                continue
            }

            val falha = processar(leitura)

            if (falha != null) {
                // Disjuntor (o servidor recusando tudo) e falhas do ambiente param na hora; erro do servidor só
                // nesta leitura: ela fica na fila com o erro e as outras seguem (ver RegraFalhas)
                if (disjuntor.disparou || contador.falhou(falha)) {
                    val restantes = umaPorVez.size - enviadasUmaPorVez
                    Log.w(TAG, "Sincronização interrompida: ${falha.mensagem}. $restantes leitura(s) continuam guardadas no aparelho")
                    return falha
                }
                Log.w(TAG, "Leitura ${leitura.epc} ficou na fila (${falha.mensagem}): seguindo com as outras")
                errosNoServidor++
                continue
            }

            contador.deuCerto()
            enviadasUmaPorVez++
        }

        val apagadas = dao.apagarArquivadasConcluidas() + dao.apagarRecusadasAntigas(relogio() - GUARDA_RECUSADAS_MS)
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
        var aceitas = 0
        var recusadas = 0
        var erros = 0

        // Tudo numa transação: o servidor já registrou o lote, e uma gravação pela metade reenviaria leituras aceitas
        emTransacao {
            lote.forEachIndexed { indice, leitura ->
                val resultado = porIndice[indice]

                when (resultado?.status) {
                    StatusLote.CADASTRADA -> {
                        val tag = resultado.tag
                        if (tag != null) {
                            dao.marcarCadastrada(leitura.id, tag.codigoInterno, tag.tipo, tag.marca, tag.modelo, tag.patrimonio, tag.numeroSerie)
                        }
                        dao.marcarEnviada(leitura.id)
                        disjuntor.aceita()
                        cadastradas++
                    }

                    StatusLote.NAO_CADASTRADA -> {
                        dao.marcarNaoCadastrada(leitura.id)
                        dao.marcarEnviada(leitura.id)
                        disjuntor.aceita()
                        naoCadastradas++
                    }

                    // Registrada pelo servidor, com status que este app não conhece: sai da fila sem mudar a situação
                    // (se ainda estiver PENDENTE, a próxima passada só consulta a tag, sem enviar de novo)
                    StatusLote.ACEITA -> {
                        dao.marcarEnviada(leitura.id)
                        disjuntor.aceita()
                        aceitas++
                    }

                    StatusLote.RECUSADA -> {
                        val mensagem = resultado.mensagem ?: "Recusada pelo servidor"
                        val sessao = leitura.sessaoOperador
                        // Recusa pela sessão que o servidor diz poder voltar (ex.: DESCONHECIDA): espera como no 401.
                        // Só com o "motivo" preenchido: no servidor antigo a recusa de item no lote é sempre definitiva
                        if (resultado.motivoSessao != null && sessao != null &&
                            MotivoSessao.podeVoltar(resultado.motivoSessao) && dentroDoPrazo(sessao)
                        ) {
                            reter(leitura, sessao, mensagem)
                        } else {
                            // Recusa pela sessão não é problema do servidor: não conta no disjuntor (como no 401)
                            recusar(leitura, mensagem, contaNoDisjuntor = resultado.motivoSessao == null)
                            recusadas++
                        }
                    }

                    StatusLote.ERRO, null -> {
                        dao.registrarErro(leitura.id, resultado?.mensagem ?: "Sem resposta do servidor para esta leitura")
                        erros++
                    }
                }
            }
        }

        Log.i(TAG, "Lote de ${lote.size} leitura(s) enviado: $cadastradas cadastrada(s), $naoCadastradas não cadastrada(s)" +
            (if (aceitas > 0) ", $aceitas aceita(s) sem classificação" else "") +
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

        leitura.sessaoOperador?.let { recusasDeSessao.esquecer(it) }
        disjuntor.aceita()
        return null
    }

    private fun tratarFalha(leitura: LeituraEntity, falha: RespostaServidor.Falha): RespostaServidor.Falha? {

        // Sessão recusada há menos de [PRAZO_SESSAO_RECUSADA_MS]: pode ser problema do servidor (banco restaurado,
        // servidor novo), então a leitura fica na fila. As outras leituras da sessão esperam a próxima passada.
        // Se o servidor disser que a recusa é definitiva (ex.: DESATIVADO, EXPIRADA), não espera
        val sessao = leitura.sessaoOperador
        if (falha.motivo == MotivoFalha.SESSAO_INVALIDA && sessao != null &&
            MotivoSessao.podeVoltar(falha.motivoSessao) && dentroDoPrazo(sessao)
        ) {
            reter(leitura, sessao, falha.mensagem)
            sessoesRetidas += sessao
            return null
        }

        // Recusa definitiva desta tag (ex.: 400): tentar de novo não adianta e travaria a fila inteira
        // Sessão inválida PARA ESTA leitura, recusada há mais que o prazo (ex.: usuário excluído), também é definitiva
        if (falha.motivo == MotivoFalha.REQUISICAO_RECUSADA || falha.motivo == MotivoFalha.SESSAO_INVALIDA) {
            // Sessão inválida não conta no disjuntor: as leituras de um usuário desativado nunca vão passar,
            // e devolvê-las à fila travaria as dos outros operadores
            val disparou = recusar(leitura, falha.mensagem, contaNoDisjuntor = falha.motivo == MotivoFalha.REQUISICAO_RECUSADA)
            return if (disparou) dispararDisjuntor() else null
        }

        dao.registrarErro(leitura.id, falha.mensagem)
        return falha
    }

    /**
     * Recusa definitiva: a leitura sai da fila, mas fica no aparelho marcada (aparece em "Recusadas").
     * Devolve true se, com esta, o disjuntor disparou (o servidor parece estar recusando tudo).
     * Só leituras recentes contam: uma recusa que persiste além de [JANELA_DISJUNTOR_MS] é tratada como de verdade,
     * senão uma regra do servidor que recuse mesmo várias leituras seguidas travaria a fila para sempre.
     */
    private fun recusar(leitura: LeituraEntity, motivo: String, contaNoDisjuntor: Boolean): Boolean {
        Log.e(TAG, "Servidor recusou a tag ${leitura.epc} ($motivo): ela não será reenviada")
        dao.marcarRecusada(leitura.id, motivo)

        val recente = relogio() - leitura.lidoEm < JANELA_DISJUNTOR_MS
        avisosDeRecusa += leitura.id to motivo
        return contaNoDisjuntor && recente && disjuntor.recusada(leitura.id, motivo)
    }

    /** A [sessao] foi recusada pela primeira vez há menos de [PRAZO_SESSAO_RECUSADA_MS] (registra agora se for a primeira). */
    /** A leitura fica na fila (sem virar recusada) porque a sessão dela foi recusada e ainda pode voltar a valer. */
    private fun reter(leitura: LeituraEntity, sessao: String, mensagem: String) {
        dao.registrarErro(leitura.id, mensagem)
        leiturasRetidas++
        motivoRetencao = mensagem
        Log.w(TAG, "Servidor recusou a sessão do operador da tag ${leitura.epc} ($mensagem): " +
            "a leitura fica na fila e será tentada de novo (prazo de ${PRAZO_SESSAO_RECUSADA_MS / 3_600_000} h desde a primeira recusa da sessão)")
    }

    private fun dentroDoPrazo(sessao: String): Boolean {
        val agora = relogio()
        val primeira = recusasDeSessao.primeiraRecusa(sessao) ?: agora.also { recusasDeSessao.registrar(sessao, it) }
        return agora - primeira < PRAZO_SESSAO_RECUSADA_MS
    }

    /** Leituras retidas por sessão recusada: a passada termina como falha (estado na tela e nova tentativa agendada). */
    private fun falhaDaRetencao(): RespostaServidor.Falha? {
        if (leiturasRetidas == 0) return null
        return RespostaServidor.Falha(
            MotivoFalha.SESSAO_INVALIDA,
            "$leiturasRetidas leitura(s) aguardando: o servidor recusou a sessão do operador ($motivoRetencao)"
        )
    }

    /** As recusadas em sequência voltam para a fila e a sincronização para (o erro é registrado em processarPendentes). */
    private fun dispararDisjuntor(): RespostaServidor.Falha {
        val ids = disjuntor.idsDaSequencia()
        dao.desfazerRecusas(ids)
        val mensagem = "O servidor recusou ${ids.size} leituras seguidas pelo mesmo motivo (${disjuntor.mensagem}): " +
            "sincronização pausada, as leituras voltaram para a fila"
        Log.e(TAG, mensagem)
        return RespostaServidor.Falha(MotivoFalha.ERRO_SERVIDOR, mensagem)
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

        /** Recusadas ficam no aparelho (e na contagem) por 30 dias depois de lidas. */
        internal const val GUARDA_RECUSADAS_MS = 30L * 24 * 60 * 60 * 1000

        /** Até quanto tempo depois de lida uma leitura recusada ainda conta no disjuntor. */
        internal const val JANELA_DISJUNTOR_MS = 24L * 60 * 60 * 1000

        /**
         * Por quanto tempo, desde a primeira recusa, as leituras de uma sessão recusada pelo servidor ficam na fila
         * antes de virar recusadas. Dá tempo de corrigir o servidor (ex.: banco restaurado errado na migração).
         */
        internal const val PRAZO_SESSAO_RECUSADA_MS = 24L * 60 * 60 * 1000

        @Volatile
        private var instancia: Sincronizador? = null

        fun get(context: Context): Sincronizador =
            instancia ?: synchronized(this) {
                instancia ?: context.applicationContext.let { app ->
                    val banco = BancoLocal.get(app)
                    val tagService = TagService(app)
                    val emTransacao: (() -> Unit) -> Unit = { bloco -> banco.runInTransaction(Runnable { bloco() }) }
                    Sincronizador(
                        dao = banco.leituraDao(),
                        tagService = tagService,
                        executor = ExecutorProtegido.umaThread(app, "sincronizar com o servidor"),
                        agendarNovaTentativa = { SincronizacaoWorker.agendarNovaTentativa(app) },
                        inventarios = SincronizadorInventarios(banco.inventarioDao(), tagService,
                            registrarErro = { tipo, mensagem -> RegistroErros.registrar(app, tipo, mensagem) }),
                        cadastros = SincronizadorCadastros(
                            banco.cadastroDao(), banco.leituraDao(), tagService,
                            registrarErro = { tipo, mensagem -> RegistroErros.registrar(app, tipo, mensagem) },
                            recusasDeSessao = RecusasDeSessaoGuardadas(app, RecusasDeSessaoGuardadas.ARQUIVO_CADASTROS),
                            emTransacao = emTransacao
                        ),
                        registrarErro = { tipo, mensagem -> RegistroErros.registrar(app, tipo, mensagem) },
                        enviarErros = { RegistroErros.enviarPendentes(app) },
                        recusasDeSessao = RecusasDeSessaoGuardadas(app),
                        emTransacao = emTransacao
                    )
                }.also { instancia = it }
            }
    }
}
