package com.example.rfid_server.fragment

import org.junit.Assert.assertEquals
import org.junit.Test

class TitulosAbasTest {

    @Test
    fun `titulo da aba traz a quantidade da lista`() {
        assertEquals("Leitura (12)", TitulosAbas.titulo("Leitura", 12))
        assertEquals("Não cadastradas (0)", TitulosAbas.titulo("Não cadastradas", 0))
    }

    @Test
    fun `antes de a lista carregar mostra so o rotulo`() {
        assertEquals("Leitura", TitulosAbas.titulo("Leitura", null))
    }
}
