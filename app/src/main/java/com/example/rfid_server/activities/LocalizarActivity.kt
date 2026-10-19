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
import com.example.rfid_server.EstadoLeitor
import com.example.rfid_server.Proximidade
import com.example.rfid_server.R
import com.example.rfid_server.RfidManager
import com.example.rfid_server.service.RespostaServidor
import com.example.rfid_server.service.TagService
import android.os.*
import android.util.Log
import androidx.annotation.RequiresPermission


class LocalizarActivity : AppCompatActivity() {

    // Leitor compartilhado com a tela principal (uma conexão só). Esta tela não desconecta
    private lateinit var rfidManager: RfidManager
    private var epcLocalizando: String? = null
    // Sem etiqueta definida (busca ainda não voltou do servidor) o leitor entrega qualquer etiqueta:
    // ignora, senão a barra e o bip guiariam até o item errado
    private val ouvinte = RfidManager.OuvinteLeitura { _, rssi ->
        if (epcLocalizando != null) runOnUiThread { atualizarFeedback(rssi) }
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

        btnBuscar.setOnClickListener {
            val patrimonio = edtPatrimonio.text.toString().trim()

            if (patrimonio.isEmpty()) {
                toast("Digite o patrimônio")
                return@setOnClickListener
            }

            txtStatus.text = "Buscando patrimônio..."
            buscarEIniciar(patrimonio)
        }

        // Aberta pelo inventário (item que falta no local): já busca o patrimônio. Só na primeira criação,
        // para não buscar de novo ao girar a tela
        intent.getStringExtra(EXTRA_PATRIMONIO)?.takeIf { savedInstanceState == null && it.isNotBlank() }?.let { patrimonio ->
            Log.i(TAG, "Localização do patrimônio $patrimonio aberta pelo inventário")
            edtPatrimonio.setText(patrimonio)
            txtStatus.text = "Buscando patrimônio..."
            buscarEIniciar(patrimonio)
        }
    }

    // ================= BUSCA =================

    private fun buscarEIniciar(patrimonio: String) {

        tagService.buscarTagPorPatrimonio(patrimonio) { resposta ->

            runOnUiThread {
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
        rfidManager.iniciarLocalizacao(epc)

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

    private fun atualizarFeedback(rssi: Int) {

        val nivel = Proximidade.nivel(rssi)

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
    // abrir a tela várias vezes o construtor passa a lançar RuntimeException
    private fun criarGeradorDeSom(): ToneGenerator? =
        try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 80)
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
        epcLocalizando?.let { rfidManager.iniciarLocalizacao(it) }
        avisoSessao.iniciar()
    }

    override fun onPause() {
        super.onPause()
        rfidManager.pararLocalizacao()
        rfidManager.removerOuvinte(this)
        avisoSessao.parar()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        epcLocalizando?.let { outState.putString(ESTADO_EPC, it) }
    }

    override fun onDestroy() {
        super.onDestroy()

        tone?.release()
        tone = null
        Log.d(TAG, "Tela de localização encerrada: gerador de som liberado")
    }

    companion object {
        private const val TAG = "LOCALIZAR"

        /** Patrimônio a localizar assim que a tela abre (usado pelo inventário). */
        const val EXTRA_PATRIMONIO = "patrimonio"
        private const val MSG_APERTE_GATILHO = "Aperte o gatilho para localizar"
        private const val MSG_AGUARDANDO_LEITOR = "Aguardando o leitor RFID conectar para localizar"
        private const val ESTADO_EPC = "epc_localizando"
    }
}
