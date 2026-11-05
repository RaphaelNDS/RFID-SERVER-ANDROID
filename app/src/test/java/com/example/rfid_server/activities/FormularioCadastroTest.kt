package com.example.rfid_server.activities

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormularioCadastroTest {

    private val vazio = FormularioCadastro()
    private val salvo = FormularioCadastro("ABC-1", "123", "SN9", tipoId = 1, marcaId = 2, modeloId = 3)

    @Test
    fun `cadastro novo sem nada preenchido nao tem alteracao`() {
        assertFalse(FormularioCadastro().alteradoEmRelacaoA(vazio))
        assertFalse(FormularioCadastro(codigoInterno = "   ").alteradoEmRelacaoA(vazio))
    }

    @Test
    fun `qualquer campo preenchido num cadastro novo conta como alteracao`() {
        assertTrue(FormularioCadastro(codigoInterno = "X").alteradoEmRelacaoA(vazio))
        assertTrue(FormularioCadastro(patrimonio = "1").alteradoEmRelacaoA(vazio))
        assertTrue(FormularioCadastro(numeroSerie = "1").alteradoEmRelacaoA(vazio))
        assertTrue(FormularioCadastro(tipoId = 7).alteradoEmRelacaoA(vazio))
        assertTrue(FormularioCadastro(marcaId = 7).alteradoEmRelacaoA(vazio))
        assertTrue(FormularioCadastro(modeloId = 7).alteradoEmRelacaoA(vazio))
    }

    @Test
    fun `cadastro guardado igual ao da tela nao tem alteracao (espacos nas pontas nao contam)`() {
        assertFalse(salvo.copy().alteradoEmRelacaoA(salvo))
        assertFalse(salvo.copy(codigoInterno = " ABC-1 ", numeroSerie = "SN9 ").alteradoEmRelacaoA(salvo))
    }

    @Test
    fun `mudar o cadastro guardado conta como alteracao`() {
        assertTrue(salvo.copy(codigoInterno = "ABC-2").alteradoEmRelacaoA(salvo))
        assertTrue(salvo.copy(modeloId = null).alteradoEmRelacaoA(salvo))
        assertTrue(salvo.copy(tipoId = 9).alteradoEmRelacaoA(salvo))
    }
}
