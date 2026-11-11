package com.example.rfid_server.telas

import com.example.rfid_server.sessao.AvisoSessao
import com.example.rfid_server.sessao.exigirSessao
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.rfid_server.configuracoes.AppConfig
import com.example.rfid_server.leitor.RfidManager
import com.example.rfid_server.sessao.SessaoOperador

/**
 * Ciclo comum das telas que leem etiquetas: enquanto a tela está visível, as leituras do leitor vêm para
 * ela ([ouvinte]) e o operador é avisado antes de a sessão acabar ([AvisoSessao]). A ordem das chamadas
 * está em [CicloTelaDeLeitura].
 *
 * Crie no onCreate, depois de obter o [rfidManager] (e, nas telas que conferem a sessão já no onCreate,
 * depois dessa conferência: se a tela fechou ali, nada é registrado). Registra-se sozinha no ciclo de vida
 * da [activity], então a tela não precisa sobrescrever onResume/onPause para isso.
 *
 * Atenção: o observador roda DEPOIS do onResume e ANTES do onPause da Activity. Por isso tudo o que deve
 * acontecer na mesma sequência (ex.: algo que depende do ouvinte já definido) vai em [aoRetomar],
 * [antesDePausar] e [aoPausar], e não no onResume/onPause da tela.
 */
class TelaDeLeitura(
    activity: AppCompatActivity,
    rfidManager: RfidManager,
    ouvinte: RfidManager.OuvinteLeitura,
    aoMudarSessao: (SessaoOperador) -> Unit = {},
    lerAoRetomar: () -> Boolean = { true },
    aoRetomar: () -> Unit = {},
    antesDePausar: () -> Unit = {},
    aoPausar: () -> Unit = {}
) : DefaultLifecycleObserver {

    private val avisoSessao = AvisoSessao(activity, aoMudarSessao)
    private val appContext = activity.applicationContext

    // Tela acesa enquanto lê e 2 min depois (antes: o tempo todo, pelo keepScreenOn dos layouts)
    private val telaAcesa = TelaAcesa(
        Handler(Looper.getMainLooper()),
        ligar = { activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) },
        desligar = { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    )

    private val ciclo = CicloTelaDeLeitura(
        exigirSessao = { activity.exigirSessao() != null },
        // Conecta o leitor, se preciso, e reconecta sozinho se cair; a situação aparece na faixa do topo
        ligarLeitor = { rfidManager.definirOuvinte(activity, ouvinte) },
        desligarLeitor = { rfidManager.removerOuvinte(activity) },
        iniciarAvisoSessao = { avisoSessao.iniciar() },
        pararAvisoSessao = { avisoSessao.parar() },
        lerAoRetomar = lerAoRetomar,
        aoRetomar = aoRetomar,
        antesDePausar = antesDePausar,
        aoPausar = aoPausar
    )

    init {
        activity.lifecycle.addObserver(this)
        // Só com a tela na frente (o LiveData não entrega com ela pausada): apertar mantém acesa, soltar conta 2 min
        rfidManager.gatilhoApertado.observe(activity) { apertado ->
            if (apertado) telaAcesa.aoApertar() else telaAcesa.aoSoltar()
        }
    }

    override fun onResume(owner: LifecycleOwner) {
        ciclo.retomar()
        telaAcesa.aoAbrir()
        // Bloqueio do PIN vencido com o aparelho ligado: apaga, senão voltaria no próximo reinício
        AppConfig.limparBloqueioPinVencido(appContext)
    }

    override fun onPause(owner: LifecycleOwner) {
        telaAcesa.parar()
        ciclo.pausar()
    }
}
