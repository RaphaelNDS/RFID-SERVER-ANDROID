package com.example.rfid_server

import com.example.rfid_server.leitor.AvisoLeitura
import com.example.rfid_server.leitor.CodigoEtiqueta
import com.example.rfid_server.leitor.FiltroLeitura
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.rfid_server.sessao.LoginActivity
import com.example.rfid_server.configuracoes.SettingsActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Teste rápido no próprio coletor, com a versão de teste (br.com.bkpbr.rfidandroid.teste), que fica ao lado do app de
 * produção sem mexer nele: as telas abrem, os recursos do aparelho (som, ícone) funcionam e as regras novas valem no
 * Android de verdade. Rodar SÓ no coletor com scripts/testar-no-coletor.sh (o connectedDebugAndroidTest do Gradle roda em
 * todos os aparelhos que o adb enxerga, mesmo com ANDROID_SERIAL):
 *   scripts/testar-no-coletor.sh [classe] [--fotos]
 * Não usa o leitor RFID (ele é do app de produção enquanto ele estiver aberto) nem o servidor.
 */
@RunWith(AndroidJUnit4::class)
class TelasNoColetorTest {

    private val contexto get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun versaoDeTesteNaoEOAppDeProducao() {
        assertEquals("br.com.bkpbr.rfidandroid.teste", contexto.packageName)
    }

    @Test
    fun loginAbreSemSessao() {
        ActivityScenario.launch(LoginActivity::class.java).use {
            // Com o último email lembrado, o teclado abre na senha e cobre o botão: fecha antes de conferir
            closeSoftKeyboard()
            onView(withId(R.id.btnEntrar)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun configuracoesAbremComOsCampos() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            onView(withId(R.id.edtIp)).check(matches(isDisplayed()))
            onView(withId(R.id.btnSalvar)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun avisosSonorosFuncionamNoAparelho() {
        val aviso = AvisoLeitura(contexto)
        try {
            aviso.tagNova(AvisoLeitura.Tipo.NORMAL)
            aviso.tagNova(AvisoLeitura.Tipo.FORA_DA_LISTA)
            aviso.concluido()
            aviso.erro()
        } finally {
            aviso.liberar()
        }
    }

    @Test
    fun iconeAdaptativoCarrega() {
        val icone = ContextCompat.getDrawable(contexto, R.mipmap.ic_launcher)
        assertTrue("Ícone adaptativo (fundo + etiqueta)", icone is AdaptiveIconDrawable)
    }

    @Test
    fun regrasDaEtiquetaNoAndroid() {
        assertEquals("424B50303132332F32303139", CodigoEtiqueta.gerar("0123/2019"))
        assertTrue(FiltroLeitura.aceita(CodigoEtiqueta.gerar("PAT-1")!!, -50, -55, soEmpresa = true))
    }
}
