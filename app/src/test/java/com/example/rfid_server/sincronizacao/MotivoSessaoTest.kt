package com.example.rfid_server.sincronizacao

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.example.rfid_server.api.HttpClient
import com.example.rfid_server.dados.FakeInventarioDao
import com.example.rfid_server.dados.FakeLeituraDao
import com.example.rfid_server.dados.InventarioEntity
import com.example.rfid_server.dados.InventarioItemEntity
import com.example.rfid_server.dados.LeituraEntity
import com.example.rfid_server.service.TagService
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.Executor

/**
 * Campo "motivo" da recusa de sessão (servidor novo): só DESCONHECIDA/OUTRO_COLETOR (e motivo ausente ou
 * desconhecido) fazem as leituras esperar na fila; os definitivos (DESATIVADO, EXPIRADA...) recusam na hora.
 */
class MotivoSessaoTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var servidor: MockWebServer
    private val dao = FakeLeituraDao()
    private val recusasDeSessao = RecusasDeSessaoEmMemoria()
    private val errosRegistrados = mutableListOf<String>()
    private val agora = 10_000_000_000L

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

    private fun sincronizador() = Sincronizador(
        dao = dao, tagService = tagService(), executor = Executor { it.run() }, agendarNovaTentativa = {},
        registrarErro = { _, mensagem -> errosRegistrados += mensagem }, relogio = { agora }, recusasDeSessao = recusasDeSessao
    )

    private fun json(codigo: Int, corpo: String) =
        MockResponse().setResponseCode(codigo).setHeader("Content-Type", "application/json").setBody(corpo)

    private fun sessaoRecusada(motivo: String?) = json(401, JSONObject()
        .put("status", 401).put("codigo", "SESSAO_OPERADOR").put("mensagem", "Sessão recusada ($motivo)")
        .apply { if (motivo != null) put("motivo", motivo) }.toString())

    private fun loteRecusado(vararg motivos: String?) = json(200, JSONObject().put("resultados", JSONArray().apply {
        motivos.forEachIndexed { i, motivo ->
            put(JSONObject().put("indice", i).put("tag", "E$i").put("status", "RECUSADA").put("mensagem", "Fora da sessão ($motivo)")
                .apply { if (motivo != null) put("motivo", motivo) })
        }
    }).toString())

    // ---------- Regra ----------

    @Test
    fun `so os motivos definitivos recusam na hora`() {
        listOf("ENCERRADA", "EXPIRADA", "ANTIGA_DEMAIS", "USUARIO_INEXISTENTE", "DESATIVADO", "SEM_OPERADOR")
            .forEach { assertTrue(it, MotivoSessao.definitivo(it)) }
        listOf(null, "DESCONHECIDA", "OUTRO_COLETOR", "UM_MOTIVO_NOVO")
            .forEach { assertTrue("$it espera", MotivoSessao.podeVoltar(it)) }
    }

    // ---------- Leituras (401 da sessão) ----------

    @Test(timeout = 10_000)
    fun `usuario desativado recusa as leituras na hora sem esperar o prazo`() {
        dao.inserir(LeituraEntity(epc = "E1", lidoEm = agora, sessaoOperador = "sessao-ana"))
        servidor.enqueue(sessaoRecusada("DESATIVADO"))   // lote
        servidor.enqueue(sessaoRecusada("DESATIVADO"))   // E1 sozinha

        assertEquals(EstadoSincronizacao.SINCRONIZADO, sincronizador().processarPendentes())

        val leitura = dao.porEpc("E1")
        assertTrue(leitura.recusada && leitura.envioConcluido)
        assertNull("Não começa a contar prazo", recusasDeSessao.primeiraRecusa("sessao-ana"))
    }

    @Test(timeout = 10_000)
    fun `sessao desconhecida no servidor faz esperar o prazo`() {
        dao.inserir(LeituraEntity(epc = "E1", lidoEm = agora, sessaoOperador = "sessao-ana"))
        servidor.enqueue(sessaoRecusada("DESCONHECIDA"))
        servidor.enqueue(sessaoRecusada("DESCONHECIDA"))

        assertEquals(EstadoSincronizacao.SESSAO_RECUSADA, sincronizador().processarPendentes())

        val leitura = dao.porEpc("E1")
        assertFalse(leitura.recusada || leitura.envioConcluido)
        assertEquals(agora, recusasDeSessao.primeiraRecusa("sessao-ana"))
    }

    @Test(timeout = 10_000)
    fun `servidor antigo sem o motivo continua esperando o prazo`() {
        dao.inserir(LeituraEntity(epc = "E1", lidoEm = agora, sessaoOperador = "sessao-ana"))
        servidor.enqueue(sessaoRecusada(null))
        servidor.enqueue(sessaoRecusada(null))

        assertEquals(EstadoSincronizacao.SESSAO_RECUSADA, sincronizador().processarPendentes())
        assertFalse(dao.porEpc("E1").envioConcluido)
    }

    // ---------- Itens RECUSADA do lote ----------

    @Test(timeout = 10_000)
    fun `item do lote recusado com sessao desconhecida espera e os definitivos e sem motivo saem da fila`() {
        (0..2).forEach { dao.inserir(LeituraEntity(epc = "E$it", lidoEm = agora, sessaoOperador = "sessao-ana")) }
        servidor.enqueue(loteRecusado("DESCONHECIDA", "EXPIRADA", null))

        assertEquals(EstadoSincronizacao.SESSAO_RECUSADA, sincronizador().processarPendentes())

        assertFalse("DESCONHECIDA espera", dao.porEpc("E0").envioConcluido)
        assertTrue("EXPIRADA sai", dao.porEpc("E1").recusada)
        assertTrue("Sem motivo (servidor antigo) sai, como antes", dao.porEpc("E2").recusada)
    }

    @Test(timeout = 10_000)
    fun `recusas definitivas de sessao no lote nao disparam o disjuntor`() {
        (0..5).forEach { dao.inserir(LeituraEntity(epc = "E$it", lidoEm = agora, sessaoOperador = "sessao-ana")) }
        servidor.enqueue(loteRecusado(*Array(6) { "EXPIRADA" }))

        assertEquals("Não pausa a fila", EstadoSincronizacao.SINCRONIZADO, sincronizador().processarPendentes())
        assertTrue(dao.leituras.all { it.recusada })
    }

    // ---------- Etiquetas de inventário ----------

    @Test(timeout = 10_000)
    fun `etiquetas de operador desativado saem da fila do inventario na hora`() {
        val inventarios = FakeInventarioDao()
        inventarios.inserirInventario(InventarioEntity("inv-1", "Sala 101", 1_000, aberturaEnviada = true, sessaoOperador = "sessao-ana"))
        inventarios.inserirItem(InventarioItemEntity(inventarioId = "inv-1", epc = "E1", lidoEm = 1_500, sessaoOperador = "sessao-bruno"))
        servidor.enqueue(sessaoRecusada("DESATIVADO"))

        assertNull(SincronizadorInventarios(inventarios, tagService(), registrarErro = { _, m -> errosRegistrados += m }, relogio = { agora }).processar())

        val item = inventarios.itens.single()
        assertTrue("Desistiu na primeira recusa", item.enviado)
        assertEquals(1, errosRegistrados.size)
        assertTrue(errosRegistrados.single(), errosRegistrados.single().contains("DESATIVADO"))
    }

    @Test(timeout = 10_000)
    fun `etiquetas com sessao desconhecida continuam na fila do inventario`() {
        val inventarios = FakeInventarioDao()
        inventarios.inserirInventario(InventarioEntity("inv-1", "Sala 101", 1_000, aberturaEnviada = true, sessaoOperador = "sessao-ana"))
        inventarios.inserirItem(InventarioItemEntity(inventarioId = "inv-1", epc = "E1", lidoEm = 1_500, sessaoOperador = "sessao-bruno"))
        servidor.enqueue(sessaoRecusada("DESCONHECIDA"))

        assertNull(SincronizadorInventarios(inventarios, tagService(), relogio = { agora }).processar())

        assertFalse(inventarios.itens.single().enviado)
        assertEquals(1, inventarios.itens.single().recusas)
    }
}
