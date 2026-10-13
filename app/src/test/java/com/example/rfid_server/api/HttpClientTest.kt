package com.example.rfid_server.api

import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class HttpClientTest {

    private lateinit var servidor: MockWebServer

    @Before
    fun setUp() {
        servidor = MockWebServer()
        servidor.start()
    }

    @After
    fun tearDown() {
        servidor.shutdown()
    }

    private fun get(client: okhttp3.OkHttpClient): Int {
        val request = Request.Builder().url(servidor.url("/api/android/ping")).build()
        return client.newCall(request).execute().use { it.code }
    }

    private fun headerChaveRecebido(): String? =
        servidor.takeRequest(5, TimeUnit.SECONDS)!!.getHeader(HttpClient.HEADER_CHAVE)

    @Test
    fun `envia a chave do coletor no header`() {
        servidor.enqueue(MockResponse().setResponseCode(200))

        get(HttpClient.criar { "minha-chave-123" })

        assertEquals("minha-chave-123", headerChaveRecebido())
    }

    @Test
    fun `sem chave configurada nao envia o header`() {
        servidor.enqueue(MockResponse().setResponseCode(200))

        get(HttpClient.criar { "" })

        assertNull(headerChaveRecebido())
    }

    @Test
    fun `le a chave a cada requisicao para valer a troca feita nas configuracoes`() {
        var chaveAtual = "chave-antiga"
        val client = HttpClient.criar { chaveAtual }
        servidor.enqueue(MockResponse().setResponseCode(200))
        servidor.enqueue(MockResponse().setResponseCode(200))

        get(client)
        chaveAtual = "chave-nova"
        get(client)

        assertEquals("chave-antiga", headerChaveRecebido())
        assertEquals("chave-nova", headerChaveRecebido())
    }

    @Test
    fun `nao segue redirecionamento e devolve o 302 ao app`() {
        servidor.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/login"))
        servidor.enqueue(MockResponse().setResponseCode(200).setBody("<html>Login</html>"))

        val codigo = get(HttpClient.criar { "chave" })

        assertEquals(302, codigo)
        assertEquals(1, servidor.requestCount)
    }

    // ---------- Sessão do operador ----------

    private fun sessaoRecebida(client: okhttp3.OkHttpClient, sessaoDoRegistro: String?): String? {
        servidor.enqueue(MockResponse().setResponseCode(200))
        val builder = Request.Builder().url(servidor.url("/api/android/leitura"))
        sessaoDoRegistro?.let { builder.header(HttpClient.HEADER_SESSAO, it) }
        client.newCall(builder.build()).execute().close()
        return servidor.takeRequest(5, TimeUnit.SECONDS)!!.getHeader(HttpClient.HEADER_SESSAO)
    }

    @Test
    fun `consultas da tela vao com a sessao do operador logado`() {
        val client = HttpClient.criar(sessaoAtual = { "sessao-atual" }) { "chave" }

        assertEquals("sessao-atual", sessaoRecebida(client, sessaoDoRegistro = null))
    }

    @Test
    fun `envio da fila respeita a sessao de quem fez a acao`() {
        val client = HttpClient.criar(sessaoAtual = { "sessao-atual" }) { "chave" }

        assertEquals("sessao-de-ontem", sessaoRecebida(client, sessaoDoRegistro = "sessao-de-ontem"))
    }

    @Test
    fun `registro antigo sem operador vai sem o header e nao pega a sessao atual`() {
        val client = HttpClient.criar(sessaoAtual = { "sessao-atual" }) { "chave" }

        assertNull(sessaoRecebida(client, sessaoDoRegistro = ""))
    }

    @Test
    fun `sem ninguem logado a consulta vai sem sessao`() {
        assertNull(sessaoRecebida(HttpClient.criar { "chave" }, sessaoDoRegistro = null))
    }
}
