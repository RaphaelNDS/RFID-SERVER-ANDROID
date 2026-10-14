package com.example.rfid_server.dados

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EsperadoLocalTest {

    private fun item(epc: String, patrimonio: String? = "P-$epc") = ItemEsperado(epc, patrimonio, null, "Notebook", "Dell", null)

    private val esperado = EsperadoLocal(
        local = "Sala 101",
        temReferencia = true,
        finalizadoEm = "2026-10-10T10:30:00-03:00",
        operador = "Ana",
        itens = listOf(item("E1"), item("E2"), item("E3"))
    )

    @Test
    fun `comparacao mostra encontradas faltando e fora da lista`() {
        val c = ComparacaoInventario.comparar(esperado, listOf("E1", "E3", "X9"))

        assertEquals(3, c.esperados)
        assertEquals(2, c.encontradas)
        assertEquals(listOf("E2"), c.faltando.map { it.epc })
        assertEquals(1, c.foraDaLista)
    }

    @Test
    fun `nada lido ainda falta tudo na ordem da lista`() {
        val c = ComparacaoInventario.comparar(esperado, emptyList())

        assertEquals(0, c.encontradas)
        assertEquals(listOf("E1", "E2", "E3"), c.faltando.map { it.epc })
        assertEquals(0, c.foraDaLista)
    }

    @Test
    fun `leitura repetida nao conta duas vezes`() {
        val c = ComparacaoInventario.comparar(esperado, listOf("E1", "E1", "X9", "X9"))

        assertEquals(1, c.encontradas)
        assertEquals(1, c.foraDaLista)
    }

    @Test
    fun `json do servidor e lido e volta igual depois de guardado no coletor`() {
        val lido = EsperadoLocal.deJson(esperado.paraJson())

        assertEquals(esperado, lido)
    }

    @Test
    fun `campos nulos ou em branco viram nulo`() {
        val lido = EsperadoLocal.deJson(
            """{"local":"Sala 1","temReferencia":true,"finalizadoEm":null,"operador":null,
                "itens":[{"tag":"E1","patrimonio":"  ","codigoInterno":null,"tipo":null,"marca":null,"modelo":null}]}"""
        )

        assertNull(lido.itens.single().patrimonio)
        assertNull(lido.operador)
        assertEquals("", lido.itens.single().descricao())
    }

    @Test
    fun `local sem referencia nao tem itens`() {
        val lido = EsperadoLocal.deJson("""{"local":"Sala nova","temReferencia":false,"itens":[]}""")

        assertFalse(lido.temReferencia)
        assertTrue(lido.itens.isEmpty())
    }

    @Test
    fun `descricao da referencia tem data e operador quando houver`() {
        assertEquals("inventário de 10/10/2026 (Ana)", esperado.descricaoReferencia())
        assertEquals("inventário", esperado.copy(finalizadoEm = null, operador = null).descricaoReferencia())
        assertEquals("Notebook · Dell", item("E1").descricao())
    }
}
