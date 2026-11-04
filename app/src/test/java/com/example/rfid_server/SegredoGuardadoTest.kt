package com.example.rfid_server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Chave do coletor e token da sessão cifrados nas preferências. O Keystore do Android não existe no computador:
 * a cifra é trocada por uma falsa, que só embaralha o texto e conta quantas vezes foi usada.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SegredoGuardadoTest {

    private class CifraFalsa : Cifra {
        var falharAoCifrar = false
        var falharAoDecifrar = false
        var decifradas = 0

        override fun cifrar(texto: String): String {
            if (falharAoCifrar) throw IllegalStateException("Keystore com defeito")
            return "X" + texto.reversed()
        }

        override fun decifrar(cifrado: String): String {
            if (falharAoDecifrar) throw IllegalStateException("chave perdida")
            decifradas++
            return cifrado.removePrefix("X").reversed()
        }
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val cifra = CifraFalsa()
    private val prefs get() = context.getSharedPreferences("rfid_prefs", Context.MODE_PRIVATE)

    @Before
    fun usarCifraFalsa() {
        AppConfig.cifra = cifra
    }

    @After
    fun voltar() {
        AppConfig.cifra = CifraKeystore
        prefs.edit().clear().commit()
    }

    private fun sessao(token: String) =
        SessaoOperador(token = token, expiraEm = 9_999, nome = "Ana", email = "ana@x.com", podeLer = true, podeCadastrar = false)

    // ---------- Regras (sem preferências) ----------

    @Test
    fun `texto puro de versao anterior e aceito e marcado para regravar`() {
        val lido = SegredoGuardado.ler("chave-antiga", cifra, "a chave")
        assertEquals("chave-antiga", lido.valor)
        assertTrue(lido.regravar)
    }

    @Test
    fun `nada guardado devolve null`() {
        assertNull(SegredoGuardado.ler(null, cifra, "a chave").valor)
        assertNull(SegredoGuardado.ler("", cifra, "a chave").valor)
    }

    @Test
    fun `cifra com defeito guarda sem cifra para o coletor continuar funcionando`() {
        cifra.falharAoCifrar = true
        assertEquals("abc", SegredoGuardado.paraGuardar("abc", cifra, "a chave"))
    }

    // ---------- Chave do coletor ----------

    @Test
    fun `chave do coletor fica cifrada no arquivo e volta igual`() {
        AppConfig.saveChaveDispositivo(context, "chave-123")

        val guardado = prefs.getString("chave_dispositivo", null)!!
        assertTrue(guardado, guardado.startsWith(SegredoGuardado.PREFIXO))
        assertFalse("Não aparece no arquivo", guardado.contains("chave-123"))
        assertEquals("chave-123", AppConfig.getChaveDispositivo(context))
    }

    @Test
    fun `chave guardada sem cifra pela versao anterior e regravada cifrada`() {
        prefs.edit().putString("chave_dispositivo", "chave-da-1-6-0").commit()

        assertEquals("chave-da-1-6-0", AppConfig.getChaveDispositivo(context))

        val guardado = prefs.getString("chave_dispositivo", null)!!
        assertTrue(guardado.startsWith(SegredoGuardado.PREFIXO))
        assertEquals("chave-da-1-6-0", AppConfig.getChaveDispositivo(context))
    }

    @Test
    fun `chave que nao da para decifrar vira vazia e o coletor pede para configurar de novo`() {
        prefs.edit().putString("chave_dispositivo", SegredoGuardado.PREFIXO + "Xlixo-que-nao-esta-no-cache").commit()
        cifra.falharAoDecifrar = true

        assertEquals("", AppConfig.getChaveDispositivo(context))
    }

    @Test
    fun `chave lida varias vezes e decifrada uma vez so`() {
        AppConfig.saveChaveDispositivo(context, "chave-cache")
        repeat(50) { assertEquals("chave-cache", AppConfig.getChaveDispositivo(context)) }
        assertEquals("Gravada agora: já está no cache", 0, cifra.decifradas)
    }

    // ---------- Token da sessão ----------

    @Test
    fun `token da sessao fica cifrado e o resto da sessao continua igual`() {
        AppConfig.saveSessao(context, sessao("token-abc"))

        val guardado = prefs.getString("sessao_token", null)!!
        assertTrue(guardado.startsWith(SegredoGuardado.PREFIXO))
        assertEquals(sessao("token-abc"), AppConfig.getSessao(context))
    }

    @Test
    fun `token que nao da para decifrar deixa sem sessao e o operador entra de novo`() {
        AppConfig.saveSessao(context, sessao("token-abc"))
        prefs.edit().putString("sessao_token", SegredoGuardado.PREFIXO + "Xoutro-token").commit()
        cifra.falharAoDecifrar = true

        assertNull(AppConfig.getSessao(context))
    }
}
