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
    fun `chave sozinha nao vai para o log`() {
        val texto = CodigoDeBarras.paraLog("AbCd3fGh1jKl")
        assertFalse(texto.contains("AbCd3fGh1jKl"))
        assertEquals("12 caracteres (pode ser a chave do coletor; não registrado)", texto)
    }

    @Test
    fun `patrimonio e local aparecem no log`() {
        assertEquals("000123", CodigoDeBarras.paraLog("000123"))
        assertEquals("12345/2", CodigoDeBarras.paraLog("12345/2"))
        assertEquals("SALA 10", CodigoDeBarras.paraLog("SALA 10"))
    }
}
