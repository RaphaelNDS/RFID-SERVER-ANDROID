package com.example.rfid_server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CodigoDeBarrasLogTest {

    @Test
    fun `QR de configuracao nao leva a chave para o log`() {
        val texto = CodigoDeBarras.paraLog("RFIDCFG1|172.16.242.22:8443|AbCd3fGh1jKl|63:0F:85")
        assertEquals("QR de configuração (RFIDCFG1|172.16.242.22:8443|…)", texto)
        assertFalse(texto.contains("AbCd3fGh1jKl"))
    }

    @Test
    fun `na tela sensivel nenhum codigo vai para o log, qualquer que seja o formato da chave`() {
        for (chave in listOf("AbCd3fGh1jKl", "ABCD1234EFGH", "a1b2-c3d4-e5f6", "123e4567-e89b-12d3-a456-426614174000")) {
            val texto = CodigoDeBarras.paraLog(chave, sensivel = true)
            assertFalse(texto, texto.contains(chave))
            assertEquals("${chave.length} caracteres (tela com a chave do coletor: conteúdo não registrado)", texto)
        }
    }

    @Test
    fun `fora da tela sensivel patrimonio e local aparecem no log`() {
        assertEquals("000123", CodigoDeBarras.paraLog("000123"))
        assertEquals("12345/2", CodigoDeBarras.paraLog("12345/2"))
        assertEquals("SALA 10", CodigoDeBarras.paraLog("SALA 10"))
    }
}
