package com.example.rfid_server.sincronizacao

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.rfid_server.sessao.CofreSessoes
import com.example.rfid_server.ExecutorProtegido
import com.example.rfid_server.sessao.SessaoAtual
import com.example.rfid_server.api.UltimaSincronizacaoOk
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [dependeDasConfiguracoes]: só resolve quando alguém mexe nas Configurações do coletor (repetir sozinho não adianta).
 * [resolveEntrandoDeNovo]: quem resolve é o operador sair e entrar de novo ("Enviar agora" não adianta).
 */
enum class EstadoSincronizacao(
    val descricao: String,
    val dependeDasConfiguracoes: Boolean = false,
    val resolveEntrandoDeNovo: Boolean = false
) {
    SINCRONIZADO("Tudo enviado ao servidor"),
    SEM_CONEXAO("Sem conexão com o servidor"),
    /** A porta do servidor está fechada (parado ou reiniciando): não é problema do coletor; tenta de novo sozinho. */
    SERVIDOR_FORA_DO_AR("O servidor não está respondendo (parado ou reiniciando?): as leituras ficam guardadas e vão sozinhas quando ele voltar"),
    CHAVE_INVALIDA("Servidor recusou a chave do coletor", dependeDasConfiguracoes = true),
    ENDERECO_INVALIDO("Endereço do servidor inválido", dependeDasConfiguracoes = true),
    ERRO_SERVIDOR("Erro no servidor"),
    /**
     * A passada terminou com registros retidos porque o servidor recusou a sessão do operador (ver
     * [Sincronizador.PRAZO_SESSAO_RECUSADA_MS]). "Enviar agora" não resolve (iria com a mesma sessão); as novas
     * tentativas continuam (o servidor pode voltar a aceitar, ex.: banco restaurado), mas quem resolve é entrar de novo.
     */
    SESSAO_RECUSADA("O servidor recusou a sessão do operador: saia e entre de novo", resolveEntrandoDeNovo = true),
    /**
     * Registros retidos por sessão recusada, mas nenhum é da sessão ATUAL (sessões anteriores ou de outro operador):
     * sair e entrar de novo não muda nada. Esperam o prazo de 24 h ou o servidor voltar a aceitar, e "Enviar agora"
     * continua valendo (o servidor pode já ter voltado ao normal).
     */
    AGUARDANDO_SESSAO_ANTERIOR("Leituras de uma sessão anterior aguardando o servidor (até 24 h)"),
    CERTIFICADO("Certificado do servidor não confirmado ou diferente: veja as Configurações", dependeDasConfiguracoes = true),
    /**
     * O servidor bloqueou o coletor por muitas tentativas com a chave inválida (HTTP 429). Insistir só mantém o
     * bloqueio: sem "Enviar agora" e sem novas tentativas a cada 30 s; quem resolve é o responsável (chave certa nas
     * Configurações). A verificação periódica de 15 min continua.
     */
    SERVIDOR_BLOQUEOU("O servidor bloqueou o coletor por muitas tentativas com a chave inválida", dependeDasConfiguracoes = true)
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
    /** Etapa 4, depois dos inventários: justificativas dos não encontrados. */
    private val justificativas: SincronizadorJustificativas? = null,
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
     * O servidor recusou a sessão (sessão gravada na leitura, motivo do servidor, mensagem). Em produção,
     * [FimDaSessaoAtual.aoRecusar]: se for a sessão atual do aparelho e o motivo disser que ela não serve mais,
     * encerra a sessão local (o operador entra de novo). Chamado na thread da sincronização.
     */
    private val aoRecusarSessao: (sessao: String, motivoSessao: String?, mensagem: String) -> Unit = { _, _, _ -> },
    /**
     * Roda o bloco depois do atraso em ms (em produção, um ScheduledExecutorService; nos testes, um agendador falso).
     * Usado pelo [solicitarEmBreve].
     */
    private val agendar: (atrasoMs: Long, bloco: () -> Unit) -> Unit = { _, bloco -> bloco() },
    /**
     * A sessão gravada num registro (referência ou token puro) é a sessão atual do aparelho? Decide o estado quando há
     * registros retidos: da sessão atual, "saia e entre de novo" (SESSAO_RECUSADA); só de sessões anteriores ou de
     * outro operador, AGUARDANDO_SESSAO_ANTERIOR. Em produção, [FimDaSessaoAtual.ehAtual].
     */
    private val ehSessaoAtual: (sessao: String) -> Boolean = { true },
    /**
     * Roda o bloco numa transação do banco (em produção, runInTransaction; nos testes, direto). O resultado de um
     * lote é gravado de uma vez: se o app morrer no meio, nenhuma leitura aceita fica na fila para ir de novo.
     */
    private val emTransacao: (() -> Unit) -> Unit = { it() },
    /** Etapa 5, depois das justificativas: transferências de bem para o local do inventário. */
    private val transferencias: SincronizadorTransferencias? = null
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
    // Todas as sessões com leitura retida nesta passada (lote e uma por vez): decidem o estado (ver falhaDaRetencao)
    private val sessoesComRetencao = mutableSetOf<String>()

    // Há um pedido do solicitarEmBreve agendado (os seguintes, até ele rodar, vão junto)
    private val pedidoEmBreve = AtomicBoolean(false)

    /**
     * Pede uma sincronização em segundo plano, na hora (pode chamar de qualquer thread, quantas vezes quiser).
     * Para quem espera o envio já: "Enviar agora", Configurações salvas, tela principal, SincronizacaoWorker.
     */
    fun solicitar() {
        pedidoPendente.set(true)
        executor.execute {
            if (pedidoPendente.getAndSet(false)) processarPendentes()
        }
    }

    /**
     * Pede uma sincronização daqui a [ESPERA_PEDIDO_EM_BREVE_MS], juntando todos os pedidos feitos nesse intervalo numa
     * passada só. Para cada etiqueta nova gravada (leitura e inventário): com o gatilho apertado chegam várias por
     * segundo, e o [solicitar] só junta os pedidos que chegam durante a passada em andamento, então as passadas rodavam
     * uma atrás da outra (dezenas de requisições em poucos segundos).
     */
    fun solicitarEmBreve() {
        if (!pedidoEmBreve.compareAndSet(false, true)) return
        try {
            agendar(ESPERA_PEDIDO_EM_BREVE_MS) {
                pedidoEmBreve.set(false)
                solicitar()
            }
        } catch (e: Exception) {
            // Agendador fora do ar (não deveria acontecer): não deixa o pedido preso, pede na hora
            Log.w(TAG, "Não foi possível agendar a sincronização: pedindo agora", e)
            pedidoEmBreve.set(false)
            solicitar()
        }
    }

    /**
     * Processa a fila agora, bloqueando a thread. Usado pelo [solicitar] e pelo SincronizacaoWorker.
     * Synchronized: o worker e o app podem chamar ao mesmo tempo, e a mesma leitura não pode ir duas vezes.
     *
     * [agendarSeFalhar] = false no SincronizacaoWorker: ele mesmo pede a nova tentativa (Result.retry), e agendar
     * aqui criaria uma segunda tentativa quando quem roda é a periódica.
     */
    /**
     * Roda [bloco] com a mesma trava do [processarPendentes]: nenhuma sincronização acontece ao mesmo tempo (se houver
     * uma em andamento, espera ela terminar). Usado para apagar do coletor algo que pode estar sendo enviado.
     * Chame fora da thread principal.
     */
    @Synchronized
    fun exclusivo(bloco: () -> Unit) = bloco()

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
            for (etapa in listOf({ cadastros?.processar() }, { processarLeituras() }, { inventarios?.processar() }, { justificativas?.processar() }, { transferencias?.processar() }, { falhaDaRetencao() })) {
                val f = etapa() ?: continue
                falhas += f
                // O disjuntor das leituras (servidor recusando tudo) também para: os inventários seriam recusados igual
                if (RegraFalhas.paraTudo(f) || disjuntor.disparou) break
            }
            // Sessão recusada (registros retidos) só vira o estado da passada se não houve outra falha: um erro do
            // servidor numa etapa seguinte é o que o operador precisa ver ("Enviar agora" pode resolver). Entre as
            // retenções, a da sessão atual ("saia e entre de novo") vem antes da de sessões anteriores
            falha = falhas.firstOrNull { RegraFalhas.paraTudo(it) }
                ?: falhas.firstOrNull { it.motivo !in RETENCOES }
                ?: falhas.firstOrNull { it.motivo == MotivoFalha.SESSAO_INVALIDA } ?: falhas.firstOrNull()

            // Erro do servidor (5xx, resposta inválida): vai para a tela de erros do coletor no servidor.
            // Sem conexão, chave, endereço ou certificado não: são do ambiente e já aparecem na tela do coletor
            // De qualquer etapa: a primeira falha pode ser de outro tipo (ex.: cadastros retidos pela sessão)
            falhas.firstOrNull { it.motivo == MotivoFalha.ERRO_SERVIDOR }?.let {
                registrarErro(TipoErro.ERRO_SERVIDOR, "Sincronização interrompida: ${it.mensagem}")
            }
        } catch (e: Throwable) {
            // Passada interrompida por exceção (ex.: banco local cheio): antes a tela ficava com o estado anterior (até
            // "Tudo enviado" com leituras paradas na fila) e a próxima tentativa só vinha na periódica de 15 min
            Log.e(TAG, "Sincronização interrompida por falha no coletor: ${e.javaClass.simpleName}: ${e.message}")
            try {
                atualizarEstado(estadoPara(MotivoFalha.ERRO_SERVIDOR))
                if (agendarSeFalhar) agendarNovaTentativa()
            } catch (outra: Throwable) {
                Log.e(TAG, "Falha ao registrar a interrupção da sincronização", outra)
            }
            throw e
        } finally {
            // Uma vez por passada, mesmo se a passada terminar com exceção (ex.: banco local cheio): os erros guardados
            // vão ao servidor sempre que ele pode estar respondendo. Falha do ambiente (sem rede, chave, endereço,
            // certificado, coletor bloqueado por muitas tentativas) não: o envio daria a mesma falha
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

        // Hora da última passada completa: vai para o servidor no cabeçalho X-Ultima-Sinc-Ok (diagnóstico do coletor)
        UltimaSincronizacaoOk.registrar()
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
        sessoesComRetencao.clear()
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
        val naFila = dao.pendentes()
        recusasDeSessao.manterSomente(naFila.mapNotNull { it.sessaoOperador }.toSet())

        // Etiqueta com cadastro ainda na fila: a leitura dela espera o cadastro passar (senão iria como
        // "não cadastrada" e depois de novo como cadastrada). As já classificadas como cadastradas seguem
        val emCadastro = cadastros?.epcsAguardando().orEmpty()
        val pendentes = if (emCadastro.isEmpty()) naFila else naFila.filterNot {
            it.epc in emCadastro && it.situacao != Situacao.CADASTRADA
        }
        if (pendentes.size < naFila.size) {
            Log.i(TAG, "${naFila.size - pendentes.size} leitura(s) esperando o cadastro da etiqueta ser aceito pelo servidor")
        }
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
        val contadorLotes = RegraFalhas.Contador()
        // Sessão que teve um lote recusado nesta passada: os outros lotes dela vão direto uma por vez (antes cada lote
        // era mandado e recusado de novo, com a mesma espera, a cada passada durante as 24 h da retenção)
        val sessoesComLoteRecusado = mutableSetOf<String>()

        for ((sessao, lote) in lotes) {
            if (sessao in sessoesComLoteRecusado) {
                umaPorVez += lote
                continue
            }
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
                    contadorLotes.deuCerto()
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
                        // Se for a sessão atual e ela não servir mais, o operador volta ao login (as do lote seguem a regra de sempre)
                        if (resposta.motivo == MotivoFalha.SESSAO_INVALIDA) {
                            aoRecusarSessao(sessao, resposta.motivoSessao, resposta.mensagem)
                            sessoesComLoteRecusado += sessao
                        }
                        umaPorVez += lote
                        continue
                    }

                    lote.forEach { dao.registrarErro(it.id, resposta.mensagem) }
                    // Erro do servidor neste lote (ex.: resposta inválida, 500 causado por um dado dele) não segura os
                    // outros lotes nem as leituras uma por vez, como nos itens: só falha do ambiente ou erros seguidos
                    // demais param (RegraFalhas). Antes um lote com problema travava todas as leituras avulsas
                    if (contadorLotes.falhou(resposta)) {
                        Log.w(TAG, "Sincronização interrompida: ${resposta.mensagem}. ${pendentes.size} leitura(s) continuam guardadas no aparelho")
                        return resposta
                    }
                    Log.w(TAG, "Lote de ${lote.size} leitura(s) ficou na fila (${resposta.mensagem}): seguindo com os outros")
                    errosNoServidor += lote.size
                }
            }
        }

        // Ordem original da fila (a mais antiga primeiro)
        umaPorVez.sortBy { it.id }
        var enviadasUmaPorVez = 0
        val contador = RegraFalhas.Contador()
        for ((indice, leitura) in umaPorVez.withIndex()) {
            // Sessão recusada nesta passada e ainda no prazo: as outras leituras dela nem são tentadas agora
            if (leitura.sessaoOperador in sessoesRetidas) {
                leiturasRetidas++
                continue
            }

            val retidasAntes = leiturasRetidas
            val falha = processar(leitura)

            if (falha != null) {
                // Disjuntor (o servidor recusando tudo) e falhas do ambiente param na hora; erro do servidor só
                // nesta leitura: ela fica na fila com o erro e as outras seguem (ver RegraFalhas)
                if (disjuntor.disparou || contador.falhou(falha)) {
                    // Esta e as que ainda não foram tentadas (antes contava só as enviadas, e retidas ou com erro sumiam da conta)
                    val restantes = umaPorVez.size - indice
                    Log.w(TAG, "Sincronização interrompida: ${falha.mensagem}. $restantes leitura(s) continuam guardadas no aparelho")
                    return falha
                }
                Log.w(TAG, "Leitura ${leitura.epc} ficou na fila (${falha.mensagem}): seguindo com as outras")
                errosNoServidor++
                continue
            }

            // Retida pela sessão recusada: ficou na fila, não foi enviada (não zera os erros seguidos nem conta como enviada)
            if (leiturasRetidas != retidasAntes) continue

            // O servidor respondeu (aceitou, ou recusou de vez esta leitura): conta como tratada uma por vez
            contador.deuCerto()
            enviadasUmaPorVez++
        }

        val apagadas = dao.apagarArquivadasConcluidas() + dao.apagarRecusadasAntigas(relogio() - GUARDA_RECUSADAS_MS)
        Log.i(TAG, "Sincronização concluída: ${pendentes.size} leitura(s) processada(s)" +
            (if (lotesEnviados > 0) " ($lotesEnviados lote(s)" + (if (enviadasUmaPorVez > 0) " e $enviadasUmaPorVez tratada(s) uma por vez)" else ")") else "") +
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

        // Sessões recusadas no lote: avisadas DEPOIS da transação, uma vez cada. Dentro dela, cada item recusado decifrava o
        // token, gravava preferências e avisava as telas com o banco travado (o gatilho esperava para gravar a leitura)
        val sessoesRecusadas = LinkedHashMap<String, Pair<String, String>>()

        // Tudo numa transação: o servidor já registrou o lote, e uma gravação pela metade reenviaria leituras aceitas
        emTransacao {
            lote.forEachIndexed { indice, leitura ->
                val resultado = porIndice[indice]

                when (resultado?.status) {
                    StatusLote.CADASTRADA -> {
                        val tag = resultado.tag
                        if (tag != null) {
                            dao.marcarCadastrada(
                                leitura.id, tag.codigoInterno, tag.tipo, tag.marca, tag.modelo, tag.patrimonio, tag.numeroSerie,
                                tag.situacao, tag.situacaoRotulo
                            )
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
                        if (resultado.motivoSessao != null && sessao != null) sessoesRecusadas.putIfAbsent(sessao, resultado.motivoSessao to mensagem)
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
        // Fora da transação: se for a sessão atual e ela não servir mais, o operador volta ao login
        sessoesRecusadas.forEach { (sessao, recusa) -> aoRecusarSessao(sessao, recusa.first, recusa.second) }

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
                        tag.modelo, tag.patrimonio, tag.numeroSerie, tag.situacao, tag.situacaoRotulo
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
        // Sessão atual que não serve mais (ex.: usuário desativado): o operador volta ao login. A leitura segue a regra abaixo
        if (falha.motivo == MotivoFalha.SESSAO_INVALIDA && sessao != null) aoRecusarSessao(sessao, falha.motivoSessao, falha.mensagem)

        // A leitura já chegou ao servidor (pelo lote) e só a consulta da etiqueta foi recusada: não é uma recusa da
        // leitura nem motivo para retê-la. Antes da retenção: senão ela ficava até 24 h presa, contando como retida
        // ("saia e entre de novo") embora o servidor já tivesse a leitura. Sai da fila sem classificação
        if (leitura.envioConcluido &&
            (falha.motivo == MotivoFalha.REQUISICAO_RECUSADA || falha.motivo == MotivoFalha.SESSAO_INVALIDA)
        ) {
            Log.w(TAG, "Tag ${leitura.epc}: leitura já registrada no servidor, mas a consulta da etiqueta foi recusada (${falha.mensagem}): fica sem classificação")
            dao.desistirDaClassificacao(leitura.id, falha.mensagem)
            return null
        }

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
        sessoesComRetencao += sessao
        Log.w(TAG, "Servidor recusou a sessão do operador da tag ${leitura.epc} ($mensagem): " +
            "a leitura fica na fila e será tentada de novo (prazo de ${PRAZO_SESSAO_RECUSADA_MS / 3_600_000} h desde a primeira recusa da sessão)")
    }

    private fun dentroDoPrazo(sessao: String): Boolean {
        val agora = relogio()
        val primeira = recusasDeSessao.primeiraRecusa(sessao) ?: agora.also { recusasDeSessao.registrar(sessao, it) }
        return agora - primeira < PRAZO_SESSAO_RECUSADA_MS
    }

    /**
     * Leituras retidas por sessão recusada: a passada termina como falha (estado na tela e nova tentativa agendada).
     * Só é "a sessão do operador" (SESSAO_INVALIDA → "saia e entre de novo") se alguma retida for da sessão ATUAL; de
     * sessões anteriores ou de outro operador é SESSAO_ANTERIOR_RETIDA (entrar de novo não resolve).
     */
    private fun falhaDaRetencao(): RespostaServidor.Falha? {
        if (leiturasRetidas == 0) return null
        return falhaDeRetencao(sessoesComRetencao, ehSessaoAtual, "$leiturasRetidas leitura(s)", motivoRetencao)
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
        MotivoFalha.SERVIDOR_FORA_DO_AR -> EstadoSincronizacao.SERVIDOR_FORA_DO_AR
        MotivoFalha.CHAVE_INVALIDA -> EstadoSincronizacao.CHAVE_INVALIDA
        MotivoFalha.ENDERECO_INVALIDO -> EstadoSincronizacao.ENDERECO_INVALIDO
        MotivoFalha.ERRO_SERVIDOR, MotivoFalha.REQUISICAO_RECUSADA -> EstadoSincronizacao.ERRO_SERVIDOR
        MotivoFalha.SESSAO_INVALIDA -> EstadoSincronizacao.SESSAO_RECUSADA
        MotivoFalha.SESSAO_ANTERIOR_RETIDA -> EstadoSincronizacao.AGUARDANDO_SESSAO_ANTERIOR
        MotivoFalha.CERTIFICADO -> EstadoSincronizacao.CERTIFICADO
        MotivoFalha.MUITAS_TENTATIVAS -> EstadoSincronizacao.SERVIDOR_BLOQUEOU
    }

    companion object {
        private const val TAG = "SINCRONIZACAO"

        /** Falhas de registros retidos por sessão recusada (não interrompem a passada). */
        private val RETENCOES = setOf(MotivoFalha.SESSAO_INVALIDA, MotivoFalha.SESSAO_ANTERIOR_RETIDA)

        /**
         * Falha de fim de etapa para os registros retidos por sessão recusada ([quantos]: "3 leitura(s)", "1 cadastro(s)").
         * SESSAO_INVALIDA se alguma das [sessoes] retidas for a atual ([ehSessaoAtual]); senão SESSAO_ANTERIOR_RETIDA.
         */
        internal fun falhaDeRetencao(
            sessoes: Set<String>,
            ehSessaoAtual: (String) -> Boolean,
            quantos: String,
            motivo: String?
        ): RespostaServidor.Falha =
            if (sessoes.any(ehSessaoAtual)) {
                RespostaServidor.Falha(MotivoFalha.SESSAO_INVALIDA, "$quantos aguardando: o servidor recusou a sessão do operador ($motivo)")
            } else {
                RespostaServidor.Falha(
                    MotivoFalha.SESSAO_ANTERIOR_RETIDA,
                    "$quantos de sessão anterior aguardando: o servidor recusou a sessão ($motivo); prazo de até " +
                        "${PRAZO_SESSAO_RECUSADA_MS / 3_600_000} h"
                )
            }

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

        /** Quanto o [solicitarEmBreve] espera, juntando os pedidos, antes de pedir a passada. */
        internal const val ESPERA_PEDIDO_EM_BREVE_MS = 2_500L

        @Volatile
        private var instancia: Sincronizador? = null

        fun get(context: Context): Sincronizador =
            instancia ?: synchronized(this) {
                instancia ?: context.applicationContext.let { app ->
                    val banco = BancoLocal.get(app)
                    val tagService = TagService(app)
                    val emTransacao: (() -> Unit) -> Unit = { bloco -> banco.runInTransaction(Runnable { bloco() }) }
                    // Servidor recusou a sessão atual de vez (usuário desativado, sessão encerrada...): volta ao login
                    val fimDaSessao = FimDaSessaoAtual(
                        tokenAtual = { SessaoAtual.token(app) },
                        referenciaDe = { CofreSessoes.get(app).referencia(it) },
                        encerrar = { token, motivo -> SessaoAtual.encerrarPeloServidor(app, token, motivo) }
                    )
                    val aoRecusarSessao: (String, String?, String) -> Unit = { sessao, motivo, mensagem ->
                        fimDaSessao.aoRecusar(sessao, motivo, mensagem)
                    }
                    // Só agenda (o envio roda no executor da sincronização): uma thread basta, e não segura o processo
                    val agendador = Executors.newSingleThreadScheduledExecutor { r ->
                        Thread(r, "agendar-sincronizacao").apply { isDaemon = true }
                    }
                    Sincronizador(
                        dao = banco.leituraDao(),
                        tagService = tagService,
                        executor = ExecutorProtegido.umaThread(app, "sincronizar com o servidor"),
                        agendarNovaTentativa = { SincronizacaoWorker.agendarNovaTentativa(app) },
                        inventarios = SincronizadorInventarios(banco.inventarioDao(), tagService,
                            registrarErro = { tipo, mensagem -> RegistroErros.registrar(app, tipo, mensagem) },
                            aoRecusarSessao = aoRecusarSessao),
                        cadastros = SincronizadorCadastros(
                            banco.cadastroDao(), banco.leituraDao(), tagService,
                            registrarErro = { tipo, mensagem -> RegistroErros.registrar(app, tipo, mensagem) },
                            recusasDeSessao = RecusasDeSessaoGuardadas(app, RecusasDeSessaoGuardadas.ARQUIVO_CADASTROS),
                            aoRecusarSessao = aoRecusarSessao,
                            ehSessaoAtual = fimDaSessao::ehAtual,
                            emTransacao = emTransacao
                        ),
                        justificativas = SincronizadorJustificativas(
                            fila = SincronizadorJustificativas.fila(app),
                            enviar = tagService::enviarJustificativasAgora,
                            registrarErro = { tipo, mensagem -> RegistroErros.registrar(app, tipo, mensagem) }
                        ),
                        registrarErro = { tipo, mensagem -> RegistroErros.registrar(app, tipo, mensagem) },
                        enviarErros = { RegistroErros.enviarPendentes(app) },
                        recusasDeSessao = RecusasDeSessaoGuardadas(app),
                        aoRecusarSessao = aoRecusarSessao,
                        agendar = { atraso, bloco -> agendador.schedule({ bloco() }, atraso, TimeUnit.MILLISECONDS) },
                        ehSessaoAtual = fimDaSessao::ehAtual,
                        emTransacao = emTransacao,
                        transferencias = SincronizadorTransferencias(
                            fila = SincronizadorTransferencias.fila(app),
                            enviar = tagService::transferirAgora,
                            registrarErro = { tipo, mensagem -> RegistroErros.registrar(app, tipo, mensagem) }
                        )
                    )
                }.also { instancia = it }
            }
    }
}
