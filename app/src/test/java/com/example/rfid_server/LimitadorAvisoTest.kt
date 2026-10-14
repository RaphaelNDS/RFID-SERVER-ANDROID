package com.example.rfid_server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LimitadorAvisoTest {

    private var agora = 10_000L
    private val limitador = LimitadorAviso(intervaloMs = 150, relogio = { agora })

    @Test
    fun `primeiro aviso sempre sai`() {
        assertTrue(limitador.liberar())
    }

    @Test
    fun `primeiro aviso sai mesmo com o relogio em zero`() {
        agora = 0L
        assertTrue(limitador.liberar())
    }

    @Test
    fun `avisos dentro do intervalo sao descartados`() {
        assertTrue(limitador.liberar())
        agora += 149
        assertFalse(limitador.liberar())
        agora += 1
        assertTrue(limitador.liberar())
    }

    @Test
    fun `rajada de etiquetas vira no maximo um aviso por intervalo`() {
        // 100 etiquetas novas em 1 segundo (uma a cada 10 ms)
        var liberados = 0
        repeat(100) {
            if (limitador.liberar()) liberados++
            agora += 10
        }

        assertEquals(7, liberados) // 0, 150, 300, 450, 600, 750, 900 ms
    }
}
