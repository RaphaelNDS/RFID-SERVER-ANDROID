package com.example.rfid_server.dados

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersaoPublicadaTest {

    private val sha = "a".repeat(64)

    @Test
    fun `le a resposta do servidor`() {
        val v = VersaoPublicada.deJson(
            """{"versaoNome":"1.5.0","codigo":20,"tamanho":3407872,"sha256":"${sha.uppercase()}","notas":"Correções","publicadaEm":"2026-10-14T10:00:00-03:00"}"""
        )

        assertEquals("1.5.0", v.versaoNome)
        assertEquals(20, v.codigo)
        assertEquals(sha, v.sha256)   // sempre em minúsculas, para comparar com o calculado
        assertEquals("Correções", v.notas)
        assertEquals("3,3 MB", v.tamanhoLegivel())
    }

    @Test
    fun `notas nulas ou vazias viram nulo`() {
        assertNull(VersaoPublicada.deJson("""{"versaoNome":"1","codigo":1,"tamanho":1,"sha256":"$sha","notas":null}""").notas)
        assertNull(VersaoPublicada.deJson("""{"versaoNome":"1","codigo":1,"tamanho":1,"sha256":"$sha","notas":"  "}""").notas)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `sha256 invalido e recusado`() {
        VersaoPublicada.deJson("""{"versaoNome":"1","codigo":1,"tamanho":1,"sha256":"xyz","notas":null}""")
    }

    @Test
    fun `so oferece versao com codigo maior que o instalado`() {
        val v = VersaoPublicada("1.5.0", 20, 1, sha, null)
        assertTrue(v.maisNovaQue(19))
        assertFalse(v.maisNovaQue(20))
        assertFalse(v.maisNovaQue(21))
    }
}
