package com.example.rfid_server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** O que o campo "IP do servidor" das Configurações aceita. */
class AppConfigEnderecoTest {

    @Test
    fun `aceita ip nome ip com porta e endereco colado com esquema`() {
        listOf("192.168.0.10", "192.168.0.10:9443", "rfid-servidor", "http://192.168.0.10", "https://192.168.0.10:8443/", " 10.0.0.5 ")
            .forEach { assertTrue("deveria aceitar '$it'", AppConfig.enderecoValido(it)) }
    }

    @Test
    fun `recusa vazio espacos no meio caminho e lixo`() {
        listOf("", "   ", "192.168 .0.10", "192.168.0.10/api", "http://", "isto não é um endereço", "::::")
            .forEach { assertFalse("deveria recusar '$it'", AppConfig.enderecoValido(it)) }
    }
}
