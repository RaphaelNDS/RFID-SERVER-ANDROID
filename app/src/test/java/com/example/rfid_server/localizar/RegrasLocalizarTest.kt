package com.example.rfid_server.localizar

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import com.example.rfid_server.localizar.RegrasLocalizar.AcaoTecla
import com.example.rfid_server.localizar.RegrasLocalizar.Inicio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegrasLocalizarTest {

    // ================= LINHA "PROCURANDO: …" =================

    @Test
    fun `procurando mostra o patrimonio quando conhecido`() {
        assertEquals(RegrasLocalizar.Alvo.Patrimonio("12345"), RegrasLocalizar.alvo("E200ABC", " 12345 "))
    }

    @Test
    fun `procurando mostra o EPC quando o item nao tem patrimonio`() {
        assertEquals(RegrasLocalizar.Alvo.Epc("E200ABC"), RegrasLocalizar.alvo("E200ABC", null))
        assertEquals(RegrasLocalizar.Alvo.Epc("E200ABC"), RegrasLocalizar.alvo("E200ABC", "  "))
    }

    @Test
    fun `campo do local do inventario inicia com o Ir do teclado e com Enter`() {
        val ir = EditorInfo.IME_ACTION_GO
        assertEquals(AcaoTecla.BUSCAR, RegrasLocalizar.acaoTecla(ir, null, null, acaoDoTeclado = ir))
        assertEquals(AcaoTecla.BUSCAR, RegrasLocalizar.acaoTecla(EditorInfo.IME_NULL, KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN, ir))
        assertEquals(AcaoTecla.CONSUMIR, RegrasLocalizar.acaoTecla(EditorInfo.IME_NULL, KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_UP, ir))
        // Com "Ir" configurado, "Buscar" não é a ação do campo
        assertEquals(AcaoTecla.IGNORAR, RegrasLocalizar.acaoTecla(EditorInfo.IME_ACTION_SEARCH, null, null, ir))
    }

    // ================= INÍCIO PELO PEDIDO DE OUTRA TELA =================

    @Test
    fun `com EPC localiza direto, mesmo tendo patrimonio`() {
        assertEquals(Inicio.PorEpc("E200ABC"), RegrasLocalizar.inicio("E200ABC", "12345"))
    }

    @Test
    fun `item sem patrimonio mas com EPC tambem localiza`() {
        assertEquals(Inicio.PorEpc("E200ABC"), RegrasLocalizar.inicio("E200ABC", null))
    }

    @Test
    fun `so com patrimonio precisa buscar no servidor`() {
        assertEquals(Inicio.PorPatrimonio("12345"), RegrasLocalizar.inicio(null, "12345"))
        assertEquals(Inicio.PorPatrimonio("12345"), RegrasLocalizar.inicio("  ", " 12345 "))
    }

    @Test
    fun `patrimonio pedido por outra tela vai em maiusculas, como o cadastro grava`() {
        assertEquals(Inicio.PorPatrimonio("AB-12C"), RegrasLocalizar.inicio(null, " ab-12c "))
    }

    @Test
    fun `busca do campo tira os espacos das pontas e converte para maiusculas`() {
        assertEquals("AB12", RegrasLocalizar.normalizarPatrimonio("  ab12 "))
        assertEquals("12345", RegrasLocalizar.normalizarPatrimonio("12345"))
        assertEquals("", RegrasLocalizar.normalizarPatrimonio("   "))
        // Locale.ROOT: o "i" vira "I" mesmo com o aparelho em turco (lá viraria "İ")
        val antes = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"))
            assertEquals("PI-01", RegrasLocalizar.normalizarPatrimonio("pi-01"))
        } finally {
            java.util.Locale.setDefault(antes)
        }
    }

    @Test
    fun `sem nada nao comeca`() {
        assertEquals(Inicio.Nada, RegrasLocalizar.inicio(null, null))
        assertEquals(Inicio.Nada, RegrasLocalizar.inicio("", " "))
    }

    // ================= TECLA DE AÇÃO DO CAMPO =================

    @Test
    fun `buscar do teclado virtual busca`() {
        assertEquals(AcaoTecla.BUSCAR, RegrasLocalizar.acaoTecla(EditorInfo.IME_ACTION_SEARCH, null, null))
    }

    @Test
    fun `Enter do leitor de codigo de barras busca uma vez so`() {
        val apertado = RegrasLocalizar.acaoTecla(EditorInfo.IME_NULL, KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN)
        val solto = RegrasLocalizar.acaoTecla(EditorInfo.IME_NULL, KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_UP)

        assertEquals(AcaoTecla.BUSCAR, apertado)
        // O solto é engolido: senão o campo trataria de novo (ou a busca sairia duplicada)
        assertEquals(AcaoTecla.CONSUMIR, solto)
    }

    @Test
    fun `Enter do teclado numerico tambem busca`() {
        assertEquals(AcaoTecla.BUSCAR, RegrasLocalizar.acaoTecla(EditorInfo.IME_NULL, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.ACTION_DOWN))
    }

    @Test
    fun `outras acoes e teclas ficam com o campo`() {
        assertEquals(AcaoTecla.IGNORAR, RegrasLocalizar.acaoTecla(EditorInfo.IME_ACTION_NEXT, null, null))
        assertEquals(AcaoTecla.IGNORAR, RegrasLocalizar.acaoTecla(EditorInfo.IME_NULL, KeyEvent.KEYCODE_A, KeyEvent.ACTION_DOWN))
    }

    // ================= VIGIA DE SINAL =================

    /** Agendador falso: guarda o que foi agendado e deixa o teste disparar quando quiser. */
    private class AgendadorFalso : VigiaSinal.Agendador {
        val pendentes = mutableListOf<() -> Unit>()
        var ultimaEsperaMs = 0L

        override fun agendar(ms: Long, acao: () -> Unit): () -> Unit {
            ultimaEsperaMs = ms
            pendentes += acao
            return { pendentes -= acao }
        }

        fun disparar() = pendentes.toList().also { pendentes.clear() }.forEach { it() }
    }

    @Test
    fun `sem leitura no prazo perde o sinal`() {
        val agendador = AgendadorFalso()
        var perdas = 0
        val vigia = VigiaSinal(agendador) { perdas++ }

        vigia.leitura(localizando = true)
        assertEquals(RegrasLocalizar.SEM_SINAL_MS, agendador.ultimaEsperaMs)
        agendador.disparar()

        assertEquals(1, perdas)
        assertFalse(vigia.armado)
    }

    @Test
    fun `cada leitura reinicia o prazo`() {
        val agendador = AgendadorFalso()
        var perdas = 0
        val vigia = VigiaSinal(agendador) { perdas++ }

        repeat(5) { vigia.leitura(localizando = true) }

        // Só o último prazo continua de pé: um disparo só
        assertEquals(1, agendador.pendentes.size)
        agendador.disparar()
        assertEquals(1, perdas)
    }

    @Test
    fun `parar cancela o prazo (onPause e onDestroy)`() {
        val agendador = AgendadorFalso()
        var perdas = 0
        val vigia = VigiaSinal(agendador) { perdas++ }

        vigia.leitura(localizando = true)
        assertTrue(vigia.armado)
        vigia.parar()
        agendador.disparar()

        assertEquals(0, perdas)
        assertFalse(vigia.armado)
    }

    @Test
    fun `sem etiqueta sendo localizada nao arma e desarma o que havia`() {
        val agendador = AgendadorFalso()
        var perdas = 0
        val vigia = VigiaSinal(agendador) { perdas++ }

        vigia.leitura(localizando = true)
        vigia.leitura(localizando = false)
        agendador.disparar()

        assertEquals(0, perdas)
        assertFalse(vigia.armado)
    }
}
