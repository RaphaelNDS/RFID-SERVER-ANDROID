package com.example.rfid_server.activities

import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Laço de conferência do AvisoSessao (a cada 30 s) e o parar() chamado de dentro da própria conferência. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AvisoSessaoTest {

    private val handler = Handler(Looper.getMainLooper())

    private fun passar(segundos: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(segundos))

    @Test
    fun `parar dentro da conferencia nao e desfeito pelo reagendamento`() {
        // Como o Cadastro: sem sessão, o aoSessaoAcabar para o aviso e pede a senha na própria tela
        var conferencias = 0
        lateinit var laco: AvisoSessao.LacoConferencia
        laco = AvisoSessao.LacoConferencia(handler, 30_000) { conferencias++; laco.parar() }

        laco.iniciar()
        passar(1)
        assertEquals(1, conferencias)
        assertFalse(laco.ativo)

        passar(120)
        assertEquals("Depois de parado não confere de novo (o pedido de senha não volta a cada 30 s)", 1, conferencias)
    }

    @Test
    fun `ligado continua conferindo a cada 30 s ate parar`() {
        var conferencias = 0
        val laco = AvisoSessao.LacoConferencia(handler, 30_000) { conferencias++ }

        laco.iniciar()
        passar(1)
        assertTrue(laco.ativo)
        passar(60)
        assertEquals(3, conferencias)

        laco.parar()
        passar(120)
        assertEquals(3, conferencias)
    }

    @Test
    fun `conferir ja depois de parado nao confere nem religa`() {
        var conferencias = 0
        val laco = AvisoSessao.LacoConferencia(handler, 30_000) { conferencias++ }

        laco.iniciar()
        passar(1)
        laco.parar()
        // Fim de sessão avisado pela sincronização (outra thread) logo depois de a tela pausar
        laco.conferirJa()
        passar(120)

        assertEquals(1, conferencias)
        assertFalse(laco.ativo)
    }
}
