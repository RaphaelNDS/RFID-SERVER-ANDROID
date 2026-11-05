package com.example.rfid_server.api

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.example.rfid_server.erros.TipoErro
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Relógio do coletor conferido com o do servidor pelo cabeçalho Date das respostas. */
class RelogioServidorTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var servidor: MockWebServer
    private val erros = mutableListOf<Pair<String, String>>()

    @Before
    fun setUp() {
        RelogioServidor.reiniciar()
        servidor = MockWebServer()
        servidor.start()
    }

    @After
    fun tearDown() {
        servidor.shutdown()
        RelogioServidor.reiniciar()
    }

    private fun cliente(): OkHttpClient = HttpClient.criar(registrarErro = { tipo, msg -> erros += tipo to msg }) { "chave" }

    private fun get(client: OkHttpClient) {
        client.newCall(Request.Builder().url(servidor.url("/api/android/ping")).build()).execute().close()
    }

    /** Resposta com o cabeçalho Date do servidor deslocado [minutos] em relação ao relógio desta máquina. */
    private fun respostaComData(minutos: Long) = MockResponse().setResponseCode(200).setHeader(
        "Date", DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC).plusMinutes(minutos))
    )

    @Test
    fun `desvio desconta metade do tempo da requisicao`() {
        // Pedido saiu em 1000, resposta chegou em 3000: o servidor carimbou por volta de 2000 do aparelho
        assertEquals(0, RelogioServidor.calcularDesvio(dataServidorMs = 2_000, inicioMs = 1_000, fimMs = 3_000))
        assertEquals(60_000, RelogioServidor.calcularDesvio(dataServidorMs = 2_000 - 60_000, inicioMs = 1_000, fimMs = 3_000))
        assertEquals(-60_000, RelogioServidor.calcularDesvio(dataServidorMs = 62_000, inicioMs = 1_000, fimMs = 3_000))
    }

    @Test
    fun `servidor com a mesma hora nao gera aviso nem erro`() {
        servidor.enqueue(respostaComData(0))

        get(cliente())

        val desvio = RelogioServidor.desvioMs
        assertNotNull("Mediu pelo cabeçalho Date", desvio)
        assertTrue("Diferença só do arredondamento do Date (segundos): $desvio", abs(desvio!!) < 5_000)
        assertNotNull(RelogioServidor.medidoEm)
        assertNull(RelogioServidor.aviso(desvio))
        assertTrue(erros.isEmpty())
    }

    @Test
    fun `coletor adiantado registra o erro no maximo uma vez por hora`() {
        // Servidor 10 min atrás do coletor = coletor 10 min adiantado
        servidor.enqueue(respostaComData(-10))
        servidor.enqueue(respostaComData(-10))
        val client = cliente()

        get(client)
        get(client)

        val desvio = RelogioServidor.desvioMs!!
        assertTrue("~10 min adiantado: $desvio", abs(desvio - 10 * 60_000) < 5_000)
        assertEquals("LiveData para a tela", desvio, RelogioServidor.desvio.value)
        assertEquals("Uma vez só na mesma hora", listOf(TipoErro.FALHA_GERAL to "Relógio do coletor 10 min adiantado em relação ao servidor"), erros)
    }

    @Test
    fun `coletor atrasado tambem e registrado`() {
        servidor.enqueue(respostaComData(30))

        get(cliente())

        assertEquals(listOf(TipoErro.FALHA_GERAL to "Relógio do coletor 30 min atrasado em relação ao servidor"), erros)
    }

    @Test
    fun `resposta sem cabecalho Date nao mede`() {
        servidor.enqueue(MockResponse().setResponseCode(200))

        get(cliente())

        assertNull(RelogioServidor.desvioMs)
        assertTrue(erros.isEmpty())
    }

    @Test
    fun `envia a hora do aparelho em ISO-8601`() {
        servidor.enqueue(MockResponse().setResponseCode(200))
        val antes = System.currentTimeMillis()

        get(cliente())

        val enviada = servidor.takeRequest(5, TimeUnit.SECONDS)!!.getHeader(HttpClient.HEADER_RELOGIO_COLETOR)
        val momento = OffsetDateTime.parse(enviada).toInstant().toEpochMilli()
        assertTrue("Hora do aparelho: $enviada", abs(momento - antes) < 5_000)
    }

    @Test
    fun `registro do erro volta depois de uma hora`() {
        var monotonico = 0L
        var registros = 0
        val registrar: (String, String) -> Unit = { _, _ -> registros++ }

        RelogioServidor.registrarMedicao(5 * 60_000, 0, registrar) { monotonico }
        monotonico += RelogioServidor.INTERVALO_REGISTRO_MS - 1
        RelogioServidor.registrarMedicao(5 * 60_000, 0, registrar) { monotonico }
        assertEquals(1, registros)

        monotonico += 1
        RelogioServidor.registrarMedicao(5 * 60_000, 0, registrar) { monotonico }
        assertEquals(2, registros)
    }

    @Test
    fun `aviso para a tela so acima de 2 minutos`() {
        assertNull(RelogioServidor.aviso(null))
        assertNull(RelogioServidor.aviso(RelogioServidor.LIMITE_AVISO_MS))
        assertNull(RelogioServidor.aviso(-RelogioServidor.LIMITE_AVISO_MS))
        assertEquals(
            "O relógio do coletor está 7 min adiantado: acerte a data e a hora nas configurações do Android",
            RelogioServidor.aviso(7 * 60_000)
        )
        assertEquals(
            "O relógio do coletor está 3 min atrasado: acerte a data e a hora nas configurações do Android",
            RelogioServidor.aviso(-3 * 60_000 - 10_000)
        )
        assertEquals(
            "O relógio do coletor está 2 h 5 min adiantado: acerte a data e a hora nas configurações do Android",
            RelogioServidor.aviso(125 * 60_000)
        )
        assertEquals(
            "O relógio do coletor está 3 dia(s) atrasado: acerte a data e a hora nas configurações do Android",
            RelogioServidor.aviso(-3L * 24 * 60 * 60_000)
        )
    }
}
