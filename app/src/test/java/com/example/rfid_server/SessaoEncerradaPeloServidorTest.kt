package com.example.rfid_server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.rfid_server.activities.LoginActivity
import com.example.rfid_server.erros.MascaraSensivel
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

/** Sessão encerrada porque o servidor a recusou: sai do coletor, avisa as telas e a tela de login mostra o porquê uma vez. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessaoEncerradaPeloServidorTest {

    private object CifraFalsa : Cifra {
        override fun cifrar(texto: String) = "X" + texto.reversed()
        override fun decifrar(cifrado: String) = cifrado.removePrefix("X").reversed()
    }

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun usarCifraFalsa() {
        AppConfig.cifra = CifraFalsa
    }

    @After
    fun voltar() {
        AppConfig.cifra = CifraKeystore
        context.getSharedPreferences("rfid_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun sessao(token: String) = SessaoOperador(
        token = token, expiraEm = System.currentTimeMillis() + 3_600_000, nome = "Ana", email = "ana@x.com",
        podeLer = true, podeCadastrar = false
    )

    @Test
    fun `encerra a sessao atual guarda o motivo uma vez e avisa as telas`() {
        AppConfig.saveSessao(context, sessao("token-ana"))
        var avisos = 0
        val ouvinte: () -> Unit = { avisos++ }
        SessaoAtual.ouvirEncerramento(ouvinte)
        try {
            assertTrue(SessaoAtual.encerrarPeloServidor(context, "token-ana", "DESATIVADO"))
        } finally {
            SessaoAtual.pararDeOuvir(ouvinte)
        }

        assertNull(SessaoAtual.get(context))
        assertEquals(1, avisos)
        assertEquals("ana@x.com", AppConfig.getUltimoEmail(context))
        assertEquals("DESATIVADO", AppConfig.tirarMotivoFimSessao(context)?.motivoServidor)
        assertNull("Mostrado uma vez só", AppConfig.tirarMotivoFimSessao(context))
    }

    @Test
    fun `o motivo guarda de quem era a sessao para o login mostrar`() {
        val agora = 1_760_000_000_000L
        AppConfig.saveSessao(context, sessao("token-ana"))
        assertTrue(AppConfig.encerrarSessaoRecusada(context, "token-ana", "EXPIRADA", agora))

        val fim = AppConfig.tirarMotivoFimSessao(context, agora + 60_000)

        assertEquals(AppConfig.FimSessao("EXPIRADA", "ana@x.com", agora), fim)
        assertEquals("a***@x.com", MascaraSensivel.email(fim!!.email))
    }

    @Test
    fun `motivo com mais de 12 h e descartado sem mostrar`() {
        val agora = 1_760_000_000_000L
        AppConfig.saveSessao(context, sessao("token-ana"))
        AppConfig.encerrarSessaoRecusada(context, "token-ana", "DESATIVADO", agora)

        assertNull(AppConfig.tirarMotivoFimSessao(context, agora + AppConfig.VALIDADE_MOTIVO_FIM_MS + 1))
        assertNull("Descartado de vez", AppConfig.tirarMotivoFimSessao(context, agora))
    }

    @Test
    fun `motivo ainda dentro das 12 h e mostrado`() {
        val agora = 1_760_000_000_000L
        AppConfig.saveSessao(context, sessao("token-ana"))
        AppConfig.encerrarSessaoRecusada(context, "token-ana", "DESATIVADO", agora)

        assertEquals("DESATIVADO", AppConfig.tirarMotivoFimSessao(context, agora + AppConfig.VALIDADE_MOTIVO_FIM_MS)?.motivoServidor)
    }

    @Test
    fun `motivo guardado por versao anterior sem hora nem email e descartado`() {
        context.getSharedPreferences("rfid_prefs", Context.MODE_PRIVATE).edit().putString("sessao_motivo_fim", "ENCERRADA").commit()

        assertNull(AppConfig.tirarMotivoFimSessao(context))
        assertTrue(context.getSharedPreferences("rfid_prefs", Context.MODE_PRIVATE).all.keys.none { it.startsWith("sessao_motivo_fim") })
    }

    @Test
    fun `nao desfaz um login novo feito depois da recusa`() {
        AppConfig.saveSessao(context, sessao("token-novo"))

        assertFalse(SessaoAtual.encerrarPeloServidor(context, "token-antigo", "ENCERRADA"))

        assertEquals("token-novo", SessaoAtual.token(context))
        assertNull(AppConfig.tirarMotivoFimSessao(context))
    }

    @Test
    fun `entrar de novo apaga o motivo antigo`() {
        AppConfig.saveSessao(context, sessao("token-ana"))
        AppConfig.encerrarSessaoRecusada(context, "token-ana", "DESCONHECIDA")

        AppConfig.saveSessao(context, sessao("token-novo"))

        assertNull(AppConfig.tirarMotivoFimSessao(context))
    }

    @Test
    fun `tela de login tem um texto para cada motivo`() {
        // Os recursos do app não entram nos testes (sem includeAndroidResources): confere o texto escolhido pelo id
        mapOf(
            "DESATIVADO" to R.string.login_sessao_usuario_desativado,
            "USUARIO_INEXISTENTE" to R.string.login_sessao_usuario_excluido,
            "ENCERRADA" to R.string.login_sessao_encerrada,
            "EXPIRADA" to R.string.login_sessao_expirada,
            "OUTRO_COLETOR" to R.string.login_sessao_outro_coletor,
            "DESCONHECIDA" to R.string.login_sessao_desconhecida
        ).forEach { (motivo, texto) -> assertEquals(motivo, texto, LoginActivity.mensagemFimSessao(motivo)) }
    }
}
