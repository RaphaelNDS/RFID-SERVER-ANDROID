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
import com.example.rfid_server.R
import com.example.rfid_server.RfidManager
import com.example.rfid_server.TagService
import android.os.*
import androidx.annotation.RequiresPermission


class LocalizarActivity : AppCompatActivity() {

    private var rfidManager: RfidManager? = null
    private lateinit var txtStatus: TextView
    private lateinit var progress: ProgressBar
    private lateinit var edtPatrimonio: EditText
    private lateinit var btnBuscar: Button

    private lateinit var tagService: TagService

    private var lastFeedback = 0L
    private lateinit var tone: ToneGenerator
    private lateinit var vibrator: Vibrator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_localizar)

        txtStatus = findViewById(R.id.txtStatus)
        progress = findViewById(R.id.progressDistancia)
        edtPatrimonio = findViewById(R.id.edtPatrimonio)
        btnBuscar = findViewById(R.id.btnBuscar)

        tagService = TagService(this)

        tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
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

        tagService.buscarTagPorPatrimonio(patrimonio) { epc ->

            runOnUiThread {

                if (epc == null) {
                    txtStatus.text = "Patrimônio não encontrado"
                    return@runOnUiThread
                }

                txtStatus.text = "Localizando..."
                iniciarRFID(epc)
            }
        }
    }

    // ================= RFID =================

    private fun iniciarRFID(epc: String) {

        if (rfidManager == null) {

            rfidManager = RfidManager(this) { _, rssi ->
                runOnUiThread { atualizarFeedback(rssi) }
            }

            if (rfidManager?.connect() != true) {
                toast("Erro ao conectar RFID")
                return
            }
        }

        rfidManager?.startLocating(epc)
        txtStatus.text = "Aperte o gatilho para localizar"
    }

    // ================= FEEDBACK =================

    private fun atualizarFeedback(rssi: Int) {

        val nivel = when {
            rssi >= -35 -> 100
            rssi >= -40 -> 85
            rssi >= -45 -> 70
            rssi >= -50 -> 55
            rssi >= -55 -> 40
            rssi >= -60 -> 25
            else -> 10
        }

        progress.progress = nivel

        txtStatus.text = when {
            nivel >= 90 -> "🔥 Tag muito próxima"
            nivel >= 70 -> "Muito perto"
            nivel >= 50 -> "Aproximando"
            nivel >= 30 -> "Longe"
            else -> "Muito longe"
        }


        emitirSomEVibracao(nivel)
    }

    @RequiresPermission(Manifest.permission.VIBRATE)
    private fun emitirSomEVibracao(nivel: Int) {

        val agora = System.currentTimeMillis()

        val intervalo = when {
            nivel > 80 -> 120
            nivel > 60 -> 250
            nivel > 40 -> 450
            nivel > 20 -> 700
            else -> 1200
        }

        if (agora - lastFeedback < intervalo) return
        lastFeedback = agora

        // 🔊 SOM
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 80)

        // 📳 VIBRAÇÃO
        val duracao = when {
            nivel > 80 -> 80
            nivel > 60 -> 60
            nivel > 40 -> 40
            nivel > 20 -> 25
            else -> 15
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(
                    duracao.toLong(),
                    VibrationEffect.DEFAULT_AMPLITUDE
                )
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duracao.toLong())
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        rfidManager?.stopLocating()
        rfidManager?.disconnect()
    }
}
