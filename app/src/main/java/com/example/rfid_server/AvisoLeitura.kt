package com.example.rfid_server

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
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
 * [tagNova] e [bip] podem ser chamados de qualquer thread.
 */
class AvisoLeitura(context: Context) {

    private val appContext = context.applicationContext

    @Volatile
    private var som: ToneGenerator? = criarGeradorDeSom()

    @Suppress("DEPRECATION")
    private val vibrador = appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    private val limitador = LimitadorAviso(INTERVALO_MINIMO_MS)

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
    }
}
