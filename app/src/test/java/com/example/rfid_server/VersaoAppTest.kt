package com.example.rfid_server

import org.junit.Assert.assertEquals
import org.junit.Test

class VersaoAppTest {

    @Test
    fun `versao de producao mostra nome e codigo`() {
        assertEquals("Versão 1.4.0 (código 13)", VersaoApp.descricao("1.4.0", 13, "release"))
    }

    @Test
    fun `versao de desenvolvimento aparece marcada`() {
        assertEquals("Versão 1.4.0 (código 13) · desenvolvimento", VersaoApp.descricao("1.4.0", 13, "debug"))
    }
}
