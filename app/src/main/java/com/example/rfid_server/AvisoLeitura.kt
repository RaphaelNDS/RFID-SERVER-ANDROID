package com.example.rfid_server

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log

/**
 * Bip curto e vibração a cada etiqueta NOVA (repetidas não avisam), para o operador não precisar
 * olhar a tela a cada leitura. Pode ser desligado nas Configurações do coletor.
 *
 * Também serve para outros avisos sonoros: [bip] toca sempre (não depende do ajuste nem do limite de
 * ritmo), com as durações escolhidas por quem chama — o Localizar usa para guiar até a etiqueta.
 *
 * Crie no onCreate da tela e chame [liberar] no onDestroy: o ToneGenerator usa um recurso nativo
 * limitado do aparelho (se não for liberado, depois de algumas telas o som para de funcionar).
 * [erro] é o aviso de falha do leitor (dois bips graves). [tagNova], [bip] e [erro] podem ser chamados de qualquer thread.
 */
class AvisoLeitura(context: Context) {

    private val appContext = context.applicationContext

    @Volatile
    private var som: ToneGenerator? = criarGeradorDeSom()

    @Suppress("DEPRECATION")
    private val vibrador = appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    private val limitador = LimitadorAviso(INTERVALO_MINIMO_MS)

    // Segundo bip do [erro]
    private val principal = Handler(Looper.getMainLooper())

    fun tagNova() {
        if (!AppConfig.isAvisoLeitura(appContext)) return
        if (!limitador.liberar()) return

        bip(DURACAO_BIP_MS, DURACAO_VIBRACAO_MS)
    }

    /** Toca o bip por [duracaoTomMs] e vibra por [duracaoVibracaoMs], sem olhar o ajuste "aviso a cada etiqueta nova". */
    fun bip(duracaoTomMs: Int, duracaoVibracaoMs: Long) {
        try {
            som?.startTone(ToneGenerator.TONE_PROP_BEEP, duracaoTomMs)
            vibrar(duracaoVibracaoMs)
        } catch (e: RuntimeException) {
            // Tela fechando ao mesmo tempo (gerador já liberado): perder um bip não tem problema
            Log.d(TAG, "Bip não emitido: ${e.message}")
        }
    }

    /**
     * Falha do leitor (ex.: o gatilho foi apertado e a leitura não começou): dois bips graves curtos e vibração dupla,
     * bem diferente do bip agudo de etiqueta nova. Toca sempre, no volume de alarme, mesmo com o ajuste
     * "aviso a cada etiqueta nova" desligado: é um erro que o operador precisa perceber.
     */
    fun erro() {
        tomErro()
        principal.postDelayed(::tomErro, INTERVALO_BIPS_ERRO_MS)
        try {
            vibrador?.vibrate(VibrationEffect.createWaveform(VIBRACAO_ERRO, -1))
        } catch (e: RuntimeException) {
            Log.d(TAG, "Vibração de erro não emitida: ${e.message}")
        }
    }

    private fun tomErro() {
        try {
            som?.startTone(ToneGenerator.TONE_PROP_NACK, DURACAO_BIP_ERRO_MS)
        } catch (e: RuntimeException) {
            // Gerador liberado entre o primeiro e o segundo bip: perder um bip não tem problema
            Log.d(TAG, "Bip de erro não emitido: ${e.message}")
        }
    }

    fun liberar() {
        som?.release()
        som = null
    }

    private fun vibrar(duracaoMs: Long) {
        val v = vibrador ?: return
        // O app exige Android 8 (minSdk 26), que já tem VibrationEffect
        v.vibrate(VibrationEffect.createOneShot(duracaoMs, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    // Volume de alarme (e não de mídia): no coletor o volume de mídia costuma ficar no zero e o bip sumia.
    // O de alarme toca mesmo com o aparelho no silencioso, que é o que se quer no chão de fábrica
    private fun criarGeradorDeSom(): ToneGenerator? =
        try {
            ToneGenerator(AudioManager.STREAM_ALARM, VOLUME)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Não foi possível criar o gerador de som: os avisos vão ser só vibração", e)
            null
        }

    companion object {
        private const val TAG = "AVISO_LEITURA"
        private const val VOLUME = 80
        private const val DURACAO_BIP_MS = 60
        private const val DURACAO_VIBRACAO_MS = 40L

        /** Com muitas etiquetas de uma vez, no máximo um bip a cada 150 ms (fica um "trrr" e não um bip contínuo). */
        private const val INTERVALO_MINIMO_MS = 150L

        // Bip de erro: tom grave (TONE_PROP_NACK, ~400 Hz) duas vezes, com uma pausa curta entre os dois
        private const val DURACAO_BIP_ERRO_MS = 120
        private const val INTERVALO_BIPS_ERRO_MS = 220L
        private val VIBRACAO_ERRO = longArrayOf(0, 120, 100, 120)
    }
}
