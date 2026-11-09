package com.example.rfid_server.dados

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EsperadoGuardadoTest {

    @get:Rule
    val pasta = TemporaryFolder()

    private fun guardado() = EsperadoGuardado(pasta.root.resolve("esperado"))

    @Test
    fun `local nunca baixado devolve null`() {
        assertNull(guardado().ler("Sala 101"))
    }

    @Test
    fun `salva e le ignorando maiusculas e espacos extras`() {
        guardado().salvar("Sala 101", """{"local":"Sala 101"}""")

        assertEquals("""{"local":"Sala 101"}""", guardado().ler("  sala   101 "))
    }

    @Test
    fun `nome do local com barra e acento vira nome de arquivo valido`() {
        guardado().salvar("Almoxarifado / Prédio B", "x")

        assertEquals("x", guardado().ler("almoxarifado / prédio b"))
        assertTrue(pasta.root.resolve("esperado").listFiles()!!.all { it.name.matches(Regex("[0-9a-f]{64}\\.json")) })
    }

    @Test
    fun `salvar de novo substitui a lista`() {
        guardado().salvar("Sala 101", "antiga")
        guardado().salvar("Sala 101", "nova")

        assertEquals("nova", guardado().ler("Sala 101"))
        assertEquals(1, pasta.root.resolve("esperado").listFiles()!!.size)
    }

    @Test
    fun `importa as listas das configuracoes sem sobrescrever a que ja esta em arquivo`() {
        guardado().salvar("Sala 101", "do arquivo")

        val trazidas = guardado().importar(
            mapOf(EsperadoGuardado.chave("Sala 101") to "das configurações", EsperadoGuardado.chave("Sala 202") to "só nas configurações")
        )

        assertEquals(1, trazidas)
        assertEquals("do arquivo", guardado().ler("Sala 101"))
        assertEquals("só nas configurações", guardado().ler("SALA 202"))
    }

    @Test
    fun `etag fica junto da lista e some quando a lista e trocada sem etag`() {
        val g = guardado()
        assertNull("Sem lista não há ETag", g.lerEtag("Sala 1"))

        g.salvar("Sala 1", "lista-1", "\"e1\"")
        assertEquals("\"e1\"", g.lerEtag(" sala 1 "))

        g.salvarEtag("Sala 1", "\"e2\"")
        assertEquals("lista-1", g.ler("Sala 1"))
        assertEquals("\"e2\"", g.lerEtag("Sala 1"))

        // Servidor antigo (sem ETag) mandou outra lista: o ETag antigo não pode ficar valendo para ela
        g.salvar("Sala 1", "lista-2")
        assertNull(g.lerEtag("Sala 1"))
    }

    @Test
    fun `etag sem lista guardada nao e gravado`() {
        guardado().salvarEtag("Sala 9", "\"x\"")
        assertNull(guardado().lerEtag("Sala 9"))
    }
}
