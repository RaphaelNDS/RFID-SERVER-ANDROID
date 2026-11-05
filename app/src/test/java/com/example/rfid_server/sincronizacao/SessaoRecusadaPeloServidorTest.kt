package com.example.rfid_server.sincronizacao

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.example.rfid_server.activities.RegrasFaixaSincronizacao
import com.example.rfid_server.api.HttpClient
import com.example.rfid_server.dados.CadastroEntity
import com.example.rfid_server.dados.FakeCadastroDao
import com.example.rfid_server.dados.FakeInventarioDao
import com.example.rfid_server.dados.FakeLeituraDao
import com.example.rfid_server.dados.InventarioEntity
import com.example.rfid_server.dados.LeituraEntity
import com.example.rfid_server.service.TagService
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.Executor

/**
 * Servidor recusando a SESSÃO ATUAL do aparelho (usuário desativado, sessão encerrada pela web, servidor que não a
 * conhece): o app encerra a sessão local e o operador entra de novo. Antes ele seguia lendo e tudo ia para recusadas.
 * Também o estado SESSAO_RECUSADA da passada.
 */
class SessaoRecusadaPeloServidorTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var servidor: MockWebServer
    private val agora = 10_000_000_000L

    // Sessão atual do aparelho: o banco guarda a referência, não o token
    private var tokenAtual: String? = TOKEN
    private val encerradas = mutableListOf<Pair<String, String>>()
    private val fimDaSessao = FimDaSessaoAtual(
        tokenAtual = { tokenAtual },
        referenciaDe = { "sessao1:" + it.reversed() },
        encerrar = { token, motivo ->
            if (tokenAtual != token) false else {
                encerradas += token to motivo
                tokenAtual = null
                true
            }
        }
    )

    // Todas as recusas de sessão que os sincronizadores avisaram (sessão, motivo)
    private val avisadas = mutableListOf<Pair<String, String?>>()
    private val aoRecusarSessao: (String, String?, String) -> Unit = { sessao, motivo, mensagem ->
        avisadas += sessao to motivo
        fimDaSessao.aoRecusar(sessao, motivo, mensagem)
    }

    @Before
    fun setUp() {
        servidor = MockWebServer()
        servidor.start()
    }

    @After
    fun tearDown() {
        servidor.shutdown()
    }

    private fun tagService() = TagService(HttpClient.criar { "chave-teste" }) { servidor.url("/").toString().trimEnd('/') }

    private fun sincronizador(dao: FakeLeituraDao, cadastros: SincronizadorCadastros? = null, inventarios: SincronizadorInventarios? = null) =
        Sincronizador(
            dao = dao, tagService = tagService(), executor = Executor { it.run() }, agendarNovaTentativa = {},
            inventarios = inventarios, cadastros = cadastros, relogio = { agora }, aoRecusarSessao = aoRecusarSessao
        )

    private fun json(codigo: Int, corpo: String) =
        MockResponse().setResponseCode(codigo).setHeader("Content-Type", "application/json").setBody(corpo)

    private fun sessaoRecusada(motivo: String?) = json(401, JSONObject()
        .put("status", 401).put("codigo", "SESSAO_OPERADOR").put("mensagem", "Sessão recusada ($motivo)")
        .apply { if (motivo != null) put("motivo", motivo) }.toString())

    private fun loteRecusado(motivo: String?) = json(200, JSONObject().put("resultados", JSONArray().put(
        JSONObject().put("indice", 0).put("tag", "E1").put("status", "RECUSADA").put("mensagem", "Fora da sessão ($motivo)")
            .apply { if (motivo != null) put("motivo", motivo) }
    )).toString())

    private fun leitura(sessao: String = REFERENCIA) = LeituraEntity(epc = "E1", lidoEm = agora, sessaoOperador = sessao)

    private fun cadastro(sessao: String) = CadastroEntity(
        epc = "E9", codigoInterno = "TI-1", patrimonio = "PAT-1", numeroSerie = "SN-1",
        tipoId = 1, tipoNome = "Notebook", marcaId = 2, marcaNome = "Dell", modeloId = 3, modeloNome = "Latitude",
        criadoEm = 0, sessaoOperador = sessao
    )

    // ---------- Regra ----------

    @Test
    fun `so encerram a sessao atual os motivos de sessao que nao serve mais`() {
        listOf("ENCERRADA", "EXPIRADA", "USUARIO_INEXISTENTE", "DESATIVADO", "DESCONHECIDA", "OUTRO_COLETOR")
            .forEach { assertTrue(it, MotivoSessao.encerraSessaoAtual(it)) }
        // ANTIGA_DEMAIS: a ação é que chegou tarde; SEM_OPERADOR: requisição sem sessão; sem motivo: servidor antigo
        listOf(null, "ANTIGA_DEMAIS", "SEM_OPERADOR", "UM_MOTIVO_NOVO")
            .forEach { assertFalse("$it não encerra", MotivoSessao.encerraSessaoAtual(it)) }
    }

    @Test
    fun `fim da sessao confere a referencia e o token puro e ignora outras sessoes`() {
        assertFalse("Outra sessão", fimDaSessao.aoRecusar("sessao1:outra", "DESATIVADO", "x"))
        assertFalse("Sem motivo (servidor antigo)", fimDaSessao.aoRecusar(REFERENCIA, null, "x"))
        assertFalse("Ação antiga demais", fimDaSessao.aoRecusar(REFERENCIA, "ANTIGA_DEMAIS", "x"))
        assertTrue(encerradas.isEmpty())

        assertTrue("Token puro (registro de antes da 1.6.1)", fimDaSessao.aoRecusar(TOKEN, "ENCERRADA", "x"))
        assertEquals(listOf(TOKEN to "ENCERRADA"), encerradas)

        // Sessão já encerrada (ou ninguém logado): nada a fazer
        assertFalse(fimDaSessao.aoRecusar(REFERENCIA, "DESATIVADO", "x"))
        assertEquals(1, encerradas.size)
    }

    @Test
    fun `fim da sessao nao lanca se a conferencia falhar`() {
        val comDefeito = FimDaSessaoAtual({ throw IllegalStateException("prefs") }, { it }, { _, _ -> true })
        assertFalse(comDefeito.aoRecusar(REFERENCIA, "DESATIVADO", "x"))
    }

    // ---------- Leituras ----------

    @Test(timeout = 10_000)
    fun `usuario desativado no lote encerra a sessao atual e a leitura segue a regra de sempre`() {
        val dao = FakeLeituraDao(leitura())
        servidor.enqueue(sessaoRecusada("DESATIVADO"))   // lote
        servidor.enqueue(sessaoRecusada("DESATIVADO"))   // E1 sozinha

        assertEquals(EstadoSincronizacao.SINCRONIZADO, sincronizador(dao).processarPendentes())

        assertEquals("Avisou no lote e na conferência sozinha", listOf(REFERENCIA to "DESATIVADO", REFERENCIA to "DESATIVADO"), avisadas)
        assertEquals("Encerrou uma vez só", listOf(TOKEN to "DESATIVADO"), encerradas)
        assertTrue("Definitivo: recusada, como antes", dao.porEpc("E1").recusada)
    }

    @Test(timeout = 10_000)
    fun `sessao encerrada recusada na rota de uma por vez encerra a sessao atual`() {
        val dao = FakeLeituraDao(leitura())
        servidor.enqueue(MockResponse().setResponseCode(404))   // servidor sem a rota de lote
        servidor.enqueue(sessaoRecusada("ENCERRADA"))           // consulta da tag

        sincronizador(dao).processarPendentes()

        assertEquals(listOf(TOKEN to "ENCERRADA"), encerradas)
    }

    @Test(timeout = 10_000)
    fun `servidor que nao conhece a sessao atual encerra mas a leitura continua retida no prazo`() {
        val dao = FakeLeituraDao(leitura(sessao = TOKEN))   // registro antigo, com o token puro
        servidor.enqueue(sessaoRecusada("DESCONHECIDA"))
        servidor.enqueue(sessaoRecusada("DESCONHECIDA"))

        assertEquals(EstadoSincronizacao.SESSAO_RECUSADA, sincronizador(dao).processarPendentes())

        assertEquals(listOf(TOKEN to "DESCONHECIDA"), encerradas)
        assertFalse("Retenção de 24 h igual", dao.porEpc("E1").recusada || dao.porEpc("E1").envioConcluido)
    }

    @Test(timeout = 10_000)
    fun `item do lote recusado com sessao encerrada encerra a sessao atual`() {
        val dao = FakeLeituraDao(leitura())
        servidor.enqueue(loteRecusado("ENCERRADA"))

        sincronizador(dao).processarPendentes()

        assertEquals(listOf(TOKEN to "ENCERRADA"), encerradas)
        assertTrue(dao.porEpc("E1").recusada)
    }

    @Test(timeout = 10_000)
    fun `servidor antigo sem o motivo nao encerra a sessao`() {
        val dao = FakeLeituraDao(leitura())
        servidor.enqueue(sessaoRecusada(null))
        servidor.enqueue(sessaoRecusada(null))

        assertEquals(EstadoSincronizacao.SESSAO_RECUSADA, sincronizador(dao).processarPendentes())

        assertTrue(encerradas.isEmpty())
        assertEquals(TOKEN, tokenAtual)
    }

    @Test(timeout = 10_000)
    fun `recusa de outra sessao ou de acao antiga demais nao encerra a sessao atual`() {
        val dao = FakeLeituraDao(leitura(sessao = "sessao1:de-outro-operador"))
        servidor.enqueue(sessaoRecusada("DESATIVADO"))
        servidor.enqueue(sessaoRecusada("DESATIVADO"))
        sincronizador(dao).processarPendentes()

        val antiga = FakeLeituraDao(leitura())
        servidor.enqueue(loteRecusado("ANTIGA_DEMAIS"))
        sincronizador(antiga).processarPendentes()

        assertTrue(encerradas.isEmpty())
        assertEquals(TOKEN, tokenAtual)
    }

    // ---------- Cadastros e inventários ----------

    @Test(timeout = 10_000)
    fun `cadastro recusado com usuario desativado encerra a sessao atual`() {
        val cadastros = FakeCadastroDao()
        cadastros.salvar(cadastro(sessao = REFERENCIA))
        servidor.enqueue(sessaoRecusada("DESATIVADO"))

        SincronizadorCadastros(cadastros, FakeLeituraDao(), tagService(), aoRecusarSessao = aoRecusarSessao).processar()

        assertEquals(listOf(TOKEN to "DESATIVADO"), encerradas)
    }

    @Test(timeout = 10_000)
    fun `abertura de inventario recusada com sessao encerrada encerra a sessao atual`() {
        val inventarios = FakeInventarioDao()
        inventarios.inserirInventario(InventarioEntity("inv-1", "Sala 101", agora, sessaoOperador = REFERENCIA))
        servidor.enqueue(sessaoRecusada("ENCERRADA"))

        SincronizadorInventarios(inventarios, tagService(), relogio = { agora }, aoRecusarSessao = aoRecusarSessao).processar()

        assertEquals(listOf(TOKEN to "ENCERRADA"), encerradas)
    }

    // ---------- Estado SESSAO_RECUSADA ----------

    @Test
    fun `sessao recusada nao oferece enviar agora mas continua tentando em segundo plano`() {
        val estado = EstadoSincronizacao.SESSAO_RECUSADA
        assertFalse(estado.dependeDasConfiguracoes)
        assertTrue(SincronizacaoWorker.tentarDeNovo(estado))
        assertEquals(RegrasFaixaSincronizacao.Acoes(enviarAgora = false, chamarResponsavel = false), RegrasFaixaSincronizacao.acoes(3, estado))
    }

    @Test(timeout = 10_000)
    fun `erro do servidor numa etapa seguinte vale mais que a sessao retida`() {
        // Cadastro retido pela sessão (etapa 1, falha SESSAO_INVALIDA) e depois uma leitura sem operador com erro do servidor
        val cadastros = FakeCadastroDao()
        cadastros.salvar(cadastro(sessao = "sessao1:retida"))
        val dao = FakeLeituraDao(LeituraEntity(epc = "E2", lidoEm = agora))
        servidor.enqueue(sessaoRecusada("DESCONHECIDA"))
        servidor.enqueue(MockResponse().setResponseCode(500))

        val estado = sincronizador(dao, cadastros = SincronizadorCadastros(cadastros, dao, tagService(), relogio = { agora }))
            .processarPendentes()

        assertEquals(EstadoSincronizacao.ERRO_SERVIDOR, estado)
    }

    companion object {
        private const val TOKEN = "token-ana"
        private val REFERENCIA = "sessao1:" + TOKEN.reversed()
    }
}
