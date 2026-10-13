package com.example.rfid_server.sincronizacao

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.example.rfid_server.api.HttpClient
import com.example.rfid_server.dados.FakeLeituraDao
import com.example.rfid_server.dados.LeituraEntity
import com.example.rfid_server.dados.Situacao
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
 * Testa a fila offline de ponta a ponta: DAO em memória + TagService real + servidor HTTP falso.
 * Todo teste tem timeout: se o Sincronizador continuasse depois de uma falha, ele pediria uma
 * resposta que não foi enfileirada e o MockWebServer ficaria esperando para sempre.
 */
class SincronizadorTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var servidor: MockWebServer
    private lateinit var dao: FakeLeituraDao
    private var novasTentativasAgendadas = 0

    private val executorDireto = Executor { it.run() }

    @Before
    fun setUp() {
        servidor = MockWebServer()
        servidor.start()
        dao = FakeLeituraDao()
    }

    @After
    fun tearDown() {
        servidor.shutdown()
    }

    private fun sincronizador(executor: Executor = executorDireto) = Sincronizador(
        dao = dao,
        tagService = TagService(HttpClient.criar { "chave-teste" }) { servidor.url("/").toString().trimEnd('/') },
        executor = executor,
        agendarNovaTentativa = { novasTentativasAgendadas++ }
    )

    private fun json(codigo: Int, corpo: String = "{}") =
        MockResponse().setResponseCode(codigo).setHeader("Content-Type", "application/json").setBody(corpo)

    private val tagCadastrada = """
        {"codigoInterno":"CI-01","tipo":"Notebook","marca":"Dell","modelo":"Latitude",
         "patrimonio":"PAT-100","numeroSerie":"SN-1"}
    """

    private var requisicoesLidas = 0

    /** Caminhos das requisições recebidas desde a última chamada (requestCount é cumulativo). */
    private fun caminhosRecebidos(): List<String> {
        val novas = servidor.requestCount - requisicoesLidas
        requisicoesLidas = servidor.requestCount
        return (1..novas).map { servidor.takeRequest(1, TimeUnit.SECONDS)!!.path!! }
    }

    @Test(timeout = 10_000)
    fun `sem leituras pendentes nao chama o servidor`() {
        val estado = sincronizador().processarPendentes()

        assertEquals(EstadoSincronizacao.SINCRONIZADO, estado)
        assertEquals(0, servidor.requestCount)
    }

    @Test(timeout = 10_000)
    fun `tag cadastrada e consultada e a leitura enviada com a hora real`() {
        dao.inserir(LeituraEntity(epc = "E1", lidoEm = 1_760_371_431_000L))
        servidor.enqueue(json(200, tagCadastrada))
        servidor.enqueue(json(200))

        val estado = sincronizador().processarPendentes()

        assertEquals(EstadoSincronizacao.SINCRONIZADO, estado)

        val leitura = dao.porEpc("E1")
        assertEquals(Situacao.CADASTRADA, leitura.situacao)
        assertTrue(leitura.envioConcluido)
        assertEquals("PAT-100", leitura.patrimonio)

        servidor.takeRequest()   // GET da consulta
        val envio = servidor.takeRequest()
        assertEquals("/api/android/leitura", envio.path)
        val lidoEm = JSONObject(envio.body.readUtf8()).getString("lidoEm")
        assertEquals(1_760_371_431_000L, OffsetDateTime.parse(lidoEm).toInstant().toEpochMilli())
    }

    @Test(timeout = 10_000)
    fun `tag nao cadastrada e enviada como nao cadastrada e nao como leitura`() {
        dao.inserir(LeituraEntity(epc = "E1", lidoEm = 0))
        servidor.enqueue(json(404))
        servidor.enqueue(json(200))

        sincronizador().processarPendentes()

        val leitura = dao.porEpc("E1")
        assertEquals(Situacao.NAO_CADASTRADA, leitura.situacao)
        assertTrue(leitura.envioConcluido)
        assertEquals(listOf("/api/android/tags/E1", "/api/android/naocadastrada"), caminhosRecebidos())
    }

    @Test(timeout = 10_000)
    fun `servidor fora do ar mantem tudo guardado e agenda nova tentativa`() {
        dao.inserir(LeituraEntity(epc = "E1", lidoEm = 0))
        dao.inserir(LeituraEntity(epc = "E2", lidoEm = 0))
        servidor.shutdown()

        val estado = sincronizador().processarPendentes()

        assertEquals(EstadoSincronizacao.SEM_CONEXAO, estado)
        assertEquals(1, novasTentativasAgendadas)
        dao.leituras.forEach {
            assertEquals(Situacao.PENDENTE, it.situacao)
            assertFalse(it.envioConcluido)
        }
        assertNotNull("O motivo fica registrado na leitura", dao.porEpc("E1").ultimoErro)
    }

    @Test(timeout = 10_000)
    fun `para na primeira falha do servidor sem tentar as outras leituras`() {
        dao.inserir(LeituraEntity(epc = "E1", lidoEm = 0))
        dao.inserir(LeituraEntity(epc = "E2", lidoEm = 0))
        dao.inserir(LeituraEntity(epc = "E3", lidoEm = 0))
        servidor.enqueue(json(503))

        val estado = sincronizador().processarPendentes()

        assertEquals(EstadoSincronizacao.ERRO_SERVIDOR, estado)
        assertEquals(1, servidor.requestCount)
        assertEquals(1, novasTentativasAgendadas)
    }

    @Test(timeout = 10_000)
    fun `chave invalida interrompe e informa o estado`() {
        dao.inserir(LeituraEntity(epc = "E1", lidoEm = 0))
        servidor.enqueue(json(401))

        val sincronizador = sincronizador()
        val estado = sincronizador.processarPendentes()

        assertEquals(EstadoSincronizacao.CHAVE_INVALIDA, estado)
        assertEquals(EstadoSincronizacao.CHAVE_INVALIDA, sincronizador.estado.value)
        assertEquals(Situacao.PENDENTE, dao.porEpc("E1").situacao)
    }

    @Test(timeout = 10_000)
    fun `classificacao fica gravada se o envio falhar e so o envio e repetido depois`() {
        dao.inserir(LeituraEntity(epc = "E1", lidoEm = 0))
        servidor.enqueue(json(200, tagCadastrada))
        servidor.enqueue(json(503))

        val sincronizador = sincronizador()
        sincronizador.processarPendentes()

        // Já aparece na tela como cadastrada, mas ainda falta o envio
        assertEquals(Situacao.CADASTRADA, dao.porEpc("E1").situacao)
        assertFalse(dao.porEpc("E1").envioConcluido)
        assertEquals(listOf("/api/android/tags/E1", "/api/android/leitura"), caminhosRecebidos())

        servidor.enqueue(json(200))
        val estado = sincronizador.processarPendentes()

        assertEquals(EstadoSincronizacao.SINCRONIZADO, estado)
        assertTrue(dao.porEpc("E1").envioConcluido)
        assertNull(dao.porEpc("E1").ultimoErro)
        assertEquals("Na segunda vez, sem consultar a tag de novo", listOf("/api/android/leitura"), caminhosRecebidos())
    }

    @Test(timeout = 10_000)
    fun `tag recusada pelo servidor nao trava a fila`() {
        dao.inserir(LeituraEntity(epc = "RUIM", lidoEm = 0))
        dao.inserir(LeituraEntity(epc = "E2", lidoEm = 0))
        servidor.enqueue(json(400))           // consulta da RUIM
        servidor.enqueue(json(404))           // consulta da E2
        servidor.enqueue(json(200))           // envio da E2 como não cadastrada

        val estado = sincronizador().processarPendentes()

        assertEquals(EstadoSincronizacao.SINCRONIZADO, estado)

        val ruim = dao.porEpc("RUIM")
        assertEquals(Situacao.ERRO, ruim.situacao)
        assertTrue("Sai da fila para não ser reenviada", ruim.envioConcluido)
        assertNotNull(ruim.ultimoErro)

        assertEquals(Situacao.NAO_CADASTRADA, dao.porEpc("E2").situacao)
        assertTrue(dao.porEpc("E2").envioConcluido)
    }

    @Test(timeout = 10_000)
    fun `leituras de sessoes anteriores ainda sao enviadas e depois removidas do aparelho`() {
        dao.inserir(LeituraEntity(epc = "ANTIGA", lidoEm = 0, arquivada = true))
        servidor.enqueue(json(404))
        servidor.enqueue(json(200))

        sincronizador().processarPendentes()

        assertEquals(2, servidor.requestCount)
        assertTrue("Enviada e arquivada: não precisa mais ficar no aparelho", dao.leituras.isEmpty())
    }

    @Test(timeout = 10_000)
    fun `varios pedidos seguidos viram uma unica passada`() {
        val fila = ArrayDeque<Runnable>()
        val sincronizador = sincronizador(Executor { fila.addLast(it) })

        // Ex.: 3 tags lidas em sequência rápida, antes da primeira passada começar
        sincronizador.solicitar()
        sincronizador.solicitar()
        sincronizador.solicitar()
        while (fila.isNotEmpty()) fila.removeFirst().run()

        assertEquals(1, dao.consultasPendentes)
    }

    @Test(timeout = 10_000)
    fun `cada leitura vai com a sessao de quem leu`() {
        dao.inserir(LeituraEntity(epc = "E1", lidoEm = 0, sessaoOperador = "sessao-ana"))
        servidor.enqueue(json(404))
        servidor.enqueue(json(200))

        sincronizador().processarPendentes()

        val (consulta, envio) = (1..2).map { servidor.takeRequest(1, TimeUnit.SECONDS)!! }
        assertEquals("sessao-ana", consulta.getHeader("X-Sessao-Operador"))
        assertEquals("sessao-ana", envio.getHeader("X-Sessao-Operador"))
    }

    @Test(timeout = 10_000)
    fun `sessao invalida numa leitura nao trava a fila`() {
        dao.inserir(LeituraEntity(epc = "SEM-SESSAO", lidoEm = 0, sessaoOperador = "sessao-de-usuario-excluido"))
        dao.inserir(LeituraEntity(epc = "E2", lidoEm = 0, sessaoOperador = "sessao-ana"))
        servidor.enqueue(json(401, """{"status":401,"codigo":"SESSAO_OPERADOR","mensagem":"O usuário desta sessão não existe mais"}"""))
        servidor.enqueue(json(404))   // E2: não cadastrada
        servidor.enqueue(json(200))

        val estado = sincronizador().processarPendentes()

        assertEquals(EstadoSincronizacao.SINCRONIZADO, estado)
        val recusada = dao.porEpc("SEM-SESSAO")
        assertEquals(Situacao.ERRO, recusada.situacao)
        assertEquals("O usuário desta sessão não existe mais", recusada.ultimoErro)
        assertTrue(dao.porEpc("E2").envioConcluido)
    }
}
