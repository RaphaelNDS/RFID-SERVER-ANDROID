package com.example.rfid_server.sincronizacao

import android.util.Log
import com.example.rfid_server.dados.CadastroDao
import com.example.rfid_server.dados.CadastroEntity
import com.example.rfid_server.dados.LeituraDao
import com.example.rfid_server.erros.TipoErro
import com.example.rfid_server.service.MotivoFalha
import com.example.rfid_server.service.RespostaServidor
import com.example.rfid_server.service.TagService

/**
 * Leva ao servidor os cadastros de etiqueta feitos no coletor. Roda ANTES das leituras: assim a
 * leitura de uma etiqueta recém-cadastrada chega ao servidor já como cadastrada (e entra nos relatórios).
 *
 * Sessão do operador recusada: mesma regra das leituras (ver Sincronizador). Se o motivo pode voltar a valer
 * ([MotivoSessao.podeVoltar]), o cadastro fica na fila por [Sincronizador.PRAZO_SESSAO_RECUSADA_MS] desde a primeira
 * recusa da sessão; só depois disso (ou com motivo definitivo) vira RECUSADO.
 */
class SincronizadorCadastros(
    private val cadastros: CadastroDao,
    private val leituras: LeituraDao,
    private val tagService: TagService,
    /** Guarda um erro para o servidor (tipo, mensagem): recusas definitivas de cadastro. */
    private val registrarErro: (String, String) -> Unit = { _, _ -> },
    /**
     * Desde quando cada sessão está sendo recusada. Instância própria dos cadastros: a das leituras esquece as sessões
     * que não têm mais leituras na fila, e isso recomeçaria o prazo dos cadastros a cada passada.
     */
    private val recusasDeSessao: RecusasDeSessao = RecusasDeSessaoEmMemoria(),
    private val relogio: () -> Long = System::currentTimeMillis,
    /** Transação do banco (em produção, runInTransaction; nos testes, direto). Último: os testes passam como lambda final. */
    private val emTransacao: (() -> Unit) -> Unit = { it() }
) {

    /** Devolve a falha que deve interromper a sincronização, ou null. */
    fun processar(): RespostaServidor.Falha? {
        val pendentes = cadastros.pendentes()
        recusasDeSessao.manterSomente(pendentes.mapNotNull { it.sessaoOperador }.toSet())
        if (pendentes.isEmpty()) return null

        Log.i(TAG, "Enviando ${pendentes.size} cadastro(s) de etiqueta feito(s) no coletor")

        val contador = RegraFalhas.Contador()
        // Sessões recusadas nesta passada e ainda no prazo: os outros cadastros delas nem são tentados agora
        val sessoesRetidas = mutableSetOf<String>()
        var retidos = 0
        var motivoRetencao: String? = null

        for (cadastro in pendentes) {
            if (cadastro.sessaoOperador != null && cadastro.sessaoOperador in sessoesRetidas) {
                retidos++
                continue
            }

            when (val resposta = tagService.cadastrarTagAgora(cadastro)) {
                is RespostaServidor.Sucesso -> {
                    contador.deuCerto()
                    cadastro.sessaoOperador?.let { recusasDeSessao.esquecer(it) }
                    // Juntos numa transação: se o app morresse entre os dois, o cadastro iria de novo, o servidor
                    // responderia 409 (já cadastrada) e ele viraria "recusado" mesmo tendo dado certo
                    var atualizadas = 0
                    emTransacao {
                        atualizadas = leituras.marcarCadastradaPorEpc(
                            cadastro.epc, cadastro.codigoInterno, cadastro.tipoNome, cadastro.marcaNome,
                            cadastro.modeloNome, cadastro.patrimonio, cadastro.numeroSerie
                        )
                        cadastros.apagar(cadastro.id)
                    }
                    Log.i(
                        TAG,
                        "Etiqueta ${cadastro.epc} cadastrada no servidor (patrimônio ${cadastro.patrimonio.ifBlank { "—" }}); " +
                            "$atualizadas leitura(s) dela passam a cadastradas"
                    )
                }

                is RespostaServidor.Falha -> when {
                    // Sessão recusada que pode voltar a valer (ex.: banco do servidor restaurado): espera o prazo
                    deveReter(cadastro, resposta) -> {
                        cadastros.registrarErro(cadastro.id, resposta.mensagem)
                        cadastro.sessaoOperador?.let { sessoesRetidas += it }
                        retidos++
                        motivoRetencao = resposta.mensagem
                        Log.w(TAG, "Servidor recusou a sessão do operador do cadastro da etiqueta ${cadastro.epc} (${resposta.mensagem}): " +
                            "fica na fila e será tentado de novo (prazo de ${Sincronizador.PRAZO_SESSAO_RECUSADA_MS / 3_600_000} h desde a primeira recusa da sessão)")
                    }

                    // Recusa deste cadastro: fica guardado com o motivo para o operador corrigir, e a fila segue
                    resposta.motivo == MotivoFalha.REQUISICAO_RECUSADA || resposta.motivo == MotivoFalha.SESSAO_INVALIDA -> {
                        Log.w(TAG, "Cadastro da etiqueta ${cadastro.epc} recusado pelo servidor: ${resposta.mensagem}")
                        cadastros.marcarRecusado(cadastro.id, resposta.mensagem)
                        // Sem o EPC na mensagem: recusas iguais de várias etiquetas viram um registro só
                        registrarErro(TipoErro.ENVIO_RECUSADO, "Servidor recusou um cadastro de etiqueta: ${resposta.mensagem}")
                    }

                    else -> {
                        cadastros.registrarErro(cadastro.id, resposta.mensagem)
                        // Sem rede/chave/certificado, ou o servidor falhando em vários seguidos: para. Erro do servidor só
                        // neste cadastro: ele fica guardado com o erro e os outros seguem (ver RegraFalhas)
                        if (contador.falhou(resposta)) return resposta
                        Log.w(TAG, "Cadastro da etiqueta ${cadastro.epc} ficou na fila (${resposta.mensagem}): seguindo com os outros")
                    }
                }

                RespostaServidor.NaoEncontrado -> Unit // cadastrarTagAgora não devolve NaoEncontrado
            }
        }

        // Cadastros retidos pela sessão: a passada não está "sincronizada" (estado na tela e nova tentativa agendada)
        contador.primeira?.let { return it }
        if (retidos == 0) return null
        return RespostaServidor.Falha(
            MotivoFalha.SESSAO_INVALIDA,
            "$retidos cadastro(s) aguardando: o servidor recusou a sessão do operador ($motivoRetencao)"
        )
    }

    /**
     * Etiquetas com cadastro ainda na fila (erro do servidor ou sessão retida). As leituras delas esperam: enviadas
     * agora iriam como "não cadastrada" e, quando o cadastro passasse, de novo como cadastrada (registro a mais).
     */
    fun epcsAguardando(): Set<String> = cadastros.pendentes().map { it.epc.trim() }.toSet()

    private fun deveReter(cadastro: CadastroEntity, falha: RespostaServidor.Falha): Boolean {
        val sessao = cadastro.sessaoOperador ?: return false
        return falha.motivo == MotivoFalha.SESSAO_INVALIDA && MotivoSessao.podeVoltar(falha.motivoSessao) && dentroDoPrazo(sessao)
    }

    /** A [sessao] foi recusada pela primeira vez há menos do prazo (registra agora se for a primeira). */
    private fun dentroDoPrazo(sessao: String): Boolean {
        val agora = relogio()
        val primeira = recusasDeSessao.primeiraRecusa(sessao) ?: agora.also { recusasDeSessao.registrar(sessao, it) }
        return agora - primeira < Sincronizador.PRAZO_SESSAO_RECUSADA_MS
    }

    companion object {
        private const val TAG = "SINCRONIZACAO"
    }
}
