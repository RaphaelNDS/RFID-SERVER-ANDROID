package com.example.rfid_server.sincronizacao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisjuntorRecusasTest {

    @Test
    fun `dispara na quinta recusa seguida com a mesma mensagem`() {
        val disjuntor = DisjuntorRecusas()
        (1L..4L).forEach { assertFalse(disjuntor.recusada(it, "HTTP 400")) }

        assertTrue(disjuntor.recusada(5, "HTTP 400"))
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), disjuntor.idsDaSequencia())
    }

    @Test
    fun `mensagem diferente ou leitura aceita recomecam a contagem`() {
        val disjuntor = DisjuntorRecusas()
        (1L..4L).forEach { disjuntor.recusada(it, "A") }
        disjuntor.recusada(5, "B")
        (6L..8L).forEach { disjuntor.recusada(it, "B") }
        disjuntor.aceita()
        (9L..12L).forEach { disjuntor.recusada(it, "B") }

        assertFalse(disjuntor.disparou)
    }

    @Test
    fun `depois de disparar fica travado e junta as recusas iguais que vierem`() {
        val disjuntor = DisjuntorRecusas(limite = 2)
        disjuntor.recusada(1, "A")
        disjuntor.recusada(2, "A")
        disjuntor.aceita()
        assertTrue(disjuntor.recusada(3, "B"))
        disjuntor.recusada(4, "A")

        assertTrue(disjuntor.disparou)
        assertEquals("A", disjuntor.mensagem)
        assertEquals(listOf(1L, 2L, 4L), disjuntor.idsDaSequencia())
    }
}
