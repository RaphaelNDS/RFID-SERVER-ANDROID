package com.example.rfid_server

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log

/**
 * Bip curto e vibração a cada etiqueta NOVA (repetidas não avisam), para o operador não precisar
 * olhar a tela a cada leitura. Pode ser desligado nas Configurações do coletor.
 *
 * Crie no onCreate da tela e chame [liberar] no onDestroy: o ToneGenerator usa um recurso nativo
 * limitado do aparelho (se não for liberado, depois de algumas telas o som para de funcionar).
 * [tagNova] pode ser chamado de qualquer thread.
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

        try {
            som?.startTone(ToneGenerator.TONE_PROP_BEEP, DURACAO_BIP_MS)
            vibrar()
        } catch (e: RuntimeException) {
            // Tela fechando ao mesmo tempo (gerador já liberado): perder um bip não tem problema
            Log.d(TAG, "Aviso de tag nova não emitido: ${e.message}")
        }
    }

    fun liberar() {
        som?.release()
        som = null
    }

    private fun vibrar() {
        val v = vibrador ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(DURACAO_VIBRACAO_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(DURACAO_VIBRACAO_MS)
        }
    }

    private fun criarGeradorDeSom(): ToneGenerator? =
        try {
            ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Não foi possível criar o gerador de som: o aviso de tag nova vai ser só vibração", e)
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
