package com.example.rfid_server

import com.example.rfid_server.api.HttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Token da sessão fora do banco local: o banco guarda a referência e o token fica cifrado no cofre. */
class CofreSessoesTest {

    private class CifraFalsa(var falhar: Boolean = false) : Cifra {
        override fun cifrar(texto: String) = "X" + texto.reversed()
        override fun decifrar(cifrado: String): String {
            if (falhar) throw IllegalStateException("chave perdida")
            return cifrado.removePrefix("X").reversed()
        }
    }

    private val guardado = mutableMapOf<String, String>()
    private val cifra = CifraFalsa()
    private var agora = 1_000_000_000_000L

    private fun cofre() = CofreSessoes(
        ler = { guardado[it] },
        gravar = { guardado.putAll(it) },
        todas = { guardado.toMap() },
        apagar = { chaves -> chaves.forEach { guardado.remove(it) } },
        cifra = { cifra },
        relogio = { agora }
    )

    @Test
    fun `referencia nao contem o token e e sempre a mesma para o mesmo token`() {
        val cofre = cofre()
        val ref = cofre.referencia("token-secreto-da-ana")

        assertTrue(ref.startsWith(CofreSessoes.PREFIXO))
        assertFalse(ref.contains("token-secreto-da-ana"))
        assertEquals("Mesmo operador, mesma referência: o agrupamento por operador continua", ref, cofre.referencia("token-secreto-da-ana"))
        assertNotEquals(ref, cofre.referencia("token-do-bruno"))
    }

    @Test
    fun `token fica cifrado no cofre e volta pela referencia mesmo depois de o app reabrir`() {
        val ref = cofre().referencia("token-ana")

        assertTrue(guardado.values.none { it == "token-ana" })
        assertEquals("Cofre novo (app reaberto): lê do arquivo", "token-ana", cofre().token(ref))
    }

    @Test
    fun `valor que nao e referencia passa direto (linhas de antes da 1_6_1)`() {
        assertEquals("token-puro-antigo", cofre().token("token-puro-antigo"))
    }

    @Test
    fun `referencia que nao da para resolver volta igual e o servidor recusa a sessao`() {
        val ref = cofre().referencia("token-ana")
        cifra.falhar = true

        assertEquals(ref, cofre().token(ref))
    }

    @Test
    fun `tokens com mais de 30 dias saem do cofre`() {
        val antiga = cofre().referencia("token-antigo")
        agora += CofreSessoes.GUARDA_MS + 1
        val nova = cofre().referencia("token-novo")

        assertEquals(1, cofre().limparAntigas())

        assertEquals("Apagada: volta a referência", antiga, cofre().token(antiga))
        assertEquals("token-novo", cofre().token(nova))
    }

    @Test(timeout = 10_000)
    fun `envio da fila leva o token e nao a referencia`() {
        val servidor = MockWebServer().apply { enqueue(MockResponse().setResponseCode(200)); start() }
        try {
            val cofre = cofre()
            val ref = cofre.referencia("token-ana")
            val client = HttpClient.criar(chaveDispositivo = { "chave" }, resolverSessao = { cofre.token(it) })

            client.newCall(Request.Builder().url(servidor.url("/x")).header(HttpClient.HEADER_SESSAO, ref).build()).execute().close()

            assertEquals("token-ana", servidor.takeRequest(1, TimeUnit.SECONDS)!!.getHeader(HttpClient.HEADER_SESSAO))
        } finally {
            servidor.shutdown()
        }
    }
}
