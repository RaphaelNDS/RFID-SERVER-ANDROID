package com.example.rfid_server.sincronizacao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.rfid_server.api.HttpClient
import com.example.rfid_server.dados.BancoLocal
import com.example.rfid_server.dados.LeituraDao
import com.example.rfid_server.dados.LeituraEntity
import com.example.rfid_server.service.TagService
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Sincronizador com o banco de verdade (Room em memória, Robolectric) e servidor falso (MockWebServer):
 *  - a transação do lote desfaz de verdade o que já tinha sido gravado quando algo falha no meio;
 *  - duas passadas ao mesmo tempo (worker e app) não mandam a mesma leitura duas vezes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SincronizadorBancoRealTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var banco: BancoLocal
    private lateinit var servidor: MockWebServer

    // EPCs que chegaram ao servidor, na ordem (de qualquer thread)
    private val recebidas: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() {
        banco = Room.inMemoryDatabaseBuilder(context, BancoLocal::class.java).allowMainThreadQueries().build()
        servidor = MockWebServer()
        // Responde cada lote com NAO_CADASTRADA para todas as leituras, devagar: as duas passadas se sobrepõem
        servidor.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path != "/api/android/leituras/lote") return MockResponse().setResponseCode(404)
                val itens = JSONObject(request.body.readUtf8()).getJSONArray("itens")
                val resultados = JSONArray()
                for (i in 0 until itens.length()) {
                    val tag = itens.getJSONObject(i).getString("tag")
                    recebidas += tag
                    resultados.put(JSONObject().put("indice", i).put("tag", tag).put("status", "NAO_CADASTRADA"))
                }
                Thread.sleep(200)
                return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                    .setBody(JSONObject().put("resultados", resultados).toString())
            }
        }
        servidor.start()
    }

    @After
    fun tearDown() {
        servidor.shutdown()
        banco.close()
    }

    private val executorDireto = Executor { it.run() }

    private fun sincronizador(dao: LeituraDao, emTransacao: (() -> Unit) -> Unit) = Sincronizador(
        dao = dao,
        tagService = TagService(HttpClient.criar { "chave-teste" }) { servidor.url("/").toString().trimEnd('/') },
        executor = executorDireto,
        agendarNovaTentativa = {},
        emTransacao = emTransacao
    )

    private fun naFila(): Int = banco.leituraDao().contarPendentesAgora()

    private fun situacoes(): Map<String, String> =
        banco.query("SELECT epc, situacao, envioConcluido FROM leituras", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1) + "/" + c.getInt(2)) }
        }

    /** LeituraDao real que lança na 2ª marcação de enviada (o app morrendo no meio da gravação do lote). */
    private fun daoQueFalhaNoMeio(): LeituraDao {
        val real = banco.leituraDao()
        return object : LeituraDao by real {
            private var enviadas = 0
            override fun marcarEnviada(id: Long) {
                if (++enviadas == 2) throw IllegalStateException("falha simulada no meio do lote")
                real.marcarEnviada(id)
            }
        }
    }

    @Test(timeout = 20_000)
    fun `excecao no meio do lote desfaz tudo o que ja tinha sido gravado na transacao`() {
        listOf("E1", "E2", "E3").forEach { banco.leituraDao().inserir(LeituraEntity(epc = it, lidoEm = 1, sessaoOperador = "sessao-ana")) }

        val erro = runCatching {
            sincronizador(daoQueFalhaNoMeio(), emTransacao = { bloco -> banco.runInTransaction(Runnable { bloco() }) })
                .processarPendentes()
        }.exceptionOrNull()

        assertTrue(erro is IllegalStateException)
        assertEquals("O servidor recebeu o lote", listOf("E1", "E2", "E3"), recebidas.toList())
        assertEquals(
            "E1 tinha sido gravada inteira e E2 pela metade: tudo voltou",
            mapOf("E1" to "PENDENTE/0", "E2" to "PENDENTE/0", "E3" to "PENDENTE/0"), situacoes()
        )
        assertEquals(3, naFila())
    }

    @Test(timeout = 20_000)
    fun `sem a transacao a mesma falha deixaria o lote pela metade (controle do teste acima)`() {
        listOf("E1", "E2", "E3").forEach { banco.leituraDao().inserir(LeituraEntity(epc = it, lidoEm = 1, sessaoOperador = "sessao-ana")) }

        runCatching { sincronizador(daoQueFalhaNoMeio(), emTransacao = { it() }).processarPendentes() }

        assertEquals(mapOf("E1" to "NAO_CADASTRADA/1", "E2" to "NAO_CADASTRADA/0", "E3" to "PENDENTE/0"), situacoes())
    }

    @Test(timeout = 30_000)
    fun `duas passadas ao mesmo tempo mandam cada leitura ao servidor uma vez so`() {
        val epcs = (1..150).map { "E$it" }
        epcs.forEach { banco.leituraDao().inserir(LeituraEntity(epc = it, lidoEm = 1, sessaoOperador = "sessao-ana")) }
        val sincronizador = sincronizador(banco.leituraDao(), emTransacao = { bloco -> banco.runInTransaction(Runnable { bloco() }) })

        val largada = CountDownLatch(1)
        val estados: MutableList<EstadoSincronizacao> = Collections.synchronizedList(mutableListOf())
        val erros: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
        val threads = listOf("worker", "app").map { nome ->
            Thread({
                try {
                    largada.await()
                    estados += sincronizador.processarPendentes()
                } catch (e: Throwable) {
                    erros += e
                }
            }, nome).apply { start() }
        }
        largada.countDown()
        threads.forEach { it.join(TimeUnit.SECONDS.toMillis(25)) }

        assertTrue(erros.toString(), erros.isEmpty())
        assertEquals(listOf(EstadoSincronizacao.SINCRONIZADO, EstadoSincronizacao.SINCRONIZADO), estados.toList())
        assertEquals("Cada leitura uma vez", epcs.sorted(), recebidas.sorted())
        assertEquals("150 leituras em 2 lotes, de uma passada só", 2, servidor.requestCount)
        assertEquals(0, naFila())
    }
}
