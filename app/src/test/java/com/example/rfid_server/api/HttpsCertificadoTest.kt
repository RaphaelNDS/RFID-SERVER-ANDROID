package com.example.rfid_server.api

import com.example.rfid_server.AppConfig
import com.example.rfid_server.service.MotivoFalha
import com.example.rfid_server.service.RespostaServidor
import com.example.rfid_server.service.ResultadoConexao
import com.example.rfid_server.service.StatusConexao
import com.example.rfid_server.service.TagService
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * HTTPS com certificado próprio (autoassinado) do servidor: o coletor só conversa com o certificado
 * cuja impressão digital foi confirmada. Servidor falso em HTTPS de verdade (MockWebServer + okhttp-tls).
 */
class HttpsCertificadoTest {

    private lateinit var servidor: MockWebServer

    // Como o certificado gerado pelo servidor RFID: autoassinado, sem autoridade
    private val certificadoServidor = HeldCertificate.Builder().commonName("Sistema RFID").addSubjectAlternativeName("localhost").build()
    private val impressaoServidor = ConfiancaCertificado.impressaoDigital(certificadoServidor.certificate)

    @Before
    fun setUp() {
        servidor = MockWebServer()
        servidor.useHttps(HandshakeCertificates.Builder().heldCertificate(certificadoServidor).build().sslSocketFactory(), false)
        servidor.start()
    }

    @After
    fun tearDown() {
        servidor.shutdown()
    }

    private fun get(impressaoConfirmada: String?): Int {
        servidor.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))
        val client = HttpClient.criar(impressaoConfirmada = { impressaoConfirmada }) { "chave" }
        return client.newCall(Request.Builder().url(servidor.url("/api/android/ping")).build()).execute().use { it.code }
    }

    private fun falhaDe(impressaoConfirmada: String?): Throwable =
        try {
            get(impressaoConfirmada)
            fail("A conexão deveria ter sido recusada")
            throw AssertionError()
        } catch (e: IOException) {
            ConfiancaCertificado.recusaNaCausa(e) ?: throw AssertionError("Falhou por outro motivo", e)
        }

    @Test
    fun `com o certificado confirmado a conexao HTTPS funciona`() {
        assertEquals(200, get(impressaoServidor))
    }

    @Test
    fun `sem confirmacao a conexao e recusada e a impressao digital fica disponivel para conferir`() {
        val recusa = falhaDe(impressaoConfirmada = null) as CertificadoNaoConfirmadoException

        assertEquals(impressaoServidor, recusa.impressaoDigital)
        assertEquals(0, servidor.requestCount) // nada foi enviado ao servidor não confirmado
    }

    @Test
    fun `certificado diferente do confirmado e recusado`() {
        val outro = ConfiancaCertificado.impressaoDigital(HeldCertificate.Builder().build().certificate)

        val recusa = falhaDe(impressaoConfirmada = outro) as CertificadoDiferenteException

        assertEquals(impressaoServidor, recusa.impressaoDigital)
        assertEquals(outro, recusa.esperada)
        assertEquals(0, servidor.requestCount)
    }

    @Test
    fun `impressao digital confirmada vale em minusculas tambem`() {
        assertEquals(200, get(impressaoServidor.lowercase()))
    }

    @Test
    fun `impressao digital no mesmo formato do servidor`() {
        assertTrue(impressaoServidor.matches(Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}")))
    }

    // ---------- TagService ----------

    private fun service(impressaoConfirmada: String?) =
        TagService(HttpClient.criar(impressaoConfirmada = { impressaoConfirmada }) { "chave" }) {
            servidor.url("/").toString().trimEnd('/')
        }

    @Test
    fun `teste de conexao devolve a impressao digital para a tela de Configuracoes`() {
        val latch = CountDownLatch(1)
        var resultado: ResultadoConexao? = null

        service(impressaoConfirmada = null).testarConexao { resultado = it; latch.countDown() }
        latch.await(5, TimeUnit.SECONDS)

        assertEquals(ResultadoConexao(StatusConexao.CERTIFICADO_NAO_CONFIRMADO, impressaoServidor), resultado)
    }

    @Test
    fun `envio da fila com certificado nao confirmado tem motivo proprio e para a sincronizacao`() {
        val resposta = service(impressaoConfirmada = null).enviarLeituraAgora("E1", 0L, "sessao")

        assertEquals(MotivoFalha.CERTIFICADO, (resposta as RespostaServidor.Falha).motivo)
    }

    @Test
    fun `envio com o certificado confirmado chega ao servidor`() {
        servidor.enqueue(MockResponse().setResponseCode(200))

        assertEquals(RespostaServidor.Sucesso(Unit), service(impressaoServidor).enviarLeituraAgora("E1", 0L, "sessao"))
    }

    // ---------- Endereço ----------

    @Test
    fun `endereco do servidor e sempre HTTPS na 8443 se a porta nao for informada`() {
        assertEquals("https://192.168.0.10:8443", AppConfig.montarEndereco("192.168.0.10"))
        assertEquals("https://192.168.0.10:9443", AppConfig.montarEndereco("192.168.0.10:9443"))
        assertEquals("https://192.168.0.10:8443", AppConfig.montarEndereco("http://192.168.0.10"))
        assertEquals("https://rfid-servidor:8443", AppConfig.montarEndereco(" rfid-servidor/ "))
    }
}
