package com.example.rfid_server.sincronizacao

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.example.rfid_server.api.HttpClient
import com.example.rfid_server.dados.CadastroEntity
import com.example.rfid_server.dados.FakeCadastroDao
import com.example.rfid_server.dados.FakeLeituraDao
import com.example.rfid_server.dados.LeituraEntity
import com.example.rfid_server.dados.Situacao
import com.example.rfid_server.dados.SituacaoCadastro
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

/** Envio dos cadastros feitos no coletor: DAOs em memória + TagService real + servidor HTTP falso. */
class SincronizadorCadastrosTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var servidor: MockWebServer
    private val cadastros = FakeCadastroDao()

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

    private fun cadastro(epc: String, patrimonio: String = "PAT-1") = CadastroEntity(
        epc = epc, codigoInterno = "TI-1", patrimonio = patrimonio, numeroSerie = "SN-1",
        tipoId = 1, tipoNome = "Notebook", marcaId = 2, marcaNome = "Dell", modeloId = 3, modeloNome = "Latitude",
        criadoEm = 0
    )

    @Test(timeout = 10_000)
    fun `cadastro aceito vira leitura cadastrada e sai da fila`() {
        val leituras = FakeLeituraDao(
            LeituraEntity(epc = "E1", lidoEm = 0, situacao = Situacao.NAO_CADASTRADA, envioConcluido = true)
        )
        cadastros.salvar(cadastro("E1"))
        servidor.enqueue(json(200, """{"resultado":"CADASTRADA"}"""))

        assertNull(SincronizadorCadastros(cadastros, leituras, tagService()).processar())

        val corpo = JSONObject(servidor.takeRequest().body.readUtf8())
        assertEquals("E1", corpo.getString("tag"))
        assertEquals("TI-1", corpo.getString("codigoInterno"))
        assertEquals(3, corpo.getLong("modeloId"))

        val leitura = leituras.porEpc("E1")
        assertEquals(Situacao.CADASTRADA, leitura.situacao)
        assertEquals("PAT-1", leitura.patrimonio)
        assertEquals("Latitude", leitura.modelo)
        assertFalse("Volta para a fila para registrar a leitura no servidor", leitura.envioConcluido)
        assertTrue(cadastros.cadastros.isEmpty())
    }

    @Test(timeout = 10_000)
    fun `recusa do servidor guarda o motivo e a fila segue`() {
        cadastros.salvar(cadastro("E1"))
        cadastros.salvar(cadastro("E2"))
        servidor.enqueue(json(409, """{"status":409,"mensagem":"Esta etiqueta já está cadastrada (patrimônio PAT-9)"}"""))
        servidor.enqueue(json(200))

        assertNull(SincronizadorCadastros(cadastros, FakeLeituraDao(), tagService()).processar())

        val recusado = cadastros.porEpcAgora("E1")!!
        assertEquals(SituacaoCadastro.RECUSADO, recusado.situacao)
        assertEquals("Esta etiqueta já está cadastrada (patrimônio PAT-9)", recusado.erro)
        assertNull("E2 foi aceito e saiu da fila", cadastros.porEpcAgora("E2"))
    }

    @Test(timeout = 10_000)
    fun `coletor sem permissao vira recusa e nao trava a sincronizacao`() {
        cadastros.salvar(cadastro("E1"))
        servidor.enqueue(json(403, """{"status":403,"mensagem":"O coletor coletor-02 não tem permissão para cadastrar etiquetas"}"""))

        assertNull(SincronizadorCadastros(cadastros, FakeLeituraDao(), tagService()).processar())

        assertEquals(SituacaoCadastro.RECUSADO, cadastros.porEpcAgora("E1")!!.situacao)
    }

    @Test(timeout = 10_000)
    fun `sem conexao o cadastro continua guardado e interrompe`() {
        cadastros.salvar(cadastro("E1"))
        servidor.shutdown()

        val falha = SincronizadorCadastros(cadastros, FakeLeituraDao(), tagService()).processar()

        assertEquals(MotivoFalha.SEM_CONEXAO, falha?.motivo)
        val guardado = cadastros.porEpcAgora("E1")!!
        assertEquals(SituacaoCadastro.PENDENTE, guardado.situacao)
        assertNotNull(guardado.erro)
    }

    @Test(timeout = 10_000)
    fun `sincronizador cadastra antes e depois envia a leitura com a hora real`() {
        val leituras = FakeLeituraDao(
            LeituraEntity(epc = "E1", lidoEm = 1_760_000_000_000L, situacao = Situacao.NAO_CADASTRADA, envioConcluido = true)
        )
        cadastros.salvar(cadastro("E1"))
        servidor.enqueue(json(200))   // cadastro
        servidor.enqueue(json(200))   // leitura

        val estado = Sincronizador(
            dao = leituras, tagService = tagService(), executor = Executor { it.run() }, agendarNovaTentativa = {},
            cadastros = SincronizadorCadastros(cadastros, leituras, tagService())
        ).processarPendentes()

        assertEquals(EstadoSincronizacao.SINCRONIZADO, estado)
        assertEquals("/api/android/tags", servidor.takeRequest(1, TimeUnit.SECONDS)!!.path)
        val leitura = servidor.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/api/android/leitura", leitura.path)
        val lidoEm = JSONObject(leitura.body.readUtf8()).getString("lidoEm")
        assertEquals(1_760_000_000_000L, OffsetDateTime.parse(lidoEm).toInstant().toEpochMilli())
        assertTrue(leituras.porEpc("E1").envioConcluido)
    }
}
