package com.example.rfid_server.dados

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/**
 * LeituraDao em memória, com as mesmas regras das queries do Room, para testar
 * Sincronizador e RepositorioLeituras sem aparelho. Os LiveData precisam da InstantTaskExecutorRule.
 */
class FakeLeituraDao(vararg iniciais: LeituraEntity) : LeituraDao {

    val leituras = mutableListOf<LeituraEntity>()
    private var proximoId = 1L

    /** Quantas vezes a fila foi lida (= quantas passadas de sincronização rodaram). */
    var consultasPendentes = 0
        private set

    private val cadastradas = MutableLiveData<List<LeituraEntity>>()
    private val naoCadastradas = MutableLiveData<List<LeituraEntity>>()
    private val aguardando = MutableLiveData<List<LeituraEntity>>()
    private val abaLeitura = MutableLiveData<List<LeituraEntity>>()
    private val pendentesCount = MutableLiveData<Int>()
    private val contagemSessao = MutableLiveData<ContagemSessao>()

    init {
        iniciais.forEach { inserir(it) }
    }

    fun porEpc(epc: String) = leituras.single { it.epc == epc }

    private fun ehPendente(l: LeituraEntity) = l.situacao == Situacao.PENDENTE || !l.envioConcluido

    private fun alterar(id: Long, mudanca: (LeituraEntity) -> LeituraEntity) {
        val i = leituras.indexOfFirst { it.id == id }
        if (i >= 0) leituras[i] = mudanca(leituras[i])
        publicar()
    }

    private fun publicar() {
        cadastradas.value = leituras.filter { !it.arquivada && it.situacao == Situacao.CADASTRADA }.sortedByDescending { it.id }
        naoCadastradas.value = leituras.filter { !it.arquivada && it.situacao == Situacao.NAO_CADASTRADA }.sortedByDescending { it.id }
        aguardando.value = leituras.filter { !it.arquivada && it.situacao == Situacao.PENDENTE }.sortedByDescending { it.id }
        abaLeitura.value = leituras.filter { !it.arquivada && (it.situacao == Situacao.CADASTRADA || it.situacao == Situacao.PENDENTE) }
            .sortedByDescending { it.id }
        pendentesCount.value = leituras.count(::ehPendente)

        val sessao = leituras.filter { !it.arquivada }
        contagemSessao.value = ContagemSessao(
            lidas = sessao.size,
            cadastradas = sessao.count { it.situacao == Situacao.CADASTRADA },
            naoCadastradas = sessao.count { it.situacao == Situacao.NAO_CADASTRADA },
            aguardandoServidor = sessao.count { it.situacao == Situacao.PENDENTE },
            recusadas = sessao.count { it.recusada }
        )
    }

    override fun inserir(leitura: LeituraEntity): Long {
        val id = if (leitura.id == 0L) proximoId++ else leitura.id.also { proximoId = maxOf(proximoId, it + 1) }
        leituras += leitura.copy(id = id)
        publicar()
        return id
    }

    override fun marcarCadastrada(
        id: Long, codigoInterno: String, tipo: String?, marca: String?,
        modelo: String?, patrimonio: String, numeroSerie: String
    ) = alterar(id) {
        it.copy(
            situacao = Situacao.CADASTRADA, codigoInterno = codigoInterno, tipo = tipo, marca = marca,
            modelo = modelo, patrimonio = patrimonio, numeroSerie = numeroSerie, ultimoErro = null
        )
    }

    override fun marcarCadastradaPorEpc(
        epc: String, codigoInterno: String, tipo: String?, marca: String?,
        modelo: String?, patrimonio: String, numeroSerie: String
    ): Int {
        var n = 0
        leituras.replaceAll {
            if (it.epc == epc && it.situacao != Situacao.CADASTRADA) {
                n++
                it.copy(
                    situacao = Situacao.CADASTRADA, codigoInterno = codigoInterno, tipo = tipo, marca = marca,
                    modelo = modelo, patrimonio = patrimonio, numeroSerie = numeroSerie,
                    envioConcluido = false, recusada = false, ultimoErro = null
                )
            } else it
        }
        publicar()
        return n
    }

    override fun marcarNaoCadastrada(id: Long) =
        alterar(id) { it.copy(situacao = Situacao.NAO_CADASTRADA, ultimoErro = null) }

    override fun marcarEnviada(id: Long) =
        alterar(id) { it.copy(envioConcluido = true, ultimoErro = null) }

    override fun registrarErro(id: Long, erro: String) =
        alterar(id) { it.copy(ultimoErro = erro) }

    override fun marcarRecusada(id: Long, erro: String) = alterar(id) {
        it.copy(
            envioConcluido = true,
            recusada = true,
            ultimoErro = erro,
            situacao = if (it.situacao == Situacao.PENDENTE) Situacao.ERRO else it.situacao
        )
    }

    override fun desfazerRecusas(ids: List<Long>) = ids.forEach { id ->
        alterar(id) {
            it.copy(
                envioConcluido = false,
                recusada = false,
                situacao = if (it.situacao == Situacao.ERRO) Situacao.PENDENTE else it.situacao
            )
        }
    }

    override fun apagarRecusadasAntigas(antesDe: Long): Int {
        val antes = leituras.size
        leituras.removeAll { it.recusada && it.arquivada && it.envioConcluido && it.lidoEm < antesDe }
        publicar()
        return antes - leituras.size
    }

    override fun pendentes(): List<LeituraEntity> {
        consultasPendentes++
        return leituras.filter(::ehPendente).sortedBy { it.id }
    }

    override fun epcsDaSessao(): List<String> = leituras.filter { !it.arquivada }.map { it.epc }

    override fun arquivarSessao(): Int {
        var n = 0
        leituras.replaceAll { if (!it.arquivada) { n++; it.copy(arquivada = true) } else it }
        publicar()
        return n
    }

    override fun apagarArquivadasConcluidas(): Int {
        val antes = leituras.size
        leituras.removeAll { it.arquivada && it.situacao != Situacao.PENDENTE && it.envioConcluido && !it.recusada }
        publicar()
        return antes - leituras.size
    }

    override fun cadastradasDaSessao(): LiveData<List<LeituraEntity>> = cadastradas.also { publicar() }

    override fun naoCadastradasDaSessao(): LiveData<List<LeituraEntity>> = naoCadastradas.also { publicar() }

    override fun aguardandoDaSessao(): LiveData<List<LeituraEntity>> = aguardando.also { publicar() }

    override fun abaLeituraDaSessao(): LiveData<List<LeituraEntity>> = abaLeitura.also { publicar() }

    override fun recusadasAgora(): List<LeituraEntity> =
        leituras.filter { it.recusada && it.envioConcluido }.sortedByDescending { it.id }

    override fun contarPendentes(): LiveData<Int> = pendentesCount.also { publicar() }

    override fun contarPendentesAgora(): Int = leituras.count(::ehPendente)

    override fun lidoEmPendenteMaisAntiga(): Long? = leituras.filter(::ehPendente).minOfOrNull { it.lidoEm }

    override fun sessoesEmUso(): List<String> =
        leituras.mapNotNull { it.sessaoOperador }.filter { it.startsWith("sessao1:") }.distinct()

    override fun contarSessao(): LiveData<ContagemSessao> = contagemSessao.also { publicar() }
}
