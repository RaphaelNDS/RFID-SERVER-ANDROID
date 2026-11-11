package com.example.rfid_server.telas

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.rfid_server.configuracoes.AppConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "Letra maior" do menu da tela principal: desligada não muda nada; ligada aumenta 15% por cima da fonte do Android. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TamanhoTextoTest {

    private val contexto: Context = ApplicationProvider.getApplicationContext()

    @After
    fun desligar() = AppConfig.setLetraMaior(contexto, false)

    @Test
    fun `vem desligada e sem ela o contexto e o mesmo`() {
        assertFalse(AppConfig.isLetraMaior(contexto))
        assertSame(contexto, TamanhoTexto.aplicar(contexto))
    }

    @Test
    fun `ligada aumenta a fonte em 15 por cento sobre a do sistema`() {
        AppConfig.setLetraMaior(contexto, true)
        assertTrue(AppConfig.isLetraMaior(contexto))
        val sistema = contexto.resources.configuration.fontScale
        val aumentado = TamanhoTexto.aplicar(contexto).resources.configuration.fontScale
        assertEquals(sistema * TamanhoTexto.ESCALA, aumentado, 0.001f)
    }

    @Test
    fun `desligar volta ao tamanho do sistema`() {
        AppConfig.setLetraMaior(contexto, true)
        AppConfig.setLetraMaior(contexto, false)
        assertSame(contexto, TamanhoTexto.aplicar(contexto))
    }
}
