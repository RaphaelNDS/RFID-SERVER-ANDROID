package com.example.rfid_server.api

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/** Cabeçalhos com o estado da fila e do aparelho, conferidos no que chega ao servidor (MockWebServer). */
class DiagnosticoColetorTest {

    private lateinit var servidor: MockWebServer

    // Relógio do cache, controlado pelo teste
    private var agora = 1_000L
    private var leiturasDaFila = 0
    private var fila = EstadoFila(pendentes = 7, leituraMaisAntigaEm = HORA_LEITURA)
    private var falharFila = false
    private var ultimaOk: Long? = HORA_SINC

    private val zona = ZoneOffset.ofHours(-3)

    @Before
    fun setUp() {
        servidor = MockWebServer()
        servidor.start()
    }

    @After
    fun tearDown() {
        servidor.shutdown()
    }

    private fun diagnostico(modelo: String = "TC21 Zebra") = DiagnosticoColetor(
        lerFila = {
            leiturasDaFila++
            if (falharFila) throw IllegalStateException("banco com defeito")
            fila
        },
        espacoLivreMb = { 512 },
        versaoAndroid = "11",
        modelo = modelo,
        ultimaSincOk = { ultimaOk },
        agoraMs = { agora },
        zona = zona
    )

    private fun cliente(diagnostico: DiagnosticoColetor): OkHttpClient =
        HttpClient.criar(diagnostico = diagnostico::cabecalhos) { "chave" }

    private fun requisitar(client: OkHttpClient): RecordedRequest {
        servidor.enqueue(MockResponse().setResponseCode(200))
        client.newCall(Request.Builder().url(servidor.url("/api/android/ping")).build()).execute().use { }
        return servidor.takeRequest(5, TimeUnit.SECONDS)!!
    }

    @Test
    fun `envia fila, ultima sincronizacao, espaco livre e aparelho`() {
        val recebida = requisitar(cliente(diagnostico()))

        assertEquals("7", recebida.getHeader("X-Fila-Pendentes"))
        assertEquals(HORA_LEITURA, OffsetDateTime.parse(recebida.getHeader("X-Fila-Mais-Antiga")).toInstant().toEpochMilli())
        assertEquals("2026-11-05T09:00:00-03:00", recebida.getHeader("X-Ultima-Sinc-Ok"))
        assertEquals("512", recebida.getHeader("X-Espaco-Livre"))
        assertEquals("11", recebida.getHeader("X-Android"))
        assertEquals("TC21 Zebra", recebida.getHeader("X-Modelo"))
        // Os cabeçalhos de antes continuam
        assertEquals("chave", recebida.getHeader(HttpClient.HEADER_CHAVE))
    }

    @Test
    fun `fila e lida no maximo a cada 30 segundos`() {
        val client = cliente(diagnostico())

        requisitar(client)
        fila = EstadoFila(pendentes = 3, leituraMaisAntigaEm = null)
        agora += 29_000
        val dentroDaValidade = requisitar(client)
        assertEquals(1, leiturasDaFila)
        assertEquals("Ainda o valor em cache", "7", dentroDaValidade.getHeader("X-Fila-Pendentes"))

        agora += 1_000
        val depois = requisitar(client)
        assertEquals(2, leiturasDaFila)
        assertEquals("3", depois.getHeader("X-Fila-Pendentes"))
        assertNull("Sem leitura pendente, sem a hora da mais antiga", depois.getHeader("X-Fila-Mais-Antiga"))
    }

    @Test
    fun `banco com defeito nao impede a requisicao e nao insiste a cada envio`() {
        falharFila = true
        val client = cliente(diagnostico())

        val recebida = requisitar(client)
        requisitar(client)

        assertNull(recebida.getHeader("X-Fila-Pendentes"))
        assertEquals("512", recebida.getHeader("X-Espaco-Livre"))
        assertEquals(1, leiturasDaFila)
    }

    @Test
    fun `depois de uma falha vai o ultimo valor conhecido`() {
        val client = cliente(diagnostico())
        requisitar(client)

        falharFila = true
        agora += 30_000
        assertEquals("7", requisitar(client).getHeader("X-Fila-Pendentes"))
    }

    @Test
    fun `sem sincronizacao completa desde que o app abriu nao envia a hora`() {
        ultimaOk = null
        assertNull(requisitar(cliente(diagnostico())).getHeader("X-Ultima-Sinc-Ok"))
    }

    @Test
    fun `modelo com acento nao derruba a requisicao`() {
        assertEquals("Coletor ?nico", requisitar(cliente(diagnostico(modelo = "Coletor Único"))).getHeader("X-Modelo"))
    }

    @Test
    fun `ultima sincronizacao ok guarda o momento informado`() {
        UltimaSincronizacaoOk.registrar(HORA_SINC)
        try {
            assertEquals(HORA_SINC, UltimaSincronizacaoOk.momento)
        } finally {
            UltimaSincronizacaoOk.esquecer()
        }
        assertNull(UltimaSincronizacaoOk.momento)
    }

    private companion object {
        /** 05/11/2026 12:00 UTC (09:00 em -03:00). */
        const val HORA_SINC = 1_793_880_000_000L
        const val HORA_LEITURA = 1_793_800_000_123L
    }
}
