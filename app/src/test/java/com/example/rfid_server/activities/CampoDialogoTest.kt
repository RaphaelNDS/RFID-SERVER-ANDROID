package com.example.rfid_server.activities

import android.view.inputmethod.EditorInfo
import com.example.rfid_server.activities.CampoDialogo.RespostaTeclado
import org.junit.Assert.assertEquals
import org.junit.Test

class CampoDialogoTest {

    @Test
    fun `concluir do teclado na tela confirma o dialogo`() {
        assertEquals(RespostaTeclado.CONFIRMAR, CampoDialogo.respostaTeclado(EditorInfo.IME_ACTION_DONE, enter = false, apertou = false))
    }

    @Test
    fun `enter do teclado fisico confirma ao apertar e so e consumido ao soltar`() {
        assertEquals(RespostaTeclado.CONFIRMAR, CampoDialogo.respostaTeclado(EditorInfo.IME_NULL, enter = true, apertou = true))
        assertEquals(RespostaTeclado.CONSUMIR, CampoDialogo.respostaTeclado(EditorInfo.IME_NULL, enter = true, apertou = false))
    }

    @Test
    fun `proximo e outras acoes seguem o comportamento normal do teclado`() {
        assertEquals(RespostaTeclado.IGNORAR, CampoDialogo.respostaTeclado(EditorInfo.IME_ACTION_NEXT, enter = false, apertou = false))
        assertEquals(RespostaTeclado.IGNORAR, CampoDialogo.respostaTeclado(EditorInfo.IME_ACTION_GO, enter = false, apertou = false))
    }
}
