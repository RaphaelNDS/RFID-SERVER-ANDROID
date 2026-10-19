package com.example.rfid_server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class PoliticaAvisoSessaoTest {

    private val minuto = 60_000L
    private val fim = 1_760_000_000_000L

    @Before
    fun limpar() = PoliticaAvisoSessao.esquecer()

    private fun aviso(restanteMin: Long, token: String = "sessao-ana") =
        PoliticaAvisoSessao.avisoDevido(token, fim, fim - restanteMin * minuto)

    @Test
    fun `sem aviso enquanto falta mais de 30 minutos`() {
        assertNull(aviso(31))
        assertNull(aviso(120))
    }

    @Test
    fun `avisa com 30 e com 5 minutos uma vez cada`() {
        assertEquals(30, aviso(30))
        assertNull("Trocar de tela não repete o aviso", aviso(29))
        assertNull(aviso(6))
        assertEquals(5, aviso(5))
        assertNull(aviso(2))
    }

    @Test
    fun `quem abre a tela com menos de 5 minutos recebe so o ultimo aviso`() {
        assertEquals(5, aviso(4))
        assertNull("O de 30 conta como dado", aviso(3))
    }

    @Test
    fun `sessao renovada tem os avisos dela`() {
        assertEquals(30, aviso(30, token = "sessao-antiga"))
        assertEquals(30, aviso(30, token = "sessao-renovada"))
    }

    @Test
    fun `sessao ja terminada nao gera aviso`() {
        assertNull(aviso(0))
        assertNull(aviso(-5))
    }

    @Test
    fun `tempo restante legivel`() {
        assertEquals("12 min", PoliticaAvisoSessao.restanteLegivel(12 * minuto + 30_000))
        assertEquals("2 h 05 min", PoliticaAvisoSessao.restanteLegivel(125 * minuto))
        assertEquals("0 min", PoliticaAvisoSessao.restanteLegivel(-1))
    }
}
