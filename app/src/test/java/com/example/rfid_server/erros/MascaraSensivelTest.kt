package com.example.rfid_server.erros

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MascaraSensivelTest {

    private fun limpar(texto: String) = MascaraSensivel.limpar(texto)

    @Test
    fun `JSON com aspas na chave e no valor`() {
        assertEquals("""{"email":"op@x.com","senha":"***"}""", limpar("""{"email":"op@x.com","senha":"abc123"}"""))
        assertEquals("""{"senha": "***", "ok": true}""", limpar("""{"senha": "abc 123", "ok": true}"""))
        assertEquals("""{"token":"***"}""", limpar("""{"token":"tk-9"}"""))
        assertEquals("""{'password':'***'}""", limpar("""{'password':'s3gr3d0'}"""))
        assertEquals("""{"chave": ***}""", limpar("""{"chave": 123456}"""))
    }

    @Test
    fun `query string`() {
        assertEquals("/api/login?email=op&senha=***&x=1", limpar("/api/login?email=op&senha=abc123&x=1"))
        assertEquals("?token=***&key=***", limpar("?token=tk1&key=k2"))
    }

    @Test
    fun `headers`() {
        assertEquals("X-Sessao-Operador: ***", limpar("X-Sessao-Operador: abc123"))
        assertEquals("X-Chave-Dispositivo=***", limpar("X-Chave-Dispositivo=abc123"))
        assertEquals("""{"X-Chave-Dispositivo":"***"}""", limpar("""{"X-Chave-Dispositivo":"abc123"}"""))
        assertEquals("Authorization: Bearer ***", limpar("Authorization: Bearer abc.def.ghi"))
        assertEquals("password: ***", limpar("password: hunter2"))
    }

    @Test
    fun `sequencia longa vira mascara`() {
        val token = "a".repeat(40)
        assertEquals("falhou com ***", limpar("falhou com $token"))
    }

    @Test
    fun `texto comum nao e alterado`() {
        val textos = listOf(
            "A senha está incorreta",
            "Token expirado: faça login de novo",
            "Chave do coletor inválida",
            "Falha ao conectar em 10.0.0.5:8443 (timeout)",
            """{"tag":"E2801170","local":"Estoque"}""",
            "java.net.SocketTimeoutException: timeout"
        )
        textos.forEach { assertEquals(it, limpar(it)) }
    }

    @Test
    fun `nao vaza o valor em nenhum formato`() {
        listOf("senha=abc123", "\"senha\":\"abc123\"", "'senha' : 'abc123'", "SENHA: abc123", "senha:\"abc123\"")
            .forEach { assertFalse(it, limpar(it).contains("abc123")) }
    }
}
