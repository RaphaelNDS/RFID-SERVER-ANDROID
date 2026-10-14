package com.example.rfid_server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconexaoLeitorTest {

    @Test
    fun `espera cresce com as tentativas e para em 10 segundos`() {
        assertEquals(3_000L, ReconexaoLeitor.esperaMs(1))
        assertEquals(3_000L, ReconexaoLeitor.esperaMs(2))
        assertEquals(5_000L, ReconexaoLeitor.esperaMs(3))
        assertEquals(5_000L, ReconexaoLeitor.esperaMs(5))
        assertEquals(10_000L, ReconexaoLeitor.esperaMs(6))
        assertEquals(10_000L, ReconexaoLeitor.esperaMs(500))
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
}
