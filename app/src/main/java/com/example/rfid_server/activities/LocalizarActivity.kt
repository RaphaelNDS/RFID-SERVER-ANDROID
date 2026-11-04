package com.example.rfid_server.activities

import android.Manifest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import com.example.rfid_server.EstadoLeitor
import com.example.rfid_server.Proximidade
import com.example.rfid_server.R
import com.example.rfid_server.RegrasLocalizar
import com.example.rfid_server.RfidManager
import com.example.rfid_server.VigiaSinal
import com.example.rfid_server.service.RespostaServidor
import com.example.rfid_server.service.TagService
import android.os.*
import android.util.Log
import androidx.annotation.RequiresPermission


class LocalizarActivity : AppCompatActivity() {

    // Leitor compartilhado com a tela principal (uma conexão só). Esta tela não desconecta
    private lateinit var rfidManager: RfidManager
    private var epcLocalizando: String? = null
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
    private lateinit var progress: ProgressBar
    private lateinit var edtPatrimonio: EditText
    private lateinit var btnBuscar: Button

    private lateinit var tagService: TagService
    private val avisoSessao = AvisoSessao(this)

    private var lastFeedback = 0L
    // Nulo se o aparelho não liberar o gerador de som: a localização segue só com vibração
    private var tone: ToneGenerator? = null
    private lateinit var vibrator: Vibrator

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

        txtStatus = findViewById(R.id.txtStatus)
        progress = findViewById(R.id.progressDistancia)
        edtPatrimonio = findViewById(R.id.edtPatrimonio)
        btnBuscar = findViewById(R.id.btnBuscar)
        if (epcLocalizando != null) txtStatus.text = MSG_APERTE_GATILHO

        tagService = TagService(this)
        rfidManager = RfidManager.get(this)
        mostrarEstadoLeitor(rfidManager)
        rfidManager.estado.observe(this) { aoMudarEstadoLeitor(it) }

        tone = criarGeradorDeSom()
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator

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
        val patrimonio = edtPatrimonio.text.toString().trim()

        if (patrimonio.isEmpty()) {
            toast("Digite o patrimônio")
            return
        }

        txtStatus.text = "Buscando patrimônio..."
        buscarEIniciar(patrimonio)
    }

    private fun iniciarPeloPedido(epc: String?, patrimonio: String?) {
        when (val inicio = RegrasLocalizar.inicio(epc, patrimonio)) {
            // O EPC já veio pronto: localiza sem perguntar ao servidor (funciona sem rede e com item sem patrimônio).
            // O patrimônio, se houver, fica no campo só para o operador ver o que procura
            is RegrasLocalizar.Inicio.PorEpc -> {
                Log.i(TAG, "Localização da etiqueta ${inicio.epc} aberta por outra tela (patrimônio ${patrimonio ?: "não cadastrado"})")
                if (!patrimonio.isNullOrBlank()) edtPatrimonio.setText(patrimonio)
                else toast("Item sem patrimônio: localizando pela etiqueta")
                iniciarRFID(inicio.epc)
            }

            is RegrasLocalizar.Inicio.PorPatrimonio -> {
                Log.i(TAG, "Localização do patrimônio ${inicio.patrimonio} aberta por outra tela")
                edtPatrimonio.setText(inicio.patrimonio)
                txtStatus.text = "Buscando patrimônio..."
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
                        txtStatus.text = "Localizando..."
                        iniciarRFID(resposta.valor)
                    }

                    RespostaServidor.NaoEncontrado -> {
                        Log.i(TAG, "Patrimônio $patrimonio não encontrado no servidor")
                        txtStatus.text = "Patrimônio não encontrado"
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

    private fun iniciarRFID(epc: String) {

        epcLocalizando = epc
        // Só liga no leitor com a tela em primeiro plano (dona das leituras). Se ela estiver em pausa
        // (ex.: outra tela por cima), o onResume liga com o epcLocalizando guardado acima
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) rfidManager.iniciarLocalizacao(this, epc)

        // A conexão do leitor é feita (e refeita, se cair) pelo RfidManager; a situação aparece na faixa do topo
        txtStatus.text = if (rfidManager.conectado) MSG_APERTE_GATILHO else MSG_AGUARDANDO_LEITOR
    }

    // Leitor conectou depois de o patrimônio ser encontrado: troca o "aguardando" pela instrução
    private fun aoMudarEstadoLeitor(estado: EstadoLeitor) {
        if (estado == EstadoLeitor.CONECTADO && txtStatus.text.toString() == MSG_AGUARDANDO_LEITOR) {
            txtStatus.text = MSG_APERTE_GATILHO
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
        txtStatus.text = MSG_SEM_SINAL
    }

    private fun atualizarFeedback(nivel: Int) {

        progress.progress = nivel
        txtStatus.text = Proximidade.descricao(nivel)

        emitirSomEVibracao(nivel)
    }

    @RequiresPermission(Manifest.permission.VIBRATE)
    private fun emitirSomEVibracao(nivel: Int) {

        val agora = System.currentTimeMillis()

        if (agora - lastFeedback < Proximidade.intervaloFeedbackMs(nivel)) return
        lastFeedback = agora

        // 🔊 SOM
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 80)

        // 📳 VIBRAÇÃO
        val duracao = Proximidade.duracaoVibracaoMs(nivel)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(
                    duracao,
                    VibrationEffect.DEFAULT_AMPLITUDE
                )
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duracao)
        }
    }

    // O ToneGenerator usa um recurso nativo limitado do aparelho: se não for liberado, depois de
    // abrir a tela várias vezes o construtor passa a lançar RuntimeException.
    // Volume de notificação (e não de mídia): no coletor o volume de mídia costuma ficar no zero e o bip sumia;
    // com o aparelho no silencioso, segue só a vibração
    private fun criarGeradorDeSom(): ToneGenerator? =
        try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Não foi possível criar o gerador de som: a localização vai funcionar só com vibração", e)
            null
        }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // Enquanto esta tela está visível, as leituras vêm para cá (e não para a tela principal)
    override fun onResume() {
        super.onResume()
        exigirSessao() ?: return
        rfidManager.definirOuvinte(this, ouvinte)
        epcLocalizando?.let { rfidManager.iniciarLocalizacao(this, it) }
        avisoSessao.iniciar()
    }

    override fun onPause() {
        super.onPause()
        rfidManager.pararLocalizacao()
        rfidManager.removerOuvinte(this)
        vigiaSinal.parar()
        avisoSessao.parar()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        epcLocalizando?.let { outState.putString(ESTADO_EPC, it) }
    }

    override fun onDestroy() {
        super.onDestroy()

        vigiaSinal.parar()
        tone?.release()
        tone = null
        Log.d(TAG, "Tela de localização encerrada: gerador de som liberado")
    }

    companion object {
        private const val TAG = "LOCALIZAR"

        /** Patrimônio a localizar assim que a tela abre. Com [EXTRA_EPC] junto, serve só para mostrar no campo. */
        const val EXTRA_PATRIMONIO = "patrimonio"

        /** EPC já conhecido (usado pelo inventário): localiza direto, sem consultar o servidor. */
        const val EXTRA_EPC = "epc"
        private const val MSG_SEM_SINAL = "Sem sinal da etiqueta"
        private const val MSG_APERTE_GATILHO = "Aperte o gatilho para localizar"
        private const val MSG_AGUARDANDO_LEITOR = "Aguardando o leitor RFID conectar para localizar"
        private const val ESTADO_EPC = "epc_localizando"
    }
}
