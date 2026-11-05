package com.example.rfid_server.api

import android.os.Looper
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.rfid_server.erros.TipoErro
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Diferença entre o relógio do coletor e o do servidor, medida pelo cabeçalho Date de cada resposta (ver HttpClient).
 *
 * O relógio do coletor importa: a hora de cada leitura é a do aparelho, o servidor recusa ações "antigas demais" pelo
 * prazo de sincronização e o cofre de sessões apaga tokens pela idade. Um coletor com a data errada (bateria
 * descarregada, fuso trocado) causa recusas que parecem sem motivo.
 *
 * Desvio positivo = relógio do coletor ADIANTADO em relação ao servidor; negativo = atrasado.
 */
object RelogioServidor {

    private const val TAG = "RELOGIO"

    /** Acima disto o desvio vira aviso (log, erro para o servidor e texto para a tela). */
    const val LIMITE_AVISO_MS = 2L * 60 * 1000

    /** No máximo um erro "relógio errado" registrado para o servidor por este intervalo. */
    const val INTERVALO_REGISTRO_MS = 60L * 60 * 1000

    /** Desvio da medição mais recente (ms), ou null se ainda não houve resposta com o cabeçalho Date. */
    @Volatile
    var desvioMs: Long? = null
        private set

    /** Quando (relógio do aparelho, epoch ms) foi feita a medição mais recente. */
    @Volatile
    var medidoEm: Long? = null
        private set

    private val _desvio = MutableLiveData<Long?>()

    /** Desvio mais recente para a tela (ver [aviso] para o texto). */
    val desvio: LiveData<Long?> = _desvio

    // Relógio monotônico (não muda se acertarem a data do aparelho) da última vez que o erro foi registrado
    @Volatile
    private var ultimoRegistroErro: Long? = null

    /**
     * Desvio a partir de uma resposta: [dataServidorMs] é o cabeçalho Date; [inicioMs] e [fimMs], o relógio do
     * aparelho antes e depois da requisição. O servidor carimbou a resposta mais ou menos no meio da ida e volta,
     * por isso o desconto de metade do tempo da requisição.
     */
    fun calcularDesvio(dataServidorMs: Long, inicioMs: Long, fimMs: Long): Long =
        inicioMs + (fimMs - inicioMs) / 2 - dataServidorMs

    /**
     * Guarda uma medição. Acima de [LIMITE_AVISO_MS] avisa no log e chama [registrarErro] (tipo, mensagem), no máximo
     * uma vez por [INTERVALO_REGISTRO_MS] pelo relógio [monotonico]. Nunca lança erro: é chamado dentro do HttpClient.
     */
    fun registrarMedicao(
        desvio: Long,
        medidoEm: Long,
        registrarErro: (String, String) -> Unit,
        monotonico: () -> Long = { System.nanoTime() / 1_000_000 }
    ) {
        try {
            desvioMs = desvio
            this.medidoEm = medidoEm
            publicar(desvio)

            if (abs(desvio) <= LIMITE_AVISO_MS) return

            // Sempre em minutos (como pedido para a tela de erros do servidor); a tela do coletor usa o texto de [aviso]
            val minutos = (abs(desvio) / 60_000.0).roundToLong()
            val mensagem = "Relógio do coletor $minutos min ${if (desvio > 0) "adiantado" else "atrasado"} em relação ao servidor"
            Log.w(TAG, "$mensagem: acerte a data e a hora nas configurações do Android")

            val agora = monotonico()
            val ultimo = ultimoRegistroErro
            if (ultimo != null && agora - ultimo < INTERVALO_REGISTRO_MS) return
            ultimoRegistroErro = agora
            registrarErro(TipoErro.FALHA_GERAL, mensagem)
        } catch (e: Throwable) {
            Log.e(TAG, "Falha ao conferir o relógio do coletor", e)
        }
    }

    /**
     * Texto para a tela quando o desvio passa de [LIMITE_AVISO_MS], ou null se o relógio está certo (ou ainda não
     * foi medido).
     */
    fun aviso(desvioMs: Long?): String? {
        if (desvioMs == null || abs(desvioMs) <= LIMITE_AVISO_MS) return null
        return "O relógio do coletor está ${descrever(desvioMs)}: acerte a data e a hora nas configurações do Android"
    }

    /** "7 min adiantado", "3 h 20 min atrasado"... */
    internal fun descrever(desvioMs: Long): String {
        val minutos = (abs(desvioMs) / 60_000.0).roundToLong()
        val direcao = if (desvioMs > 0) "adiantado" else "atrasado"
        val tempo = when {
            minutos < 60 -> "$minutos min"
            minutos < 48 * 60 -> "${minutos / 60} h" + (if (minutos % 60 > 0) " ${minutos % 60} min" else "")
            else -> "${minutos / (24 * 60)} dia(s)"
        }
        return "$tempo $direcao"
    }

    // A medição chega na thread da rede (postValue). Nos testes JVM não há thread principal de verdade: setValue
    // funciona com a InstantTaskExecutorRule e, sem ela, a falha é ignorada (a tela não existe ali)
    private fun publicar(desvio: Long) {
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) _desvio.setValue(desvio) else _desvio.postValue(desvio)
        } catch (_: Exception) {
        }
    }

    /** Só para os testes: volta ao estado inicial. */
    internal fun reiniciar() {
        desvioMs = null
        medidoEm = null
        ultimoRegistroErro = null
    }
}
