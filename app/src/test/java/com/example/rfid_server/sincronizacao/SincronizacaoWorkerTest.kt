package com.example.rfid_server.sincronizacao

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.rfid_server.api.HttpClient
import com.example.rfid_server.dados.FakeLeituraDao
import com.example.rfid_server.dados.LeituraDao
import com.example.rfid_server.dados.LeituraEntity
import com.example.rfid_server.service.TagService
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SincronizacaoWorker.doWork com o WorkManager de teste (TestListenableWorkerBuilder) e Robolectric.
 *
 * O worker pega o Sincronizador pelo Sincronizador.get(context), que monta o de produção (banco do app, servidor
 * das Configurações, que é HTTPS com certificado fixado). Para controlar o resultado, o teste põe no lugar da
 * instância única um Sincronizador com DAO em memória e servidor falso (MockWebServer), por reflexão no campo
 * privado `instancia`, e tira no fim.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SincronizacaoWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var servidor: MockWebServer
    private val dao = FakeLeituraDao()
    private var novasTentativasAgendadas = 0

    private val campoInstancia = Sincronizador::class.java.getDeclaredField("instancia").apply { isAccessible = true }

    @Before
    fun setUp() {
        servidor = MockWebServer()
        servidor.start()
    }

    @After
    fun tearDown() {
        campoInstancia.set(null, null)
        servidor.shutdown()
    }

    private fun usarSincronizador(leituras: LeituraDao) {
        val sincronizador = Sincronizador(
            dao = leituras,
            tagService = TagService(HttpClient.criar { "chave-teste" }) { servidor.url("/").toString().trimEnd('/') },
            executor = { it.run() },
            agendarNovaTentativa = { novasTentativasAgendadas++ }
        )
        campoInstancia.set(null, sincronizador)
    }

    private fun rodarWorker(): ListenableWorker.Result =
        TestListenableWorkerBuilder<SincronizacaoWorker>(context).build().doWork()

    @Test(timeout = 20_000)
    fun `fila vazia termina com sucesso sem chamar o servidor`() {
        usarSincronizador(dao)

        assertEquals(ListenableWorker.Result.success(), rodarWorker())
        assertEquals(0, servidor.requestCount)
    }

    @Test(timeout = 20_000)
    fun `fila enviada termina com sucesso`() {
        dao.inserir(LeituraEntity(epc = "E1", lidoEm = 1, sessaoOperador = "sessao-ana"))
        servidor.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("""{"resultados":[{"indice":0,"tag":"E1","status":"NAO_CADASTRADA"}]}""")
        )
        usarSincronizador(dao)

        assertEquals(ListenableWorker.Result.success(), rodarWorker())
        assertEquals(0, dao.contarPendentesAgora())
    }

    @Test(timeout = 20_000)
    fun `servidor com erro pede nova tentativa ao WorkManager sem agendar outra por conta propria`() {
        dao.inserir(LeituraEntity(epc = "E1", lidoEm = 1, sessaoOperador = "sessao-ana"))
        servidor.enqueue(MockResponse().setResponseCode(503))
        usarSincronizador(dao)

        assertEquals(ListenableWorker.Result.retry(), rodarWorker())
        assertEquals("Quem repete é o Result.retry()", 0, novasTentativasAgendadas)
        assertEquals("A leitura continua na fila", 1, dao.contarPendentesAgora())
    }

    @Test(timeout = 20_000)
    fun `excecao na passada pede nova tentativa em vez de falha definitiva`() {
        usarSincronizador(object : LeituraDao by dao {
            override fun pendentes(): List<LeituraEntity> = throw IllegalStateException("database or disk is full")
        })

        assertEquals(ListenableWorker.Result.retry(), rodarWorker())
    }

    @Test
    fun `so repete o que pode resolver sozinho`() {
        assertTrue(SincronizacaoWorker.tentarDeNovo(EstadoSincronizacao.SEM_CONEXAO))
        assertTrue(SincronizacaoWorker.tentarDeNovo(EstadoSincronizacao.ERRO_SERVIDOR))
        assertEquals(false, SincronizacaoWorker.tentarDeNovo(EstadoSincronizacao.SINCRONIZADO))
        assertEquals(false, SincronizacaoWorker.tentarDeNovo(EstadoSincronizacao.CHAVE_INVALIDA))
    }
}
