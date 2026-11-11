package com.example.rfid_server.telas

import com.example.rfid_server.leitor.CorFaixa
import com.example.rfid_server.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EstiloFaixaLeitorTest {

    @Test
    fun `conectado fica discreto, em fundo claro`() {
        val estilo = EstiloFaixaLeitor.de(CorFaixa.SUCESSO)
        assertFalse(estilo.solido)
        assertEquals(R.color.sucesso_fundo, estilo.fundo)
        assertEquals(R.color.sucesso, estilo.texto)
        assertEquals(R.color.sucesso, estilo.marca)
    }

    @Test
    fun `atencao e erro usam fundo solido com texto e bolinha brancos`() {
        val atencao = EstiloFaixaLeitor.de(CorFaixa.ATENCAO)
        assertTrue(atencao.solido)
        assertEquals(R.color.atencao, atencao.fundo)
        assertEquals(R.color.white, atencao.texto)
        assertEquals(R.color.white, atencao.marca)

        val erro = EstiloFaixaLeitor.de(CorFaixa.ERRO)
        assertTrue(erro.solido)
        assertEquals(R.color.erro, erro.fundo)
        assertEquals(R.color.white, erro.texto)
        assertEquals(R.color.white, erro.marca)
    }
}
