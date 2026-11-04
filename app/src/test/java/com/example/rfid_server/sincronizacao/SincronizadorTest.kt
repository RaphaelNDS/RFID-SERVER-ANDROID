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

    // Erros que o Sincronizador mandou guardar para o servidor (tipo, mensagem) e envios dos erros guardados
    private val errosRegistrados = mutableListOf<Pair<String, String>>()
    private var enviosDeErros = 0

    // Relógio dos testes do disjuntor: as leituras com lidoEm = 0 dos outros testes são antigas (não contam nele)
    private var agora = System.currentTimeMillis()

    private fun sincronizador(executor: Executor = executorDireto) = Sincronizador(
        dao = dao,
        tagService = TagService(HttpClient.criar { "chave-teste" }) { servidor.url("/").toString().trimEnd('/') },
        executor = executor,
        agendarNovaTentativa = { novasTentativasAgendadas++ },
        registrarErro = { tipo, mensagem -> errosRegistrados += tipo to mensagem },
        enviarErros = { enviosDeErros++ },
        relogio = { agora }
    )

    // ---------- Erros do app para o servidor ----------

    @Test(timeout = 10_000)
    fun `recusa definitiva fica registrada como erro para o servidor e os erros sao enviados`() {
        dao.inserir(LeituraEntity(epc = "RUIM", lidoEm = 0))
        servidor.enqueue(json(400, """{"status":400,"mensagem":"Informe a TAG RFID"}"""))

        sincronizador().processarPendentes()

        val (tipo, mensagem) = errosRegistrados.single()
        assertEquals(com.example.rfid_server.erros.TipoErro.ENVIO_RECUSADO, tipo)
        assertTrue("Sem o EPC, para recusas iguais se agruparem", mensagem.startsWith("Servidor recusou uma leitura:") && !mensagem.contains("RUIM"))
        assertEquals("Sincronizou: aproveita para enviar os erros guardados", 1, enviosDeErros)
    }

    @Test(timeout = 10_000)
    fun `erro do servidor fica registrado e os erros guardados ainda sao enviados`() {
        dao.inserir(LeituraEntity(epc = "E1", lidoEm = 0))
        servidor.enqueue(json(503))

        sincronizador().processarPendentes()

        assertEquals(com.example.rfid_server.erros.TipoErro.ERRO_SERVIDOR, errosRegistrados.single().first)
        assertEquals(1, enviosDeErros)
    }

    @Test(timeout = 10_000)
    fun `sem conexao nao registra erro nem tenta enviar os erros`() {
        dao.inserir(LeituraEntity(epc = "E1", lidoEm = 0))
        servidor.shutdown()

        sincronizador().processarPendentes()

        assertTrue(errosRegistrados.isEmpty())
        assertEquals(0, enviosDeErros)
    }

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
        assertEquals("Só as Configurações resolvem: não agenda tentativas à toa", 0, novasTentativasAgendadas)
    }

    @Test(timeout = 10_000)
    fun `chamado pelo worker nao agenda outra tentativa porque o worker ja pede a dele`() {
        dao.inserir(LeituraEntity(epc = "E1", lidoEm = 0))
        servidor.enqueue(json(503))

        val estado = sincronizador().processarPendentes(agendarSeFalhar = false)

        assertEquals(EstadoSincronizacao.ERRO_SERVIDOR, estado)
        assertEquals(0, novasTentativasAgendadas)
    }

    @Test
    fun `worker so tenta de novo quando repetir pode resolver`() {
        assertFalse(SincronizacaoWorker.tentarDeNovo(EstadoSincronizacao.SINCRONIZADO))
        assertTrue(SincronizacaoWorker.tentarDeNovo(EstadoSincronizacao.SEM_CONEXAO))
        assertTrue(SincronizacaoWorker.tentarDeNovo(EstadoSincronizacao.ERRO_SERVIDOR))
        listOf(EstadoSincronizacao.CHAVE_INVALIDA, EstadoSincronizacao.ENDERECO_INVALIDO, EstadoSincronizacao.CERTIFICADO).forEach {
            assertFalse("$it depende das Configurações", SincronizacaoWorker.tentarDeNovo(it))
        }
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

    // ---------- Envio em lote (leituras com operador) ----------

    private fun lote(vararg resultados: String) = json(200, """{"resultados":[${resultados.joinToString(",")}]}""")

    private fun cadastradaNoLote(indice: Int, tag: String) =
        """{"indice":$indice,"tag":"$tag","status":"CADASTRADA","dados":$tagCadastrada}"""

    private fun noLote(indice: Int, tag: String, status: String, mensagem: String? = null): String {
        val item = JSONObject().put("indice", indice).put("tag", tag).put("status", status)
        if (mensagem != null) item.put("mensagem", mensagem)
        return item.toString()
    }

    @Test(timeout = 10_000)
    fun `leituras com operador vao num lote so e cada uma recebe o resultado`() {
        dao.inserir(LeituraEntity(epc = "E1", lidoEm = 1_760_371_431_000L, sessaoOperador = "sessao-ana"))
        dao.inserir(LeituraEntity(epc = "E2", lidoEm = 1_760_371_432_000L, sessaoOperador = "sessao-ana"))
        dao.inserir(LeituraEntity(epc = "E3", lidoEm = 1_760_371_433_000L, sessaoOperador = "sessao-ana"))
        servidor.enqueue(lote(cadastradaNoLote(0, "E1"), noLote(1, "E2", "NAO_CADASTRADA"), noLote(2, "E3", "RECUSADA", "Informe a TAG RFID")))

        val estado = sincronizador().processarPendentes()

        assertEquals(EstadoSincronizacao.SINCRONIZADO, estado)
        val envio = servidor.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("Uma requisição para as 3 leituras", 1, servidor.requestCount)
        assertEquals("/api/android/leituras/lote", envio.path)
        assertEquals("sessao-ana", envio.getHeader("X-Sessao-Operador"))
        assertEquals("X-Momento = leitura mais recente", 1_760_371_433_000L,
            OffsetDateTime.parse(envio.getHeader("X-Momento")).toInstant().toEpochMilli())

        val itens = JSONObject(envio.body.readUtf8()).getJSONArray("itens")
        assertEquals(3, itens.length())
        assertEquals("E1", itens.getJSONObject(0).getString("tag"))
        assertEquals(1_760_371_431_000L, OffsetDateTime.parse(itens.getJSONObject(0).getString("lidoEm")).toInstant().toEpochMilli())

        with(dao.porEpc("E1")) {
            assertEquals(Situacao.CADASTRADA, situacao)
            assertEquals("PAT-100", patrimonio)
            assertTrue(envioConcluido)
        }
        with(dao.porEpc("E2")) {
            assertEquals(Situacao.NAO_CADASTRADA, situacao)
            assertTrue(envioConcluido)
        }
        with(dao.porEpc("E3")) {
            assertEquals(Situacao.ERRO, situacao)
            assertTrue("Recusada sai da fila", envioConcluido)
            assertEquals("Informe a TAG RFID", ultimoErro)
        }
    }

    @Test(timeout = 10_000)
    fun `cada lote leva so as leituras de um operador`() {
        dao.inserir(LeituraEntity(epc = "E1", lidoEm = 0, sessaoOperador = "sessao-ana"))
        dao.inserir(LeituraEntity(epc = "E2", lidoEm = 0, sessaoOperador = "sessao-bruno"))
        dao.inserir(LeituraEntity(epc = "E3", lidoEm = 0, sessaoOperador = "sessao-ana"))
        servidor.enqueue(lote(noLote(0, "E1", "NAO_CADASTRADA"), noLote(1, "E3", "NAO_CADASTRADA")))
        servidor.enqueue(lote(noLote(0, "E2", "NAO_CADASTRADA")))

        sincronizador().processarPendentes()

        val (daAna, doBruno) = (1..2).map { servidor.takeRequest(1, TimeUnit.SECONDS)!! }
        assertEquals("sessao-ana", daAna.getHeader("X-Sessao-Operador"))
        assertEquals(2, JSONObject(daAna.body.readUtf8()).getJSONArray("itens").length())
        assertEquals("sessao-bruno", doBruno.getHeader("X-Sessao-Operador"))
        assertTrue(dao.leituras.all { it.envioConcluido })
    }

    @Test(timeout = 10_000)
    fun `fila grande e dividida em lotes de 100`() {
        repeat(150) { dao.inserir(LeituraEntity(epc = "E$it", lidoEm = 0, sessaoOperador = "sessao-ana")) }
        servidor.enqueue(lote(*Array(100) { noLote(it, "E$it", "NAO_CADASTRADA") }))
        servidor.enqueue(lote(*Array(50) { noLote(it, "E${it + 100}", "NAO_CADASTRADA") }))

        sincronizador().processarPendentes()

        assertEquals(2, servidor.requestCount)
        assertTrue(dao.leituras.all { it.envioConcluido })
    }

    @Test(timeout = 10_000)
    fun `servidor sem a rota de lote recebe uma leitura por vez`() {
        dao.inserir(LeituraEntity(epc = "E1", lidoEm = 0, sessaoOperador = "sessao-ana"))
        dao.inserir(LeituraEntity(epc = "E2", lidoEm = 0, sessaoOperador = "sessao-bruno"))
        servidor.enqueue(json(404))   // lote: servidor antigo
        servidor.enqueue(json(404))   // consulta E1
        servidor.enqueue(json(200))   // E1 como não cadastrada
        servidor.enqueue(json(404))   // consulta E2
        servidor.enqueue(json(200))   // E2 como não cadastrada

        val estado = sincronizador().processarPendentes()

        assertEquals(EstadoSincronizacao.SINCRONIZADO, estado)
        assertEquals(
            "Depois do 404 não tenta o lote do outro operador",
            listOf("/api/android/leituras/lote", "/api/android/tags/E1", "/api/android/naocadastrada",
                "/api/android/tags/E2", "/api/android/naocadastrada"),
            caminhosRecebidos()
        )
        assertTrue(dao.leituras.all { it.envioConcluido })
    }

    @Test(timeout = 10_000)
    fun `resultado do lote e gravado inteiro numa transacao`() {
        (1..3).forEach { dao.inserir(LeituraEntity(epc = "E$it", lidoEm = 0, sessaoOperador = "sessao-ana")) }
        servidor.enqueue(lote(cadastradaNoLote(0, "E1"), noLote(1, "E2", "NAO_CADASTRADA"), noLote(2, "E3", "RECUSADA", "Formato inválido")))
        var transacoes = 0
        var foraDaFilaAntes = -1

        Sincronizador(
            dao = dao,
            tagService = TagService(HttpClient.criar { "chave-teste" }) { servidor.url("/").toString().trimEnd('/') },
            executor = executorDireto,
            agendarNovaTentativa = {},
            emTransacao = { bloco ->
                transacoes++
                foraDaFilaAntes = dao.leituras.count { it.envioConcluido }
                bloco()
            }
        ).processarPendentes()

        assertEquals(1, transacoes)
        assertEquals("Nenhuma gravação antes de abrir a transação", 0, foraDaFilaAntes)
        assertTrue("Todas gravadas dentro dela", dao.leituras.all { it.envioConcluido })
    }

    @Test(timeout = 10_000)
    fun `status desconhecido no lote e aceito e a leitura nao e reenviada`() {
        dao.inserir(LeituraEntity(epc = "E1", lidoEm = 0, sessaoOperador = "sessao-ana"))
        dao.inserir(LeituraEntity(epc = "E2", lidoEm = 0, sessaoOperador = "sessao-ana"))
        servidor.enqueue(lote(noLote(0, "E1", "DUPLICADA"), noLote(1, "E2", "NAO_CADASTRADA")))

        assertEquals(EstadoSincronizacao.SINCRONIZADO, sincronizador().processarPendentes())
        caminhosRecebidos()

        with(dao.porEpc("E1")) {
            assertTrue("Servidor respondeu 2xx: sai da fila", envioConcluido)
            assertEquals("Sem classificação ainda", Situacao.PENDENTE, situacao)
        }

        // Próxima passada: só consulta a tag para classificar, sem registrar a leitura de novo pelo lote
        servidor.enqueue(json(200, tagCadastrada))
        assertEquals(EstadoSincronizacao.SINCRONIZADO, sincronizador().processarPendentes())
        assertEquals(listOf("/api/android/tags/E1"), caminhosRecebidos())
        assertEquals(Situacao.CADASTRADA, dao.porEpc("E1").situacao)
    }

    @Test(timeout = 10_000)
    fun `erro passageiro numa leitura do lote mantem ela na fila e agenda nova tentativa`() {
        dao.inserir(LeituraEntity(epc = "E1", lidoEm = 0, sessaoOperador = "sessao-ana"))
        dao.inserir(LeituraEntity(epc = "E2", lidoEm = 0, sessaoOperador = "sessao-ana"))
        servidor.enqueue(lote(noLote(0, "E1", "ERRO", "Erro no servidor ao processar a tag"), noLote(1, "E2", "NAO_CADASTRADA")))

        val estado = sincronizador().processarPendentes()

        assertEquals(EstadoSincronizacao.ERRO_SERVIDOR, estado)
        assertEquals(1, novasTentativasAgendadas)
        with(dao.porEpc("E1")) {
            assertEquals(Situacao.PENDENTE, situacao)
            assertFalse(envioConcluido)
            assertEquals("Erro no servidor ao processar a tag", ultimoErro)
        }
        assertTrue(dao.porEpc("E2").envioConcluido)
    }

    @Test(timeout = 10_000)
    fun `servidor fora do ar no lote mantem tudo guardado`() {
        dao.inserir(LeituraEntity(epc = "E1", lidoEm = 0, sessaoOperador = "sessao-ana"))
        dao.inserir(LeituraEntity(epc = "E2", lidoEm = 0, sessaoOperador = "sessao-ana"))
        servidor.enqueue(json(503))

        val estado = sincronizador().processarPendentes()

        assertEquals(EstadoSincronizacao.ERRO_SERVIDOR, estado)
        assertEquals(1, novasTentativasAgendadas)
        dao.leituras.forEach {
            assertEquals(Situacao.PENDENTE, it.situacao)
            assertFalse(it.envioConcluido)
            assertNotNull(it.ultimoErro)
        }
    }

    @Test(timeout = 10_000)
    fun `leituras sem operador e com operador vao cada uma pelo seu caminho`() {
        dao.inserir(LeituraEntity(epc = "ANTIGA", lidoEm = 0))   // de antes do login de operador
        dao.inserir(LeituraEntity(epc = "E1", lidoEm = 0, sessaoOperador = "sessao-ana"))
        servidor.enqueue(lote(noLote(0, "E1", "NAO_CADASTRADA")))
        servidor.enqueue(json(404))
        servidor.enqueue(json(200))

        sincronizador().processarPendentes()

        assertEquals(
            listOf("/api/android/leituras/lote", "/api/android/tags/ANTIGA", "/api/android/naocadastrada"),
            caminhosRecebidos()
        )
    }

    private val sessaoRecusada = """{"status":401,"codigo":"SESSAO_OPERADOR","mensagem":"O usuário desta sessão não existe mais"}"""

    @Test(timeout = 10_000)
    fun `sessao invalida num lote faz cada leitura ser conferida sozinha sem travar a fila`() {
        dao.inserir(LeituraEntity(epc = "SEM-SESSAO", lidoEm = 0, sessaoOperador = "sessao-de-usuario-excluido"))
        dao.inserir(LeituraEntity(epc = "E2", lidoEm = 0, sessaoOperador = "sessao-ana"))
        servidor.enqueue(json(401, sessaoRecusada))                // lote da sessão excluída
        servidor.enqueue(lote(noLote(0, "E2", "NAO_CADASTRADA")))  // lote da Ana
        servidor.enqueue(json(401, sessaoRecusada))                // SEM-SESSAO sozinha: recusada, fica na fila (prazo)

        val estado = sincronizador().processarPendentes()

        assertEquals("Não está tudo enviado", EstadoSincronizacao.ERRO_SERVIDOR, estado)
        assertEquals(1, novasTentativasAgendadas)
        val retida = dao.porEpc("SEM-SESSAO")
        assertFalse("Fica na fila durante o prazo", retida.envioConcluido)
        assertFalse(retida.recusada)
        assertEquals("O usuário desta sessão não existe mais", retida.ultimoErro)
        assertTrue(dao.porEpc("E2").envioConcluido)
        assertEquals(
            listOf("/api/android/leituras/lote", "/api/android/leituras/lote", "/api/android/tags/SEM-SESSAO"),
            caminhosRecebidos()
        )
        assertTrue("Retida não é recusa: sem aviso de recusa", errosRegistrados.isEmpty())
    }

    // ---------- Sessão recusada: as leituras esperam um prazo antes de virar recusadas ----------

    private val recusasDeSessao = RecusasDeSessaoEmMemoria()

    private fun sincronizadorComRecusas() = Sincronizador(
        dao = dao,
        tagService = TagService(HttpClient.criar { "chave-teste" }) { servidor.url("/").toString().trimEnd('/') },
        executor = executorDireto,
        agendarNovaTentativa = { novasTentativasAgendadas++ },
        registrarErro = { tipo, mensagem -> errosRegistrados += tipo to mensagem },
        relogio = { agora },
        recusasDeSessao = recusasDeSessao
    )

    @Test(timeout = 10_000)
    fun `servidor que nao conhece a sessao nao faz perder as leituras e so uma e tentada por passada`() {
        // Ex.: banco do servidor restaurado sem as sessões: todas as leituras da Ana seriam recusadas de vez
        (1..5).forEach { dao.inserir(LeituraEntity(epc = "E$it", lidoEm = agora, sessaoOperador = "sessao-ana")) }
        servidor.enqueue(json(401, sessaoRecusada))   // lote
        servidor.enqueue(json(401, sessaoRecusada))   // E1 sozinha; E2..E5 nem são tentadas

        assertEquals(EstadoSincronizacao.ERRO_SERVIDOR, sincronizadorComRecusas().processarPendentes())

        assertEquals(listOf("/api/android/leituras/lote", "/api/android/tags/E1"), caminhosRecebidos())
        dao.leituras.forEach {
            assertFalse("${it.epc} continua na fila", it.envioConcluido)
            assertFalse(it.recusada)
        }
        assertEquals(agora, recusasDeSessao.primeiraRecusa("sessao-ana"))
    }

    @Test(timeout = 10_000)
    fun `sessao que volta a ser aceita dentro do prazo envia tudo e esquece a recusa`() {
        (1..2).forEach { dao.inserir(LeituraEntity(epc = "E$it", lidoEm = agora, sessaoOperador = "sessao-ana")) }
        servidor.enqueue(json(401, sessaoRecusada))
        servidor.enqueue(json(401, sessaoRecusada))
        sincronizadorComRecusas().processarPendentes()
        caminhosRecebidos()

        // O servidor foi corrigido algumas horas depois
        agora += 3 * 60 * 60 * 1000
        servidor.enqueue(lote(noLote(0, "E1", "NAO_CADASTRADA"), noLote(1, "E2", "NAO_CADASTRADA")))

        assertEquals(EstadoSincronizacao.SINCRONIZADO, sincronizadorComRecusas().processarPendentes())
        assertTrue(dao.leituras.all { it.envioConcluido && !it.recusada })
        assertNull(recusasDeSessao.primeiraRecusa("sessao-ana"))
    }

    @Test(timeout = 10_000)
    fun `sessao recusada por mais que o prazo vira recusa de verdade`() {
        dao.inserir(LeituraEntity(epc = "E1", lidoEm = agora, sessaoOperador = "sessao-ana"))
        dao.inserir(LeituraEntity(epc = "E2", lidoEm = agora, sessaoOperador = "sessao-ana"))
        recusasDeSessao.registrar("sessao-ana", agora - Sincronizador.PRAZO_SESSAO_RECUSADA_MS)
        servidor.enqueue(json(401, sessaoRecusada))   // lote
        servidor.enqueue(json(401, sessaoRecusada))   // E1
        servidor.enqueue(json(401, sessaoRecusada))   // E2

        assertEquals(EstadoSincronizacao.SINCRONIZADO, sincronizadorComRecusas().processarPendentes())

        assertTrue(dao.leituras.all { it.recusada && it.envioConcluido })
        assertEquals(2, errosRegistrados.size)
        // Sem leituras dela na fila, a recusa da sessão é esquecida na próxima passada
        sincronizadorComRecusas().processarPendentes()
        assertNull(recusasDeSessao.primeiraRecusa("sessao-ana"))
    }

    @Test(timeout = 10_000)
    fun `sessao retida nao trava os inventarios`() {
        val inventarioDao = com.example.rfid_server.dados.FakeInventarioDao()
        inventarioDao.inserirInventario(com.example.rfid_server.dados.InventarioEntity("inv-1", "Sala 101", 1_000, sessaoOperador = "sessao-bruno"))
        dao.inserir(LeituraEntity(epc = "E1", lidoEm = agora, sessaoOperador = "sessao-ana"))
        servidor.enqueue(json(401, sessaoRecusada))   // lote da Ana
        servidor.enqueue(json(401, sessaoRecusada))   // E1 sozinha: retida
        servidor.enqueue(json(200))                   // abertura do inventário do Bruno

        val tagService = TagService(HttpClient.criar { "chave-teste" }) { servidor.url("/").toString().trimEnd('/') }
        val estado = Sincronizador(
            dao = dao, tagService = tagService, executor = executorDireto, agendarNovaTentativa = { novasTentativasAgendadas++ },
            inventarios = SincronizadorInventarios(inventarioDao, tagService), relogio = { agora }
        ).processarPendentes()

        assertEquals(EstadoSincronizacao.ERRO_SERVIDOR, estado)
        assertTrue(inventarioDao.inventario("inv-1").aberturaEnviada)
        assertEquals(1, novasTentativasAgendadas)
    }

    // ---------- Leituras recusadas e disjuntor ----------

    private val recusa400 = """{"status":400,"mensagem":"Pedido inválido"}"""

    @Test(timeout = 10_000)
    fun `recusada fica no aparelho marcada e aparece na contagem`() {
        dao.inserir(LeituraEntity(epc = "RUIM", lidoEm = agora))
        servidor.enqueue(json(400))

        assertEquals(EstadoSincronizacao.SINCRONIZADO, sincronizador().processarPendentes())

        assertTrue(dao.porEpc("RUIM").recusada)
        assertEquals(1, dao.contarSessao().value!!.recusadas)
    }

    @Test(timeout = 10_000)
    fun `recusadas de sessoes anteriores ficam 30 dias e depois saem do aparelho`() {
        val dia = 24L * 60 * 60 * 1000
        dao.inserir(LeituraEntity(epc = "VELHA", lidoEm = agora - 31 * dia, arquivada = true, envioConcluido = true, recusada = true, situacao = Situacao.ERRO))
        dao.inserir(LeituraEntity(epc = "RECENTE", lidoEm = agora - 2 * dia, arquivada = true, envioConcluido = true, recusada = true, situacao = Situacao.ERRO))
        dao.inserir(LeituraEntity(epc = "NOVA", lidoEm = agora))
        servidor.enqueue(json(404))
        servidor.enqueue(json(200))

        sincronizador().processarPendentes()

        assertEquals(listOf("RECENTE", "NOVA"), dao.leituras.map { it.epc })
    }

    @Test(timeout = 10_000)
    fun `cinco recusas seguidas pelo mesmo motivo pausam a sincronizacao e devolvem as leituras para a fila`() {
        (1..6).forEach { dao.inserir(LeituraEntity(epc = "E$it", lidoEm = agora)) }
        repeat(5) { servidor.enqueue(json(400, recusa400)) } // a sexta nem é consultada: o timeout pega se for

        val estado = sincronizador().processarPendentes()

        assertEquals(EstadoSincronizacao.ERRO_SERVIDOR, estado)
        assertEquals(5, servidor.requestCount)
        assertEquals(1, novasTentativasAgendadas)
        dao.leituras.forEach {
            assertEquals("${it.epc} volta para a fila", Situacao.PENDENTE, it.situacao)
            assertFalse(it.recusada)
            assertFalse(it.envioConcluido)
        }
        assertTrue("Um aviso só, do disjuntor", errosRegistrados.none { it.second.startsWith("Servidor recusou uma leitura") })
        assertTrue(errosRegistrados.single().second.contains("5 leituras seguidas"))
    }

    @Test(timeout = 10_000)
    fun `recusas com motivos diferentes nao disparam o disjuntor`() {
        (1..5).forEach { dao.inserir(LeituraEntity(epc = "E$it", lidoEm = agora)) }
        // A mensagem da recusa leva o código HTTP: cada uma diferente da anterior
        listOf(400, 409, 410, 415, 422).forEach { servidor.enqueue(json(it)) }

        assertEquals(EstadoSincronizacao.SINCRONIZADO, sincronizador().processarPendentes())
        assertTrue(dao.leituras.all { it.recusada })
    }

    @Test(timeout = 10_000)
    fun `recusas de leituras antigas nao contam no disjuntor`() {
        // Recusa que persiste há mais de um dia é de verdade: senão travaria a fila para sempre
        (1..5).forEach { dao.inserir(LeituraEntity(epc = "E$it", lidoEm = agora - Sincronizador.JANELA_DISJUNTOR_MS - 1)) }
        repeat(5) { servidor.enqueue(json(400, recusa400)) }

        assertEquals(EstadoSincronizacao.SINCRONIZADO, sincronizador().processarPendentes())
        assertTrue(dao.leituras.all { it.recusada })
    }

    @Test(timeout = 10_000)
    fun `sessao invalida nao conta no disjuntor`() {
        val sessaoRecusada = """{"status":401,"codigo":"SESSAO_OPERADOR","mensagem":"Usuário desativado"}"""
        (1..5).forEach { dao.inserir(LeituraEntity(epc = "E$it", lidoEm = agora)) }
        repeat(5) { servidor.enqueue(json(401, sessaoRecusada)) }

        assertEquals(EstadoSincronizacao.SINCRONIZADO, sincronizador().processarPendentes())
        assertTrue(dao.leituras.all { it.recusada })
    }

    @Test(timeout = 10_000)
    fun `recusas seguidas dentro do lote disparam o disjuntor e uma aceita depois nao desfaz`() {
        (0..5).forEach { dao.inserir(LeituraEntity(epc = "E$it", lidoEm = agora, sessaoOperador = "sessao-ana")) }
        dao.inserir(LeituraEntity(epc = "OK", lidoEm = agora, sessaoOperador = "sessao-ana"))
        servidor.enqueue(lote(*((0..5).map { noLote(it, "E$it", "RECUSADA", "Formato inválido") } + cadastradaNoLote(6, "OK")).toTypedArray()))

        assertEquals(EstadoSincronizacao.ERRO_SERVIDOR, sincronizador().processarPendentes())

        (0..5).forEach { assertEquals(Situacao.PENDENTE, dao.porEpc("E$it").situacao) }
        assertEquals(Situacao.CADASTRADA, dao.porEpc("OK").situacao)
        assertTrue(dao.porEpc("OK").envioConcluido)
    }
}
