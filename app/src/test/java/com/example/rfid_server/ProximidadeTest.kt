package com.example.rfid_server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximidadeTest {

    @Test
    fun `nivel respeita os limites de cada faixa de RSSI`() {
        val esperado = mapOf(
            -20 to 100, -35 to 100,
            -36 to 85, -40 to 85,
            -41 to 70, -45 to 70,
            -46 to 55, -50 to 55,
            -51 to 40, -55 to 40,
            -56 to 25, -60 to 25,
            -61 to 10, -90 to 10
        )

        esperado.forEach { (rssi, nivel) ->
            assertEquals("RSSI $rssi", nivel, Proximidade.nivel(rssi))
        }
    }

    @Test
    fun `nivel nunca diminui quando o RSSI aumenta`() {
        (-100..-20).zipWithNext().forEach { (longe, perto) ->
            assertTrue("RSSI $longe → $perto", Proximidade.nivel(perto) >= Proximidade.nivel(longe))
        }
    }

    @Test
    fun `descricao de cada nivel calculado`() {
        assertEquals("🔥 Tag muito próxima", Proximidade.descricao(100))
        assertEquals("Muito perto", Proximidade.descricao(85))
        assertEquals("Muito perto", Proximidade.descricao(70))
        assertEquals("Aproximando", Proximidade.descricao(55))
        assertEquals("Longe", Proximidade.descricao(40))
        assertEquals("Muito longe", Proximidade.descricao(25))
        assertEquals("Muito longe", Proximidade.descricao(10))
    }

    @Test
    fun `quanto mais perto mais rapido o bip`() {
        val niveis = listOf(10, 25, 40, 55, 70, 85, 100)

        niveis.zipWithNext().forEach { (longe, perto) ->
            assertTrue(
                "nível $longe → $perto",
                Proximidade.intervaloFeedbackMs(perto) <= Proximidade.intervaloFeedbackMs(longe)
            )
        }
        assertEquals(120L, Proximidade.intervaloFeedbackMs(100))
        assertEquals(1200L, Proximidade.intervaloFeedbackMs(10))
    }

    @Test
    fun `quanto mais perto mais forte a vibracao`() {
        val niveis = listOf(10, 25, 40, 55, 70, 85, 100)

        niveis.zipWithNext().forEach { (longe, perto) ->
            assertTrue(
                "nível $longe → $perto",
                Proximidade.duracaoVibracaoMs(perto) >= Proximidade.duracaoVibracaoMs(longe)
            )
        }
        assertEquals(80L, Proximidade.duracaoVibracaoMs(100))
        assertEquals(15L, Proximidade.duracaoVibracaoMs(10))
    }

    @Test
    fun `distancia do leitor vira nivel na mesma escala e fica entre 0 e 100`() {
        assertEquals(0, Proximidade.nivelDaDistancia(0))
        assertEquals(63, Proximidade.nivelDaDistancia(63))
        assertEquals(100, Proximidade.nivelDaDistancia(100))
        // O SDK manda short: valores fora da faixa não podem estourar a barra
        assertEquals(0, Proximidade.nivelDaDistancia(-5))
        assertEquals(100, Proximidade.nivelDaDistancia(140))
    }
}
