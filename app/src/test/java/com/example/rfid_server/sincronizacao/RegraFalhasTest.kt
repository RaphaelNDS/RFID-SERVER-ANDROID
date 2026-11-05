package com.example.rfid_server.sincronizacao

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.example.rfid_server.ExecutorProtegido
import com.example.rfid_server.api.HttpClient
import com.example.rfid_server.dados.CadastroEntity
import com.example.rfid_server.dados.FakeCadastroDao
import com.example.rfid_server.dados.FakeLeituraDao
import com.example.rfid_server.dados.LeituraEntity
import com.example.rfid_server.service.MotivoFalha
import com.example.rfid_server.service.TagService
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.Executor

/**
 * 1.6.2: erro do servidor num item só não trava a fila; prazo de 24 h da sessão recusada não recomeça a cada
 * passada; exceção na thread de gravação não derruba o app.
 */
class RegraFalhasTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var servidor: MockWebServer
    private val cadastros = FakeCadastroDao()
    private var agora = 10_000_000_000L

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

    private fun json(codigo: Int, corpo: String = "{}") =
        MockResponse().setResponseCode(codigo).setHeader("Content-Type", "application/json").setBody(corpo)

    private fun cadastro(epc: String) = CadastroEntity(
        epc = epc, codigoInterno = "TI-$epc", patrimonio = "PAT-$epc", numeroSerie = "SN-1",
        tipoId = 1, tipoNome = "Notebook", marcaId = 2, marcaNome = "Dell", modeloId = 3, modeloNome = "Latitude",
        criadoEm = 0
    )

    // ---------- Erro do servidor num item ----------

    @Test(timeout = 10_000)
    fun `cadastro com erro no servidor fica na fila e os outros cadastros seguem`() {
        cadastros.salvar(cadastro("RUIM"))
        cadastros.salvar(cadastro("BOM"))
        servidor.enqueue(json(500))
        servidor.enqueue(json(200))

        val falha = SincronizadorCadastros(cadastros, FakeLeituraDao(), tagService()).processar()

        assertEquals(MotivoFalha.ERRO_SERVIDOR, falha?.motivo)
        assertEquals(listOf("RUIM"), cadastros.cadastros.map { it.epc })
        assertNotNull("Fica com o erro guardado", cadastros.cadastros.single().erro)
    }

    @Test(timeout = 10_000)
    fun `cadastro com erro no servidor nao segura as leituras`() {
        cadastros.salvar(cadastro("RUIM"))
        val leituras = FakeLeituraDao(LeituraEntity(epc = "E1", lidoEm = 0))
        servidor.enqueue(json(500))   // cadastro
        servidor.enqueue(json(404))   // consulta da E1
        servidor.enqueue(json(200))   // E1 como não cadastrada

        val estado = Sincronizador(
            dao = leituras, tagService = tagService(), executor = Executor { it.run() }, agendarNovaTentativa = {},
            cadastros = SincronizadorCadastros(cadastros, leituras, tagService())
        ).processarPendentes()

        assertEquals(EstadoSincronizacao.ERRO_SERVIDOR, estado)
        assertTrue("A leitura foi enviada mesmo com o cadastro parado", leituras.porEpc("E1").envioConcluido)
    }

    @Test
    fun `so falhas do ambiente param tudo e erros seguidos param a etapa`() {
        val semRede = com.example.rfid_server.service.RespostaServidor.Falha(MotivoFalha.SEM_CONEXAO, "x")
        val doServidor = com.example.rfid_server.service.RespostaServidor.Falha(MotivoFalha.ERRO_SERVIDOR, "x")
        assertTrue(RegraFalhas.paraTudo(semRede))
        assertFalse(RegraFalhas.paraTudo(doServidor))

        val contador = RegraFalhas.Contador()
        assertFalse(contador.falhou(doServidor))
        contador.deuCerto()
        repeat(RegraFalhas.LIMITE_ERROS_SEGUIDOS - 1) { assertFalse(contador.falhou(doServidor)) }
        assertTrue(contador.falhou(doServidor))
        assertEquals(doServidor, contador.primeira)
    }

    // ---------- Prazo da sessão recusada no lote ----------

    private fun loteRecusado(motivo: String) = json(200, JSONObject().put("resultados", JSONArray().put(
        JSONObject().put("indice", 0).put("tag", "E1").put("status", "RECUSADA").put("mensagem", "Sessão desconhecida").put("motivo", motivo)
    )).toString())

    @Test(timeout = 10_000)
    fun `prazo de 24 h da sessao recusada no lote nao recomeca a cada passada`() {
        val leituras = FakeLeituraDao(LeituraEntity(epc = "E1", lidoEm = agora, sessaoOperador = "sessao-ana"))
        val recusas = RecusasDeSessaoEmMemoria()
        fun sincronizador() = Sincronizador(
            dao = leituras, tagService = tagService(), executor = Executor { it.run() }, agendarNovaTentativa = {},
            relogio = { agora }, recusasDeSessao = recusas
        )

        servidor.enqueue(loteRecusado("DESCONHECIDA"))
        assertEquals(EstadoSincronizacao.SESSAO_RECUSADA, sincronizador().processarPendentes())
        val primeira = recusas.primeiraRecusa("sessao-ana")
        assertEquals(agora, primeira)

        // Outras passadas no mesmo dia: o prazo continua contando da primeira recusa
        agora += 60 * 60 * 1000
        servidor.enqueue(loteRecusado("DESCONHECIDA"))
        sincronizador().processarPendentes()
        assertEquals("Não recomeça", primeira, recusas.primeiraRecusa("sessao-ana"))
        assertFalse(leituras.porEpc("E1").envioConcluido)

        // Passou de 24 h: vira recusada de vez (antes ficava na fila para sempre)
        agora += Sincronizador.PRAZO_SESSAO_RECUSADA_MS
        servidor.enqueue(loteRecusado("DESCONHECIDA"))
        sincronizador().processarPendentes()
        assertTrue(leituras.porEpc("E1").recusada)
    }

    // ---------- Exceção na thread de gravação ----------

    @Test
    fun `excecao na tarefa nao derruba a thread e vai para o tratamento de erro`() {
        val erros = mutableListOf<String>()
        val executor = ExecutorProtegido(Executor { it.run() }, "gravar as leituras") { nome, e -> erros += "$nome: ${e.message}" }
        var depois = false

        executor.execute { throw IllegalStateException("database or disk is full") }
        executor.execute { depois = true }

        assertEquals(listOf("gravar as leituras: database or disk is full"), erros)
        assertTrue("A próxima tarefa roda normalmente", depois)
    }

    @Test
    fun `leitura que nao foi gravada pode ser lida de novo`() {
        var falhar = true
        val dao = object : com.example.rfid_server.dados.LeituraDao by FakeLeituraDao() {
            val gravadas = mutableListOf<String>()
            override fun inserir(leitura: LeituraEntity): Long {
                if (falhar) throw IllegalStateException("database or disk is full")
                gravadas += leitura.epc
                return gravadas.size.toLong()
            }
        }
        val repositorio = com.example.rfid_server.dados.RepositorioLeituras(
            dao = dao, executor = ExecutorProtegido(Executor { it.run() }, "teste") { _, _ -> },
            solicitarSincronizacao = {}, sessaoAtual = { "sessao-ana" }
        )

        repositorio.registrar("E1")   // memória cheia: não gravou
        falhar = false
        repositorio.registrar("E1")   // espaço liberado: a mesma etiqueta é gravada

        assertEquals(listOf("E1"), dao.gravadas)
    }
}
