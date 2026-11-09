package com.example.rfid_server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfigQrTest {

    private val impressao = (1..32).joinToString(":") { "%02X".format(it) }

    @Test
    fun `le servidor chave e certificado`() {
        val config = ConfigQr.ler("RFIDCFG1|172.16.242.22:8443|chave-123|${impressao.lowercase()}")

        assertEquals(ConfigQr("172.16.242.22:8443", "chave-123", impressao), config)
    }

    @Test
    fun `certificado ausente ou malformado fica de fora sem perder o resto`() {
        assertEquals(ConfigQr("srv:8443", "k", null), ConfigQr.ler("RFIDCFG1|srv:8443|k"))
        assertEquals(ConfigQr("srv:8443", "k", null), ConfigQr.ler("RFIDCFG1|srv:8443|k|AB:CD"))
    }

    @Test
    fun `codigo que nao e de configuracao ou incompleto e ignorado`() {
        assertNull("Plaqueta de patrimônio", ConfigQr.ler("0123/2019"))
        assertNull(ConfigQr.ler("RFIDCFG1|srv:8443"))
        assertNull(ConfigQr.ler("RFIDCFG1||chave"))
        assertNull("Chave com acento não vai num header", ConfigQr.ler("RFIDCFG1|srv:8443|chave-coleção"))
    }
}
