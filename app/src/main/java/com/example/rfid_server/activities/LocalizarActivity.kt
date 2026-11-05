package com.example.rfid_server.activities

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import com.example.rfid_server.AvisoLeitura
import com.example.rfid_server.EstadoLeitor
import com.example.rfid_server.Proximidade
import com.example.rfid_server.R
import com.example.rfid_server.RegrasLocalizar
import com.example.rfid_server.RfidManager
import com.example.rfid_server.RitmoAviso
import com.example.rfid_server.VigiaSinal
import com.example.rfid_server.service.RespostaServidor
import com.example.rfid_server.service.TagService
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import android.os.Handler
import android.os.Looper
import android.util.Log


class LocalizarActivity : AppCompatActivity() {

    // Leitor compartilhado com a tela principal (uma conexão só). Esta tela não desconecta
    private lateinit var rfidManager: RfidManager
    private var epcLocalizando: String? = null
    // Patrimônio da etiqueta localizada (só para a linha "Procurando: …"); null quando o item não tem
    private var patrimonioLocalizando: String? = null
    // Número da busca mais recente: a resposta de uma busca anterior (cliques seguidos em "Buscar") é descartada
    private var ultimaBusca = 0
    // Sem etiqueta definida (busca ainda não voltou do servidor) o leitor entrega qualquer etiqueta:
    // ignora, senão a barra e o bip guiariam até o item errado
    private val ouvinte = object : RfidManager.OuvinteLeitura {
        override fun aoLer(epc: String, rssi: Int) {
            if (epcLocalizando != null) runOnUiThread { aoReceberSinal(Proximidade.nivel(rssi)) }
        }

        // Com a etiqueta definida o gatilho liga o TagLocationing do leitor, que manda a distância direto
        override fun aoLocalizar(epc: String, distancia: Int) {
            if (epcLocalizando != null) runOnUiThread { aoReceberSinal(Proximidade.nivelDaDistancia(distancia)) }
        }
    }
    private lateinit var txtStatus: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var edtPatrimonio: EditText
    private lateinit var btnBuscar: Button
    private lateinit var cartaoBusca: View
    private lateinit var linhaProcurando: View
    private lateinit var txtProcurando: TextView
    // Última faixa pintada na barra: só troca a cor quando muda de faixa
    private var faixaPintada: Proximidade.Faixa? = null

    private lateinit var tagService: TagService

    // Bip e vibração da localização (sempre tocam: não dependem do ajuste "aviso a cada etiqueta nova").
    // Quanto mais perto, mais rápido o bip ([Proximidade.intervaloFeedbackMs])
    private lateinit var aviso: AvisoLeitura
    private val ritmoBip = RitmoAviso()

    // Sem leitura da etiqueta por um instante (saiu do alcance, gatilho solto): a barra não pode ficar parada no último valor
    private val handler = Handler(Looper.getMainLooper())
    private val vigiaSinal = VigiaSinal(
        agendador = { ms, acao ->
            val r = Runnable { acao() }
            handler.postDelayed(r, ms)
            ({ handler.removeCallbacks(r) })
        },
        aoPerderSinal = { aoPerderSinal() }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_localizar)
        // Tela recriada (ex.: girou): continua localizando a mesma etiqueta (onResume reinicia a localização)
        epcLocalizando = savedInstanceState?.getString(ESTADO_EPC)
        patrimonioLocalizando = savedInstanceState?.getString(ESTADO_PATRIMONIO)

        findViewById<MaterialToolbar>(R.id.barraLocalizar).setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        txtStatus = findViewById(R.id.txtStatus)
        progress = findViewById(R.id.progressDistancia)
        edtPatrimonio = findViewById(R.id.edtPatrimonio)
        btnBuscar = findViewById(R.id.btnBuscar)
        cartaoBusca = findViewById(R.id.cartaoBusca)
        linhaProcurando = findViewById(R.id.linhaProcurando)
        txtProcurando = findViewById(R.id.txtProcurando)
        findViewById<Button>(R.id.btnAlterarBusca).setOnClickListener { alterarBusca() }
        epcLocalizando?.let {
            txtStatus.setText(R.string.localizar_aperte_gatilho)
            mostrarProcurando(it, patrimonioLocalizando)
        }

        tagService = TagService(this)
        rfidManager = RfidManager.get(this)
        mostrarEstadoLeitor(rfidManager)
        rfidManager.estado.observe(this) { aoMudarEstadoLeitor(it) }

        // Enquanto esta tela está visível, as leituras vêm para cá (e não para a tela principal).
        // Ao voltar, retoma a localização da etiqueta guardada; ao sair, encerra a localização ANTES de
        // soltar o leitor (o RfidManager só aceita pedidos de localização da tela dona das leituras)
        TelaDeLeitura(
            this, rfidManager, ouvinte,
            aoRetomar = { epcLocalizando?.let { rfidManager.iniciarLocalizacao(this, it) } },
            antesDePausar = { rfidManager.pararLocalizacao() },
            aoPausar = { vigiaSinal.parar() }
        )

        aviso = AvisoLeitura(this)

        btnBuscar.setOnClickListener { buscarDoCampo() }

        // "Buscar" do teclado virtual e Enter do leitor de código de barras (DataWedge) fazem o mesmo que o botão
        edtPatrimonio.setOnEditorActionListener { _, actionId, evento ->
            when (RegrasLocalizar.acaoTecla(actionId, evento?.keyCode, evento?.action)) {
                RegrasLocalizar.AcaoTecla.BUSCAR -> { buscarDoCampo(); true }
                RegrasLocalizar.AcaoTecla.CONSUMIR -> true
                RegrasLocalizar.AcaoTecla.IGNORAR -> false
            }
        }

        // Aberta por outra tela (ex.: item que falta no inventário). Só na primeira criação, para não começar
        // de novo ao girar a tela
        if (savedInstanceState == null) iniciarPeloPedido(intent.getStringExtra(EXTRA_EPC), intent.getStringExtra(EXTRA_PATRIMONIO))
    }

    private fun buscarDoCampo() {
        // O cadastro grava o patrimônio em MAIÚSCULAS: "ab12" digitado (ou lido pelo código de barras) acha o "AB12"
        val patrimonio = RegrasLocalizar.normalizarPatrimonio(edtPatrimonio.text.toString())

        if (patrimonio.isEmpty()) {
            toast(getString(R.string.localizar_digite_patrimonio))
            return
        }

        // O teclado cobria o cartão de proximidade
        fecharTeclado()
        txtStatus.setText(R.string.localizar_buscando)
        buscarEIniciar(patrimonio)
    }

    private fun iniciarPeloPedido(epc: String?, patrimonio: String?) {
        when (val inicio = RegrasLocalizar.inicio(epc, patrimonio)) {
            // O EPC já veio pronto: localiza sem perguntar ao servidor (funciona sem rede e com item sem patrimônio).
            // O patrimônio, se houver, fica no campo e na linha "Procurando: …"; sem ele, a linha mostra o EPC
            is RegrasLocalizar.Inicio.PorEpc -> {
                Log.i(TAG, "Localização da etiqueta ${inicio.epc} aberta por outra tela (patrimônio ${patrimonio ?: "não cadastrado"})")
                if (!patrimonio.isNullOrBlank()) edtPatrimonio.setText(patrimonio)
                iniciarRFID(inicio.epc, patrimonio)
            }

            is RegrasLocalizar.Inicio.PorPatrimonio -> {
                Log.i(TAG, "Localização do patrimônio ${inicio.patrimonio} aberta por outra tela")
                edtPatrimonio.setText(inicio.patrimonio)
                txtStatus.setText(R.string.localizar_buscando)
                buscarEIniciar(inicio.patrimonio)
            }

            RegrasLocalizar.Inicio.Nada -> Unit
        }
    }

    // ================= BUSCA =================

    private fun buscarEIniciar(patrimonio: String) {
        // Nova busca: a etiqueta anterior deixa de ser localizada (se esta falhar, a tela não pode continuar
        // guiando até o item antigo) e qualquer resposta de uma busca anterior passa a ser descartada
        val busca = ++ultimaBusca
        epcLocalizando = null
        patrimonioLocalizando = null
        rfidManager.pararLocalizacao()
        vigiaSinal.parar()
        progress.progress = 0

        tagService.buscarTagPorPatrimonio(patrimonio) { resposta ->

            runOnUiThread {
                // A resposta pode chegar depois de a tela sair (o servidor demora até 15 s): aí não faz nada
                if (isFinishing || isDestroyed) {
                    Log.i(TAG, "Resposta da busca do patrimônio $patrimonio chegou depois de a tela fechar: ignorada")
                    return@runOnUiThread
                }
                if (busca != ultimaBusca) {
                    Log.i(TAG, "Resposta da busca do patrimônio $patrimonio chegou depois de uma busca mais nova: ignorada")
                    return@runOnUiThread
                }

                when (resposta) {
                    is RespostaServidor.Sucesso -> {
                        Log.i(TAG, "Patrimônio $patrimonio → tag ${resposta.valor}")
                        txtStatus.setText(R.string.localizar_localizando)
                        iniciarRFID(resposta.valor, patrimonio)
                    }

                    RespostaServidor.NaoEncontrado -> {
                        Log.i(TAG, "Patrimônio $patrimonio não encontrado no servidor")
                        txtStatus.setText(R.string.localizar_nao_encontrado)
                    }

                    is RespostaServidor.Falha -> {
                        Log.w(TAG, "Falha ao buscar o patrimônio $patrimonio: ${resposta.mensagem}")
                        txtStatus.text = resposta.mensagem
                    }
                }
            }
        }
    }

    // ================= RFID =================

    private fun iniciarRFID(epc: String, patrimonio: String?) {

        epcLocalizando = epc
        patrimonioLocalizando = patrimonio?.takeIf { it.isNotBlank() }
        // Teclado fora da frente e cartão de busca recolhido: a tela fica para a proximidade
        fecharTeclado()
        mostrarProcurando(epc, patrimonioLocalizando)
        // Só liga no leitor com a tela em primeiro plano (dona das leituras). Se ela estiver em pausa
        // (ex.: outra tela por cima), o onResume liga com o epcLocalizando guardado acima
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) rfidManager.iniciarLocalizacao(this, epc)

        // A conexão do leitor é feita (e refeita, se cair) pelo RfidManager; a situação aparece na faixa do topo
        txtStatus.setText(if (rfidManager.conectado) R.string.localizar_aperte_gatilho else R.string.localizar_aguardando_leitor)
    }

    // Leitor conectou depois de o patrimônio ser encontrado: troca o "aguardando" pela instrução
    private fun aoMudarEstadoLeitor(estado: EstadoLeitor) {
        if (estado == EstadoLeitor.CONECTADO && txtStatus.text.toString() == getString(R.string.localizar_aguardando_leitor)) {
            txtStatus.setText(R.string.localizar_aperte_gatilho)
        }
    }

    // ================= FEEDBACK =================

    // Leitura da etiqueta procurada (já na thread principal). Pode chegar logo depois do onPause (já estava
    // na fila): aí não rearma o vigia, que ficaria disparando com a tela parada
    private fun aoReceberSinal(nivel: Int) {
        if (epcLocalizando == null) return
        atualizarFeedback(nivel)
        vigiaSinal.leitura(localizando = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    private fun aoPerderSinal() {
        if (epcLocalizando == null) return
        progress.progress = 0
        txtStatus.setText(R.string.localizar_sem_sinal)
    }

    private fun atualizarFeedback(nivel: Int) {

        progress.progress = nivel
        pintarBarra(Proximidade.faixa(nivel))
        txtStatus.text = Proximidade.descricao(nivel)

        // Quanto mais perto, mais rápido o bip e mais forte a vibração
        if (ritmoBip.liberar(Proximidade.intervaloFeedbackMs(nivel))) {
            aviso.bip(DURACAO_BIP_MS, Proximidade.duracaoVibracaoMs(nivel))
        }
    }

    /** Cor da barra pela faixa de proximidade: cinza longe, âmbar aproximando, verde muito perto. */
    private fun pintarBarra(faixa: Proximidade.Faixa) {
        if (faixa == faixaPintada) return
        faixaPintada = faixa
        val cor = when (faixa) {
            Proximidade.Faixa.LONGE -> R.color.texto_terciario
            Proximidade.Faixa.APROXIMANDO -> R.color.atencao
            Proximidade.Faixa.MUITO_PERTO -> R.color.sucesso
        }
        progress.setIndicatorColor(ContextCompat.getColor(this, cor))
    }

    // ================= BUSCA RECOLHIDA =================

    /** Recolhe o cartão de busca e mostra "Procurando: Patrimônio X" (ou o EPC, para item sem patrimônio). */
    private fun mostrarProcurando(epc: String, patrimonio: String?) {
        txtProcurando.text = when (val alvo = RegrasLocalizar.alvo(epc, patrimonio)) {
            is RegrasLocalizar.Alvo.Patrimonio -> getString(R.string.localizar_procurando_patrimonio, alvo.patrimonio)
            is RegrasLocalizar.Alvo.Epc -> getString(R.string.localizar_procurando_epc, alvo.epc)
        }
        cartaoBusca.isVisible = false
        linhaProcurando.isVisible = true
    }

    /** "Alterar": volta o cartão de busca com o campo pronto para digitar (a localização atual segue até buscar outro). */
    private fun alterarBusca() {
        linhaProcurando.isVisible = false
        cartaoBusca.isVisible = true
        edtPatrimonio.requestFocus()
        edtPatrimonio.text?.let { edtPatrimonio.setSelection(it.length) }
        getSystemService(InputMethodManager::class.java)?.showSoftInput(edtPatrimonio, InputMethodManager.SHOW_IMPLICIT)
    }

    /** Tira o teclado da frente do cartão de proximidade e solta o foco do campo. */
    private fun fecharTeclado() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(edtPatrimonio.windowToken, 0)
        edtPatrimonio.clearFocus()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        epcLocalizando?.let { outState.putString(ESTADO_EPC, it) }
        patrimonioLocalizando?.let { outState.putString(ESTADO_PATRIMONIO, it) }
    }

    override fun onDestroy() {
        super.onDestroy()

        vigiaSinal.parar()
        // O ToneGenerator usa um recurso nativo limitado do aparelho: se não for liberado, depois de
        // abrir a tela várias vezes o som para de funcionar
        aviso.liberar()
        Log.d(TAG, "Tela de localização encerrada: gerador de som liberado")
    }

    companion object {
        private const val TAG = "LOCALIZAR"

        /** Patrimônio a localizar assim que a tela abre. Com [EXTRA_EPC] junto, serve só para mostrar no campo. */
        const val EXTRA_PATRIMONIO = "patrimonio"

        /** EPC já conhecido (usado pelo inventário): localiza direto, sem consultar o servidor. */
        const val EXTRA_EPC = "epc"
        private const val ESTADO_EPC = "epc_localizando"
        private const val ESTADO_PATRIMONIO = "patrimonio_localizando"
        private const val DURACAO_BIP_MS = 80
    }
}
