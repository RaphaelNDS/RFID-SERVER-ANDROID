package com.example.rfid_server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RitmoAvisoTest {

    private var agora = 10_000L
    private val ritmo = RitmoAviso(relogio = { agora })

    @Test
    fun `primeiro bip sempre sai`() {
        assertTrue(ritmo.liberar(1200))
    }

    @Test
    fun `primeiro bip sai mesmo com o relogio em zero`() {
        agora = 0L
        assertTrue(ritmo.liberar(1200))
    }

    @Test
    fun `bip dentro do intervalo e descartado`() {
        assertTrue(ritmo.liberar(250))
        agora += 249
        assertFalse(ritmo.liberar(250))
        agora += 1
        assertTrue(ritmo.liberar(250))
    }

    @Test
    fun `ao chegar perto o intervalo menor ja vale para o proximo bip`() {
        // Longe: 1200 ms entre bips. A etiqueta fica muito perto 120 ms depois: já bipa
        assertTrue(ritmo.liberar(Proximidade.intervaloFeedbackMs(10)))
        agora += 120
        assertTrue(ritmo.liberar(Proximidade.intervaloFeedbackMs(100)))
    }

    @Test
    fun `descartado nao reinicia a contagem`() {
        assertTrue(ritmo.liberar(450))
        agora += 300
        assertFalse(ritmo.liberar(450))
        agora += 150
        assertTrue(ritmo.liberar(450))
    }

    @Test
    fun `um segundo muito perto da etiqueta da no maximo um bip a cada 120 ms`() {
        var liberados = 0
        repeat(100) {
            if (ritmo.liberar(Proximidade.intervaloFeedbackMs(100))) liberados++
            agora += 10
        }

        assertEquals(9, liberados) // 0, 120, 240, ..., 960 ms
    }
}
