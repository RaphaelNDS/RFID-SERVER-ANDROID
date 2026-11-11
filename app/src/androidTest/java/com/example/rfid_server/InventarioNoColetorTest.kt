package com.example.rfid_server

import android.graphics.Bitmap
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.rfid_server.activities.HistoricoInventarios
import com.example.rfid_server.activities.InventarioActivity
import com.example.rfid_server.dados.EsperadoLocal
import com.example.rfid_server.dados.ItemEsperado
import com.example.rfid_server.sincronizacao.FilaJustificativas
import com.example.rfid_server.sincronizacao.SincronizadorJustificativas
import java.io.File
import org.hamcrest.Matchers.startsWith
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fluxo do inventário no próprio coletor, sem servidor (versão de teste, ao lado do app de produção): um local com a
 * lista do esperado guardada, iniciar, finalizar sem ler nada (as duas esperadas ficam faltando), justificar uma como
 * "Emprestado" pelo resumo e conferir que a justificativa entrou na fila de envio. Também confere que a planilha pode
 * ser compartilhada (pasta liberada no FileProvider). Fotos do inventário e do resumo vão para a mesma pasta das telas.
 */
@RunWith(AndroidJUnit4::class)
class InventarioNoColetorTest {

    private val instrumentacao get() = InstrumentationRegistry.getInstrumentation()
    private val contexto get() = instrumentacao.targetContext
    private val local = "TESTE AUTOMATICO"

    @Before
    fun preparar() {
        AppConfig.saveSessao(
            contexto,
            SessaoOperador("sessao-de-teste", System.currentTimeMillis() + 3_600_000, "Operador Teste", "teste@exemplo.com",
                podeLer = true, podeCadastrar = true)
        )
        // Começa limpo: uma rodada interrompida (coletor que caiu do adb) deixa o app de teste instalado com a fila de
        // justificativas e o histórico da rodada anterior, e as conferências abaixo achavam duas em vez de uma
        File(contexto.filesDir, "justificativas.json").delete()
        File(contexto.filesDir, "historico_inventarios").deleteRecursively()

        // Local conhecido (sem a pergunta "local novo") e com a lista do esperado já guardada no coletor
        AppConfig.saveLocais(contexto, listOf(local))
        val esperado = EsperadoLocal(
            local, temReferencia = true, finalizadoEm = "2026-11-01T10:00:00-03:00", operador = "Ana",
            itens = listOf(
                ItemEsperado("E-FALTA-1", "PAT-9001", "TI-1", "Notebook", "Dell", "Latitude"),
                ItemEsperado("E-FALTA-2", "PAT-9002", "TI-2", "Monitor", "LG", null, "EM_MANUTENCAO", "Em manutenção")
            )
        )
        AppConfig.saveEsperadoJson(contexto, local, esperado.paraJson())
    }

    @After
    fun sair() = AppConfig.limparSessao(contexto)

    private fun aguardar(descricao: String, condicao: () -> Boolean) {
        val limite = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < limite) {
            instrumentacao.waitForIdleSync()
            if (try { condicao() } catch (e: Throwable) { false }) return
            Thread.sleep(300)
        }
        throw AssertionError("Esperou demais: $descricao")
    }

    private fun foto(nome: String) {
        instrumentacao.waitForIdleSync()
        Thread.sleep(800)
        val imagem = instrumentacao.uiAutomation.takeScreenshot() ?: return
        val pasta = File(contexto.getExternalFilesDir(null), "telas").apply { mkdirs() }
        File(pasta, "$nome.png").outputStream().use { imagem.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun iniciarFinalizarEJustificarFaltante() {
        ActivityScenario.launch(InventarioActivity::class.java).use {
            onView(withId(R.id.edtLocal)).perform(replaceText(local))
            closeSoftKeyboard()
            onView(withId(R.id.btnIniciar)).perform(click())

            aguardar("inventário aberto") { onView(withId(R.id.btnFinalizar)).check(matches(isDisplayed())); true }
            // O bem em manutenção no cadastro diz isso na lista do Faltando (e vem por último)
            aguardar("situação do cadastro na lista") {
                onView(withText(contexto.getString(R.string.esperado_situacao, "Em manutenção")))
                    .check(matches(androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility(androidx.test.espresso.matcher.ViewMatchers.Visibility.VISIBLE))); true
            }
            foto("07-inventario-andamento")

            onView(withId(R.id.btnFinalizar)).perform(click())
            onView(withText(R.string.inventario_finalizar_confirmar)).inRoot(isDialog()).perform(click())

            // Resumo: as duas esperadas faltaram. "Ver faltantes" abre a lista (tocar procura; Justificar leva ao motivo)
            val verFaltantes = contexto.getString(R.string.inventario_ver_faltantes, 2)
            aguardar("resumo com o botão dos faltantes") { onView(withText(verFaltantes)).inRoot(isDialog()).check(matches(isDisplayed())); true }
            foto("08-inventario-resumo")
            onView(withText(verFaltantes)).inRoot(isDialog()).perform(click())

            val justificar = contexto.getString(R.string.inventario_justificar, 2)
            aguardar("lista dos faltantes com o justificar") { onView(withText(justificar)).inRoot(isDialog()).check(matches(isDisplayed())); true }
            onView(withText(justificar)).inRoot(isDialog()).perform(click())

            onView(withText(R.string.justificativa_emprestado)).inRoot(isDialog()).perform(click())
            onView(withText(startsWith("Patrimônio PAT-9001"))).inRoot(isDialog()).perform(click())
            onView(withText(R.string.inventario_justificar_salvar)).inRoot(isDialog()).perform(click())

            // Volta à lista dos faltantes com uma a menos para justificar
            val restante = contexto.getString(R.string.inventario_justificar, 1)
            aguardar("lista com 1 faltante sem justificativa") { onView(withText(restante)).inRoot(isDialog()).check(matches(isDisplayed())); true }

            val pendente = SincronizadorJustificativas.fila(contexto).pendentes().single { it.epc == "E-FALTA-1" }
            assertEquals(FilaJustificativas.Motivo.EMPRESTADO, pendente.motivo)
            assertTrue("Justificativa com a sessão de quem justificou", pendente.sessao != null)
            // E fica no histórico: a fila esquece quando o servidor confirma, o resumo reaberto não pode esquecer
            aguardar("justificativa guardada no histórico") {
                HistoricoInventarios(File(contexto.filesDir, "historico_inventarios"))
                    .motivos(pendente.inventarioId)["E-FALTA-1"] == FilaJustificativas.Motivo.EMPRESTADO
            }

            // A justificada aparece com o motivo na lista
            onView(withText(org.hamcrest.Matchers.containsString("→ Emprestado"))).inRoot(isDialog()).check(matches(isDisplayed()))
            // A outra consta "em manutenção" no cadastro: aparece na lista e "Pelo cadastro" justifica de uma vez
            onView(withText(org.hamcrest.Matchers.containsString("(no cadastro: Em manutenção)"))).inRoot(isDialog())
                .check(matches(isDisplayed()))
            foto("08b-inventario-faltantes")
            onView(withText(contexto.getString(R.string.inventario_usar_cadastro, 1))).inRoot(isDialog()).perform(click())
            onView(withText(R.string.inventario_justificar_salvar)).inRoot(isDialog()).perform(click())
            aguardar("faltante justificado pelo cadastro") {
                onView(withText(org.hamcrest.Matchers.containsString("→ Em manutenção"))).inRoot(isDialog()).check(matches(isDisplayed())); true
            }
            assertEquals(
                FilaJustificativas.Motivo.MANUTENCAO,
                SincronizadorJustificativas.fila(contexto).pendentes().single { it.epc == "E-FALTA-2" }.motivo
            )

            // Volta ao resumo, fecha e confere que o inventário aparece em "Inventários anteriores"
            onView(withText(R.string.tela_voltar)).inRoot(isDialog()).perform(click())
            aguardar("resumo de novo") { onView(withText(R.string.comum_ok)).inRoot(isDialog()).check(matches(isDisplayed())); true }
            onView(withText(R.string.comum_ok)).inRoot(isDialog()).perform(click())
            aguardar("tela de início do inventário") { onView(withId(R.id.btnAnteriores)).check(matches(isDisplayed())); true }
            onView(withId(R.id.btnAnteriores)).perform(click())
            aguardar("lista dos anteriores com o local") {
                onView(withText(org.hamcrest.Matchers.containsString(local))).inRoot(isDialog()).check(matches(isDisplayed())); true
            }
            // As duas que faltaram foram justificadas: a linha não avisa "sem justificativa"
            onView(
                withText(
                    org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("faltaram 2"),
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("sem justificativa"))
                    )
                )
            ).inRoot(isDialog()).check(matches(isDisplayed()))
            foto("09-inventarios-anteriores")
        }
    }

    @Test
    fun planilhaPodeSerCompartilhada() {
        val arquivo = File(File(contexto.cacheDir, "relatorios").apply { mkdirs() }, "teste.csv").apply { writeText("a;b\n") }
        val uri = FileProvider.getUriForFile(contexto, "${contexto.packageName}.atualizacao", arquivo)
        assertTrue("Endereço do FileProvider para a planilha: $uri", uri.toString().contains("relatorios"))
    }
}
