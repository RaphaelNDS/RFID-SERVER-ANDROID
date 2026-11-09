package com.example.rfid_server

import android.content.Intent
import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.rfid_server.activities.CadastroTagActivity
import com.example.rfid_server.activities.InventarioActivity
import com.example.rfid_server.activities.LocalizarActivity
import com.example.rfid_server.activities.LoginActivity
import com.example.rfid_server.activities.MainActivity
import com.example.rfid_server.activities.SettingsActivity
import com.example.rfid_server.dados.BancoLocal
import com.example.rfid_server.dados.LeituraEntity
import com.example.rfid_server.dados.Situacao
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Fotos das telas no próprio coletor, com dados de exemplo, para conferir o visual sem precisar do servidor: a versão de
 * teste entra com uma sessão falsa e leituras inventadas. As imagens ficam em
 * /sdcard/Android/data/br.com.bkpbr.rfidandroid.teste/files/telas (puxar com adb pull antes de desinstalar).
 * Não confere nada sozinho: só falha se uma tela travar ao abrir.
 */
@RunWith(AndroidJUnit4::class)
class FotosDasTelasNoColetorTest {

    private val instrumentacao get() = InstrumentationRegistry.getInstrumentation()
    private val contexto get() = instrumentacao.targetContext
    private val pasta get() = File(contexto.getExternalFilesDir(null), "telas").apply { mkdirs() }

    @Before
    fun entrar() {
        AppConfig.saveSessao(
            contexto,
            SessaoOperador(
                token = "sessao-de-teste", expiraEm = System.currentTimeMillis() + 3_600_000,
                nome = "Operador Teste", email = "teste@exemplo.com", podeLer = true, podeCadastrar = true
            )
        )
    }

    @After
    fun sair() = AppConfig.limparSessao(contexto)

    private fun foto(nome: String) {
        instrumentacao.waitForIdleSync()
        Thread.sleep(1_500)
        val imagem = instrumentacao.uiAutomation.takeScreenshot() ?: return
        File(pasta, "$nome.png").outputStream().use { imagem.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun telas() {
        ActivityScenario.launch(MainActivity::class.java).use {
            // Depois de abrir: a tela principal começa uma "nova leitura" ao abrir e arquivaria o que já estivesse lá
            val dao = BancoLocal.get(contexto).leituraDao()
            val agora = System.currentTimeMillis()
            listOf(
                LeituraEntity(epc = "424B5030313233000000000A", lidoEm = agora, situacao = Situacao.CADASTRADA, envioConcluido = true,
                    codigoInterno = "TI-0412", tipo = "Notebook", marca = "Dell", modelo = "Latitude 5440", patrimonio = "0123/2019", numeroSerie = "SN-8H2K"),
                LeituraEntity(epc = "E28011606000020000000001", lidoEm = agora, situacao = Situacao.CADASTRADA, envioConcluido = true,
                    codigoInterno = "TI-0007", tipo = "Monitor", marca = "LG", modelo = "24MK430H", patrimonio = "PAT-1007", numeroSerie = "SN-77"),
                LeituraEntity(epc = "E28011606000020000000002", lidoEm = agora, situacao = Situacao.NAO_CADASTRADA, envioConcluido = true),
                LeituraEntity(epc = "E28011606000020000000003", lidoEm = agora, situacao = Situacao.PENDENTE)
            ).forEach { dao.inserir(it) }
            foto("01-principal")
        }

        ActivityScenario.launch(InventarioActivity::class.java).use { foto("02-inventario-inicio") }
        ActivityScenario.launch(LocalizarActivity::class.java).use { foto("03-localizar") }
        ActivityScenario.launch<CadastroTagActivity>(
            Intent(contexto, CadastroTagActivity::class.java).putExtra(CadastroTagActivity.EXTRA_EPC, "E28011606000020000000002")
        ).use { foto("04-cadastro") }
        ActivityScenario.launch(SettingsActivity::class.java).use { foto("05-configuracoes") }

        AppConfig.limparSessao(contexto)
        ActivityScenario.launch(LoginActivity::class.java).use { foto("06-login") }
    }
}
