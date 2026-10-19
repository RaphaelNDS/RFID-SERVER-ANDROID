package com.example.rfid_server.dados

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.Executor

/** Peças do cadastro pelo coletor que não falam com o servidor: repositório, catálogo e aba de não cadastradas. */
class CadastroColetorTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val dao = FakeCadastroDao()
    private var sincronizacoes = 0
    private var sessao: String? = "sessao-ana"

    // lazy: o LiveData só pode ser criado com a InstantTaskExecutorRule já ativa (dentro do teste)
    private val repositorio by lazy {
        RepositorioCadastros(
            dao = dao,
            executor = Executor { it.run() },
            solicitarSincronizacao = { sincronizacoes++ },
            relogio = { 5_000L },
            sessaoAtual = { sessao }
        )
    }

    private fun cadastro(epc: String = "E1", patrimonio: String = "PAT-1") =
        CadastroEntity(epc = epc, codigoInterno = "TI-1", patrimonio = patrimonio, criadoEm = 0)

    // ---------- RepositorioCadastros ----------

    @Test
    fun `salvar guarda pendente sem espacos e pede sincronizacao`() {
        var terminou = false

        repositorio.salvar(cadastro().copy(codigoInterno = "  TI-1 ", patrimonio = " PAT-1 ")) { terminou = it }

        val guardado = dao.porEpcAgora("E1")!!
        assertEquals("TI-1", guardado.codigoInterno)
        assertEquals("PAT-1", guardado.patrimonio)
        assertEquals(SituacaoCadastro.PENDENTE, guardado.situacao)
        assertEquals(5_000L, guardado.criadoEm)
        assertEquals(1, sincronizacoes)
        assertEquals(true, terminou)
    }

    @Test
    fun `resultado do envio acompanha o que o sincronizador fez com o cadastro`() {
        repositorio.salvar(cadastro())
        val guardado = dao.porEpcAgora("E1")!!

        // Logo depois de salvar: ainda não tentou
        assertEquals(RepositorioCadastros.ResultadoEnvio.Aguardando, RepositorioCadastros.resultadoEnvio(guardado))

        // Servidor confirmou: o sincronizador apaga o cadastro do aparelho
        assertEquals(RepositorioCadastros.ResultadoEnvio.Enviado, RepositorioCadastros.resultadoEnvio(null))

        // Servidor recusou: precisa corrigir
        assertEquals(
            RepositorioCadastros.ResultadoEnvio.Recusado("Esta etiqueta já está cadastrada"),
            RepositorioCadastros.resultadoEnvio(guardado.copy(situacao = SituacaoCadastro.RECUSADO, erro = "Esta etiqueta já está cadastrada"))
        )

        // Sem rede: continua pendente, com o erro da tentativa
        assertEquals(
            RepositorioCadastros.ResultadoEnvio.NaoEnviado("Sem conexão com o servidor"),
            RepositorioCadastros.resultadoEnvio(guardado.copy(erro = "Sem conexão com o servidor"))
        )
    }

    @Test
    fun `salvar de novo zera o erro da tentativa anterior`() {
        repositorio.salvar(cadastro())
        dao.registrarErro(dao.porEpcAgora("E1")!!.id, "Sem conexão com o servidor")

        repositorio.salvar(cadastro())

        // Senão a tela diria "sem conexão" por causa da tentativa antiga, antes de o envio novo terminar
        assertEquals(RepositorioCadastros.ResultadoEnvio.Aguardando, RepositorioCadastros.resultadoEnvio(dao.porEpcAgora("E1")))
    }

    @Test
    fun `corrigir um recusado substitui e volta a pendente`() {
        repositorio.salvar(cadastro(patrimonio = "PAT-ERRADO"))
        dao.marcarRecusado(dao.porEpcAgora("E1")!!.id, "Esta etiqueta já está cadastrada")

        repositorio.salvar(cadastro(patrimonio = "PAT-CERTO"))

        assertEquals(1, dao.cadastros.size)
        val guardado = dao.porEpcAgora("E1")!!
        assertEquals("PAT-CERTO", guardado.patrimonio)
        assertEquals(SituacaoCadastro.PENDENTE, guardado.situacao)
        assertNull(guardado.erro)
    }

    @Test
    fun `porEpc devolve o cadastro guardado para preencher o formulario`() {
        repositorio.salvar(cadastro())
        var encontrado: CadastroEntity? = null

        repositorio.porEpc("E1") { encontrado = it }

        assertEquals("PAT-1", encontrado?.patrimonio)
    }

    // ---------- Aba "Não cadastradas" ----------

    @Test
    fun `aba mostra a situacao do cadastro de cada etiqueta`() {
        val leituras = listOf("E1", "E2", "E3").map { LeituraEntity(epc = it, lidoEm = 0, situacao = Situacao.NAO_CADASTRADA) }
        val cadastros = listOf(
            cadastro("E2", patrimonio = "PAT-2"),
            cadastro("E3").copy(situacao = SituacaoCadastro.RECUSADO, erro = "Modelo não encontrado")
        )

        val itens = ItemNaoCadastrada.montar(leituras, cadastros)

        assertEquals(listOf("E1", "E2", "E3"), itens.map { it.epc })
        assertEquals(ItemNaoCadastrada.SEM_CADASTRO, itens[0].situacao)
        assertEquals("Cadastro aguardando envio ao servidor (patrimônio PAT-2)", itens[1].situacao)
        assertEquals("Cadastro recusado: Modelo não encontrado — toque para corrigir", itens[2].situacao)
    }

    // ---------- Catálogo ----------

    @Test
    fun `catalogo do servidor vira arvore tipo marca modelo`() {
        val json = """
            {"podeCadastrar":true,"tipos":[
              {"id":1,"nome":"Notebook","marcas":[
                {"id":2,"nome":"Dell","modelos":[{"id":3,"nome":"Latitude"},{"id":4,"nome":"Vostro"}]}]},
              {"id":5,"nome":"Monitor","marcas":[]}]}
        """

        val catalogo = CatalogoColetor.deJson(json)

        assertEquals(true, catalogo.podeCadastrar)
        assertEquals(listOf("Notebook", "Monitor"), catalogo.tipos.map { it.nome })
        assertEquals(listOf("Latitude", "Vostro"), catalogo.tipos[0].marcas[0].modelos.map { it.nome })
        assertEquals(4L, catalogo.tipos[0].marcas[0].modelos[1].id)
    }

    @Test
    fun `catalogo sem permissao continua legivel`() {
        assertFalse(CatalogoColetor.deJson("""{"podeCadastrar":false,"tipos":[]}""").podeCadastrar)
    }

    @Test(expected = JSONException::class)
    fun `texto que nao e o catalogo lanca erro`() {
        CatalogoColetor.deJson("""{"status":"ok"}""")
    }
}
