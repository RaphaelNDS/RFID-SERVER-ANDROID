package com.example.rfid_server.sincronizacao

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.example.rfid_server.api.HttpClient
import com.example.rfid_server.dados.FakeInventarioDao
import com.example.rfid_server.dados.FakeLeituraDao
import com.example.rfid_server.dados.InventarioEntity
import com.example.rfid_server.dados.InventarioItemEntity
import com.example.rfid_server.dados.LeituraEntity
import com.example.rfid_server.service.MotivoFalha
import com.example.rfid_server.service.TagService
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.OffsetDateTime
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Envio dos inventários: DAO em memória + TagService real + servidor HTTP falso.
 * Timeout em todos: se o código fizesse uma requisição a mais, o MockWebServer ficaria esperando.
 */
class SincronizadorInventariosTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var servidor: MockWebServer
    private val dao = FakeInventarioDao()

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

    private fun sincronizador() = SincronizadorInventarios(dao, tagService())

    private fun ok() = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("{}")
    private fun erro(codigo: Int) = MockResponse().setResponseCode(codigo).setBody("{}")

    private var lidas = 0
    private fun proximas(n: Int) = (1..n).map { servidor.takeRequest(1, TimeUnit.SECONDS)!! }.also { lidas += n }

    private fun inventario(id: String, finalizado: Boolean, vararg epcs: String, iniciadoEm: Long = 1_000) {
        dao.inserirInventario(InventarioEntity(id, "Sala 101", iniciadoEm, finalizadoEm = if (finalizado) 2_000 else null))
        epcs.forEach { dao.inserirItem(InventarioItemEntity(inventarioId = id, epc = it, lidoEm = 1_500)) }
    }

    @Test(timeout = 10_000)
    fun `sem inventario pendente nao chama o servidor`() {
        assertNull(sincronizador().processar())
        assertEquals(0, servidor.requestCount)
    }

    @Test(timeout = 10_000)
    fun `inventario finalizado vai na ordem abertura itens finalizacao e sai do coletor`() {
        inventario("inv-1", finalizado = true, "E1", "E2")
        repeat(3) { servidor.enqueue(ok()) }

        assertNull(sincronizador().processar())

        val (abertura, itens, finalizacao) = proximas(3)
        assertEquals("/api/android/inventarios", abertura.path)
        val corpoAbertura = JSONObject(abertura.body.readUtf8())
        assertEquals("inv-1", corpoAbertura.getString("id"))
        assertEquals("Sala 101", corpoAbertura.getString("local"))
        assertEquals(1_000L, OffsetDateTime.parse(corpoAbertura.getString("iniciadoEm")).toInstant().toEpochMilli())

        assertEquals("/api/android/inventarios/inv-1/itens", itens.path)
        val lista = JSONObject(itens.body.readUtf8()).getJSONArray("itens")
        assertEquals(2, lista.length())
        assertEquals("E1", lista.getJSONObject(0).getString("tag"))
        assertEquals(1_500L, OffsetDateTime.parse(lista.getJSONObject(0).getString("lidoEm")).toInstant().toEpochMilli())

        assertEquals("/api/android/inventarios/inv-1/finalizar", finalizacao.path)

        assertTrue("Tudo confirmado: apagado do coletor", dao.inventarios.isEmpty() && dao.itens.isEmpty())
    }

    @Test(timeout = 10_000)
    fun `inventario em andamento envia o que ja foi lido e continua no coletor`() {
        inventario("inv-1", finalizado = false, "E1")
        repeat(2) { servidor.enqueue(ok()) }

        sincronizador().processar()

        assertEquals(2, servidor.requestCount)
        val inv = dao.inventario("inv-1")
        assertTrue(inv.aberturaEnviada)
        assertTrue(dao.itens.single().enviado)
    }

    @Test(timeout = 10_000)
    fun `muitas etiquetas vao em lotes`() {
        val epcs = (1..SincronizadorInventarios.TAMANHO_LOTE + 50).map { "E$it" }.toTypedArray()
        inventario("inv-1", finalizado = false, *epcs)
        repeat(3) { servidor.enqueue(ok()) }

        sincronizador().processar()

        val (_, lote1, lote2) = proximas(3)
        assertEquals(SincronizadorInventarios.TAMANHO_LOTE, JSONObject(lote1.body.readUtf8()).getJSONArray("itens").length())
        assertEquals(50, JSONObject(lote2.body.readUtf8()).getJSONArray("itens").length())
    }

    @Test(timeout = 10_000)
    fun `sem conexao nada e perdido e a falha interrompe`() {
        inventario("inv-1", finalizado = true, "E1")
        servidor.shutdown()

        val falha = sincronizador().processar()

        assertEquals(MotivoFalha.SEM_CONEXAO, falha?.motivo)
        val inv = dao.inventario("inv-1")
        assertFalse(inv.aberturaEnviada)
        assertNotNull(inv.ultimoErro)
        assertFalse(dao.itens.single().enviado)
    }

    @Test(timeout = 10_000)
    fun `falha no meio continua de onde parou na proxima vez`() {
        inventario("inv-1", finalizado = true, "E1")
        servidor.enqueue(ok())          // abertura
        servidor.enqueue(erro(503))     // itens

        assertEquals(MotivoFalha.ERRO_SERVIDOR, sincronizador().processar()?.motivo)
        proximas(2)

        repeat(2) { servidor.enqueue(ok()) }
        assertNull(sincronizador().processar())

        val paths = proximas(2).map { it.path }
        assertEquals("Sem reabrir: só itens e finalização",
            listOf("/api/android/inventarios/inv-1/itens", "/api/android/inventarios/inv-1/finalizar"), paths)
        assertTrue(dao.inventarios.isEmpty())
    }

    @Test(timeout = 10_000)
    fun `servidor que nao conhece o inventario faz reabrir na proxima vez`() {
        inventario("inv-1", finalizado = false, "E1")
        dao.definirAberturaEnviada("inv-1", true)
        servidor.enqueue(erro(404))     // itens: inventário desconhecido

        val falha = sincronizador().processar()

        assertNotNull(falha)
        assertFalse("Vai registrar de novo", dao.inventario("inv-1").aberturaEnviada)
        assertFalse(dao.itens.single().enviado)
    }

    @Test(timeout = 10_000)
    fun `inventario recusado pelo servidor nao trava os outros`() {
        inventario("ruim", finalizado = true, iniciadoEm = 1_000)
        inventario("bom", finalizado = true, "E1", iniciadoEm = 2_000)
        servidor.enqueue(erro(400))             // abertura do "ruim"
        repeat(3) { servidor.enqueue(ok()) }    // "bom" inteiro

        assertNull(sincronizador().processar())

        assertEquals(listOf("ruim"), dao.inventarios.map { it.id })
        assertNotNull("Fica guardado com o motivo", dao.inventario("ruim").ultimoErro)
    }

    @Test(timeout = 10_000)
    fun `sincronizador envia inventarios depois das leituras avulsas`() {
        val leituras = FakeLeituraDao(LeituraEntity(epc = "AVULSA", lidoEm = 0))
        inventario("inv-1", finalizado = false)
        servidor.enqueue(MockResponse().setResponseCode(404))  // consulta da avulsa: não cadastrada
        servidor.enqueue(ok())                                 // envio da não cadastrada
        servidor.enqueue(ok())                                 // abertura do inventário

        val estado = Sincronizador(
            dao = leituras, tagService = tagService(), executor = Executor { it.run() },
            agendarNovaTentativa = {}, inventarios = sincronizador()
        ).processarPendentes()

        assertEquals(EstadoSincronizacao.SINCRONIZADO, estado)
        assertEquals(
            listOf("/api/android/tags/AVULSA", "/api/android/naocadastrada", "/api/android/inventarios"),
            proximas(3).map { it.path }
        )
    }

    @Test(timeout = 10_000)
    fun `falha nas leituras avulsas nao tenta os inventarios`() {
        val leituras = FakeLeituraDao(LeituraEntity(epc = "AVULSA", lidoEm = 0))
        inventario("inv-1", finalizado = false)
        servidor.enqueue(erro(503))
        var tentativas = 0

        val estado = Sincronizador(
            dao = leituras, tagService = tagService(), executor = Executor { it.run() },
            agendarNovaTentativa = { tentativas++ }, inventarios = sincronizador()
        ).processarPendentes()

        assertEquals(EstadoSincronizacao.ERRO_SERVIDOR, estado)
        assertEquals(1, servidor.requestCount)
        assertEquals(1, tentativas)
    }

    @Test(timeout = 10_000)
    fun `etiquetas vao agrupadas pela sessao de quem leu e cada etapa com a sua sessao`() {
        dao.inserirInventario(
            InventarioEntity("inv-1", "Sala 101", 1_000, finalizadoEm = 3_000, sessaoOperador = "sessao-ana", sessaoFinalizacao = "sessao-bruno")
        )
        dao.inserirItem(InventarioItemEntity(inventarioId = "inv-1", epc = "E1", lidoEm = 1_500, sessaoOperador = "sessao-ana"))
        dao.inserirItem(InventarioItemEntity(inventarioId = "inv-1", epc = "E2", lidoEm = 2_500, sessaoOperador = "sessao-bruno"))
        repeat(4) { servidor.enqueue(ok()) }

        assertNull(sincronizador().processar())

        val requisicoes = proximas(4)
        assertEquals(
            listOf("sessao-ana", "sessao-ana", "sessao-bruno", "sessao-bruno"),
            requisicoes.map { it.getHeader("X-Sessao-Operador") }
        )
        assertEquals("E2", JSONObject(requisicoes[2].body.readUtf8()).getJSONArray("itens").getJSONObject(0).getString("tag"))
        assertEquals("/api/android/inventarios/inv-1/finalizar", requisicoes[3].path)
    }
}
