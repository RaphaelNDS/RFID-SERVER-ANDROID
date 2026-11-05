package com.example.rfid_server.activities

import org.junit.Assert.assertEquals
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
    fun `patrimonio codigo interno e serie sao salvos em maiusculas sem espacos nas pontas`() {
        assertEquals("PAT-123", FormularioCadastro.normalizarCodigo("  pat-123 "))
        assertEquals("ABC-1", FormularioCadastro.normalizarCodigo("Abc-1"))
        assertEquals("SN9X", FormularioCadastro.normalizarCodigo("sn9x\n"))
        assertEquals("", FormularioCadastro.normalizarCodigo("   "))
        // Locale.ROOT: no aparelho em turco o "i" não vira "İ"
        assertEquals("NOTEBOOK-I", FormularioCadastro.normalizarCodigo("notebook-i"))
    }

    @Test
    fun `o que foi digitado em minusculas e salvo em maiusculas nao conta como alteracao`() {
        assertFalse(salvo.copy(codigoInterno = "abc-1", numeroSerie = "sn9").alteradoEmRelacaoA(salvo))
    }

    @Test
    fun `mudar o cadastro guardado conta como alteracao`() {
        assertTrue(salvo.copy(codigoInterno = "ABC-2").alteradoEmRelacaoA(salvo))
        assertTrue(salvo.copy(modeloId = null).alteradoEmRelacaoA(salvo))
        assertTrue(salvo.copy(tipoId = 9).alteradoEmRelacaoA(salvo))
    }

    // ================= sessão na tela de cadastro =================

    @Test
    fun `nao guardado sem sessao pede a senha e com sessao e falha de gravacao`() {
        assertEquals(SessaoNoCadastro.Acao.PEDIR_SENHA, SessaoNoCadastro.aposNaoGuardar(temSessao = false, email = "ana@x.com"))
        // Com sessão, o false veio de uma falha ao gravar no coletor: não é para pedir senha nem ir para o login
        assertEquals(SessaoNoCadastro.Acao.TENTAR_DE_NOVO, SessaoNoCadastro.aposNaoGuardar(temSessao = true, email = "ana@x.com"))
        assertEquals(SessaoNoCadastro.Acao.TENTAR_DE_NOVO, SessaoNoCadastro.aposNaoGuardar(temSessao = true, email = null))
        // Sem saber de quem é a sessão, não há como renovar aqui
        assertEquals(SessaoNoCadastro.Acao.LOGIN, SessaoNoCadastro.aposNaoGuardar(temSessao = false, email = null))
        assertEquals(SessaoNoCadastro.Acao.LOGIN, SessaoNoCadastro.aposNaoGuardar(temSessao = false, email = " "))
    }

    @Test
    fun `sessao vencida com a tela aberta so pede a senha se houver algo a perder`() {
        assertEquals(SessaoNoCadastro.Acao.PEDIR_SENHA, SessaoNoCadastro.semSessao(alterado = true, cadastroEsperando = false, email = "ana@x.com"))
        assertEquals(SessaoNoCadastro.Acao.PEDIR_SENHA, SessaoNoCadastro.semSessao(alterado = false, cadastroEsperando = true, email = "ana@x.com"))
        assertEquals(SessaoNoCadastro.Acao.LOGIN, SessaoNoCadastro.semSessao(alterado = false, cadastroEsperando = false, email = "ana@x.com"))
        assertEquals(SessaoNoCadastro.Acao.LOGIN, SessaoNoCadastro.semSessao(alterado = true, cadastroEsperando = true, email = null))
    }

    @Test
    fun `aviso da sessao para antes do fim e a conferencia acontece logo depois`() {
        val agora = 1_000_000L
        val min = 60_000L

        assertEquals(30 * min - SessaoNoCadastro.MARGEM_PARAR_AVISO_MS, SessaoNoCadastro.esperaAtePararAviso(agora + 30 * min, agora))
        assertEquals(30 * min + SessaoNoCadastro.FOLGA_FIM_MS, SessaoNoCadastro.esperaAteFim(agora + 30 * min, agora))
        // Quase no fim ou já vencida: não inicia o aviso (ele mandaria para o login) e confere já
        assertEquals(0, SessaoNoCadastro.esperaAtePararAviso(agora + 1_000, agora))
        assertEquals(0, SessaoNoCadastro.esperaAtePararAviso(agora - min, agora))
        assertEquals(SessaoNoCadastro.FOLGA_FIM_MS, SessaoNoCadastro.esperaAteFim(agora - min, agora))
    }
}
