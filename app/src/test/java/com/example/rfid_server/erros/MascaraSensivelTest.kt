package com.example.rfid_server.erros

import org.junit.Assert.assertEquals
import org.junit.Test

class MascaraSensivelTest {

    @Test
    fun `email fica so com a primeira letra e o dominio`() {
        assertEquals("r***@empresa.com.br", MascaraSensivel.email("raphael.nds@empresa.com.br"))
        assertEquals("a***@x.com", MascaraSensivel.email("  a@x.com "))
    }

    @Test
    fun `email fora do formato nao vaza o texto inteiro`() {
        assertEquals("r***", MascaraSensivel.email("raphael"))
        assertEquals("@***", MascaraSensivel.email("@dominio.com"))
        assertEquals("***", MascaraSensivel.email(""))
        assertEquals("***", MascaraSensivel.email(null))
    }
}
