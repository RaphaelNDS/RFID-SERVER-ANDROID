package com.example.rfid_server.telas

import com.example.rfid_server.configuracoes.AppConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CliqueUnicoTest {

    @Test
    fun `segundo toque de um toque duplo e ignorado`() {
        assertTrue("Primeiro toque", CliqueUnico.permitido(null, 1_000))
        assertFalse("Toque duplo", CliqueUnico.permitido(1_000, 1_300))
        assertTrue("Toque novo depois do intervalo", CliqueUnico.permitido(1_000, 1_000 + CliqueUnico.INTERVALO_MS))
        assertTrue("Aparelho reiniciado (tempo voltou)", CliqueUnico.permitido(50_000, 1_000))
    }

    @Test
    fun `chave com acento ou espaco especial nao pode ir no header`() {
        assertTrue(AppConfig.chaveValida("abc-123_XYZ"))
        assertTrue(AppConfig.chaveValida(""))
        assertFalse(AppConfig.chaveValida("chave-coleção"))
        assertFalse(AppConfig.chaveValida("abc\u00A0123"))
    }
}
