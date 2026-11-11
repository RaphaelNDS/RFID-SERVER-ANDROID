package com.example.rfid_server.activities

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovosAlertasTest {

    @Test
    fun `toca so quando aparece alerta novo, nao ao abrir a tela`() {
        val n = NovosAlertas()
        assertFalse("Alertas que já estavam na tela ao abrir não tocam", n.mudou(setOf("E1")))
        assertFalse("Mesma lista de novo (voltou à tela)", n.mudou(setOf("E1")))
        assertTrue("Classificada agora como alugada", n.mudou(setOf("E1", "E2")))
        assertFalse("Nova leitura limpou a tela", n.mudou(emptySet()))
        assertTrue("Depois de limpar, um alerta é novidade", n.mudou(setOf("E1")))
    }
}
