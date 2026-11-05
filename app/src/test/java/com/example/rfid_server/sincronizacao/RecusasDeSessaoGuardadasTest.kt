package com.example.rfid_server.sincronizacao

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Prazo das sessões recusadas guardado nas preferências: continua contando depois de o app reabrir. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecusasDeSessaoGuardadasTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun limpar() {
        listOf("recusas_sessao", RecusasDeSessaoGuardadas.ARQUIVO_CADASTROS).forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun `prazo sobrevive a recriar o objeto (app reaberto)`() {
        RecusasDeSessaoGuardadas(context).registrar("sessao1:ana", 1_000)

        val reaberto = RecusasDeSessaoGuardadas(context)

        assertEquals(1_000L, reaberto.primeiraRecusa("sessao1:ana"))
        assertNull(reaberto.primeiraRecusa("sessao1:bia"))
    }

    @Test
    fun `esquecer apaga so a sessao aceita`() {
        val recusas = RecusasDeSessaoGuardadas(context)
        recusas.registrar("sessao1:ana", 1_000)
        recusas.registrar("sessao1:bia", 2_000)

        recusas.esquecer("sessao1:ana")

        val reaberto = RecusasDeSessaoGuardadas(context)
        assertNull(reaberto.primeiraRecusa("sessao1:ana"))
        assertEquals(2_000L, reaberto.primeiraRecusa("sessao1:bia"))
    }

    @Test
    fun `manterSomente apaga as sessoes que sairam da fila e nao mexe no arquivo dos cadastros`() {
        val leituras = RecusasDeSessaoGuardadas(context)
        leituras.registrar("sessao1:ana", 1_000)
        leituras.registrar("sessao1:bia", 2_000)
        leituras.registrar("sessao1:caio", 3_000)
        val cadastros = RecusasDeSessaoGuardadas(context, RecusasDeSessaoGuardadas.ARQUIVO_CADASTROS)
        cadastros.registrar("sessao1:bia", 5_000)

        leituras.manterSomente(setOf("sessao1:ana", "sessao1:caio"))

        val reaberto = RecusasDeSessaoGuardadas(context)
        assertEquals(1_000L, reaberto.primeiraRecusa("sessao1:ana"))
        assertNull(reaberto.primeiraRecusa("sessao1:bia"))
        assertEquals(3_000L, reaberto.primeiraRecusa("sessao1:caio"))
        assertEquals("Outra fila, outro arquivo", 5_000L, RecusasDeSessaoGuardadas(context, RecusasDeSessaoGuardadas.ARQUIVO_CADASTROS).primeiraRecusa("sessao1:bia"))
    }

    @Test
    fun `o arquivo guarda o hash da sessao e nao o token`() {
        RecusasDeSessaoGuardadas(context).registrar("token-secreto", 1_000)

        val chaves = context.getSharedPreferences("recusas_sessao", Context.MODE_PRIVATE).all.keys
        assertEquals(1, chaves.size)
        assertFalse(chaves.single().contains("token-secreto"))
        assertEquals(64, chaves.single().length)
    }
}
