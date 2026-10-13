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
    private val ouvinte = RfidManager.OuvinteLeitura { _, rssi -> runOnUiThread { atualizarFeedback(rssi) } }
    private lateinit var txtStatus: TextView
    private lateinit var progress: ProgressBar
    private lateinit var edtPatrimonio: EditText
    private lateinit var btnBuscar: Button

    private lateinit var tagService: TagService

    private var lastFeedback = 0L
    // Nulo se o aparelho não liberar o gerador de som: a localização segue só com vibração
    private var tone: ToneGenerator? = null
    private lateinit var vibrator: Vibrator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_localizar)

        txtStatus = findViewById(R.id.txtStatus)
        progress = findViewById(R.id.progressDistancia)
        edtPatrimonio = findViewById(R.id.edtPatrimonio)
        btnBuscar = findViewById(R.id.btnBuscar)

        tagService = TagService(this)
        rfidManager = RfidManager.get(this)

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

        if (rfidManager.conectado) {
            txtStatus.text = "Aperte o gatilho para localizar"
            return
        }

        txtStatus.text = "Conectando ao leitor RFID..."
        rfidManager.conectar { ok ->
            runOnUiThread {
                if (ok) {
                    txtStatus.text = "Aperte o gatilho para localizar"
                } else {
                    Log.e(TAG, "Leitor RFID não conectado: não é possível localizar")
                    txtStatus.text = "Erro ao conectar RFID"
                    toast("Erro ao conectar RFID")
                }
            }
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
    }

    override fun onPause() {
        super.onPause()
        rfidManager.pararLocalizacao()
        rfidManager.removerOuvinte(this)
    }

    override fun onDestroy() {
        super.onDestroy()

        tone?.release()
        tone = null
        Log.d(TAG, "Tela de localização encerrada: gerador de som liberado")
    }

    companion object {
        private const val TAG = "LOCALIZAR"
    }
}
