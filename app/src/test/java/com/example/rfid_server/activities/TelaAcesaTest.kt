package com.example.rfid_server.activities

import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Tela acesa só enquanto lê e 2 min depois de soltar o gatilho. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TelaAcesaTest {

    private var acesa = false
    private val tela = TelaAcesa(Handler(Looper.getMainLooper()), ligar = { acesa = true }, desligar = { acesa = false })

    private fun passar(segundos: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(segundos))

    @Test
    fun `ao abrir fica acesa 2 minutos e depois apaga`() {
        tela.aoAbrir()
        passar(119)
        assertTrue(acesa)
        passar(2)
        assertFalse(acesa)
    }

    @Test
    fun `lendo com o gatilho apertado nao apaga e soltar conta 2 minutos`() {
        tela.aoAbrir()
        tela.aoApertar()
        passar(5 * 60)
        assertTrue("Gatilho apertado há 5 min: continua acesa", acesa)

        tela.aoSoltar()
        passar(119)
        assertTrue(acesa)
        passar(2)
        assertFalse(acesa)
    }

    @Test
    fun `gatilho que nunca soltou apaga depois de 10 minutos`() {
        tela.aoApertar()
        passar(10 * 60 + 1)
        assertFalse(acesa)
    }

    @Test
    fun `gatilho depois de apagada acende de novo e sair da tela apaga na hora`() {
        tela.aoAbrir()
        passar(3 * 60)
        assertFalse(acesa)

        tela.aoApertar()
        assertTrue(acesa)

        tela.parar()
        assertFalse(acesa)
        passar(15 * 60)
        assertFalse(acesa)
    }
}
