package com.example.rfid_server

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.example.rfid_server.api.HttpClient
import com.example.rfid_server.dados.CadastroDao
import com.example.rfid_server.dados.CadastroEntity
import com.example.rfid_server.dados.FakeCadastroDao
import com.example.rfid_server.dados.FakeInventarioDao
import com.example.rfid_server.dados.FakeLeituraDao
import com.example.rfid_server.dados.InventarioDao
import com.example.rfid_server.dados.InventarioItemEntity
import com.example.rfid_server.dados.LeituraEntity
import com.example.rfid_server.dados.RepositorioCadastros
import com.example.rfid_server.dados.RepositorioInventario
import com.example.rfid_server.service.TagService
import com.example.rfid_server.sincronizacao.Sincronizador
import com.example.rfid_server.sincronizacao.SincronizadorCadastros
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.Executor

/** Achados da revisão da 1.6.2: falhas de gravação que deixavam a tela sem resposta ou perdiam a etiqueta. */
class RevisaoVersao162Test {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val protegido = ExecutorProtegido(Executor { it.run() }, "teste") { _, _ -> }

    @Test
    fun `etiqueta do inventario que nao foi gravada pode ser lida de novo`() {
        val fake = FakeInventarioDao()
        var falhar = false
        val dao = object : InventarioDao by fake {
            override fun inserirItem(item: InventarioItemEntity): Long {
                if (falhar) throw IllegalStateException("database or disk is full")
                return fake.inserirItem(item)
            }
        }
        val repositorio = RepositorioInventario(dao = dao, executor = protegido, solicitarSincronizacao = {}, sessaoAtual = { "sessao1:ana" })
        repositorio.abrir("Sala 101")

        falhar = true
        repositorio.registrar("E1")   // memória cheia: não gravou
        falhar = false
        repositorio.registrar("E1")   // espaço liberado: a mesma etiqueta entra

        assertEquals(listOf("E1"), fake.itens.map { it.epc })
    }

    @Test
    fun `cadastro que nao grava responde a tela em vez de deixar o botao travado`() {
        val fake = FakeCadastroDao()
        val dao = object : CadastroDao by fake {
            override fun salvar(cadastro: CadastroEntity): Long = throw IllegalStateException("database or disk is full")
        }
        val repositorio = RepositorioCadastros(dao = dao, executor = protegido, solicitarSincronizacao = {}, sessaoAtual = { "sessao1:ana" })
        var resposta: Boolean? = null

        repositorio.salvar(cadastro("E1")) { resposta = it }

        assertEquals(false, resposta)
    }

    @Test(timeout = 10_000)
    fun `leitura de etiqueta com cadastro ainda na fila espera o cadastro passar`() {
        val servidor = MockWebServer().apply { start() }
        try {
            val tagService = TagService(HttpClient.criar { "chave" }) { servidor.url("/").toString().trimEnd('/') }
            val cadastros = FakeCadastroDao().apply { salvar(cadastro("E1")) }
            val leituras = FakeLeituraDao(LeituraEntity(epc = "E1", lidoEm = 0), LeituraEntity(epc = "E2", lidoEm = 0))
            servidor.enqueue(MockResponse().setResponseCode(500))   // cadastro da E1: erro no servidor
            servidor.enqueue(MockResponse().setResponseCode(404))   // consulta da E2
            servidor.enqueue(MockResponse().setResponseCode(200))   // E2 como não cadastrada

            Sincronizador(
                dao = leituras, tagService = tagService, executor = Executor { it.run() }, agendarNovaTentativa = {},
                cadastros = SincronizadorCadastros(cadastros, leituras, tagService)
            ).processarPendentes()

            assertEquals(3, servidor.requestCount)
            assertFalse("E1 espera o cadastro", leituras.porEpc("E1").envioConcluido)
            assertTrue(leituras.porEpc("E2").envioConcluido)
        } finally {
            servidor.shutdown()
        }
    }

    private fun cadastro(epc: String) = CadastroEntity(
        epc = epc, codigoInterno = "TI-1", patrimonio = "PAT-1", numeroSerie = "SN-1",
        tipoId = 1, tipoNome = "Notebook", marcaId = 2, marcaNome = "Dell", modeloId = 3, modeloNome = "Latitude",
        criadoEm = 0
    )
}
