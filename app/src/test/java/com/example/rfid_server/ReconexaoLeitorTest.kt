package com.example.rfid_server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconexaoLeitorTest {

    @Test
    fun `espera cresce com as tentativas e para em 1 minuto`() {
        assertEquals(3_000L, ReconexaoLeitor.esperaMs(1))
        assertEquals(3_000L, ReconexaoLeitor.esperaMs(2))
        assertEquals(5_000L, ReconexaoLeitor.esperaMs(3))
        assertEquals(5_000L, ReconexaoLeitor.esperaMs(5))
        assertEquals(10_000L, ReconexaoLeitor.esperaMs(6))
        assertEquals(10_000L, ReconexaoLeitor.esperaMs(17))
        assertEquals(30_000L, ReconexaoLeitor.esperaMs(18))
        assertEquals(30_000L, ReconexaoLeitor.esperaMs(35))
        assertEquals(60_000L, ReconexaoLeitor.esperaMs(36))
        assertEquals(60_000L, ReconexaoLeitor.esperaMs(500))
    }

    @Test
    fun `nos primeiros 2 minutos sem leitor ainda tenta no maximo a cada 10 segundos`() {
        val esperaAte17 = (1..17).sumOf { ReconexaoLeitor.esperaMs(it) }
        assertTrue("Cerca de 2 min de tentativas rápidas: $esperaAte17 ms", esperaAte17 in 120_000L..160_000L)
    }

    @Test
    fun `espera nunca diminui de uma tentativa para a seguinte`() {
        for (t in 1..50) assertTrue(ReconexaoLeitor.esperaMs(t + 1) >= ReconexaoLeitor.esperaMs(t))
    }

    @Test
    fun `log registra as primeiras falhas e depois cerca de uma por minuto`() {
        val registradas = (1..30).filter { ReconexaoLeitor.deveRegistrar(it) }

        assertEquals(listOf(1, 2, 3, 6, 12, 18, 24, 30), registradas)
    }

    @Test
    fun `potencia 100 e a maxima e as outras ficam dentro da lista`() {
        // MC3300x: 271 níveis (0 a 270)
        assertEquals(270, ReconexaoLeitor.indicePotencia(271, 100))
        assertEquals(135, ReconexaoLeitor.indicePotencia(271, 50))
        assertEquals(0, ReconexaoLeitor.indicePotencia(271, 0))
        assertEquals(68, ReconexaoLeitor.indicePotencia(271, 25))
    }

    @Test
    fun `potencia nunca sai da lista do leitor`() {
        assertEquals(0, ReconexaoLeitor.indicePotencia(0, 100))
        assertEquals(0, ReconexaoLeitor.indicePotencia(1, 75))
        assertEquals(9, ReconexaoLeitor.indicePotencia(10, 150))
        assertEquals(0, ReconexaoLeitor.indicePotencia(10, -5))
    }
}
