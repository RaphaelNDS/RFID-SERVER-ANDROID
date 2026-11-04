package com.example.rfid_server

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo

/**
 * Decisões da tela Localizar que não dependem da tela (testadas no computador).
 * As constantes do Android usadas aqui são números fixos, copiados pelo compilador.
 */
object RegrasLocalizar {

    /** Sem leitura da etiqueta por este tempo, a barra volta a zero (senão fica parada no último valor). */
    const val SEM_SINAL_MS = 800L

    /** Como a tela começa quando aberta por outra (ex.: aba "Faltando" do inventário). */
    sealed class Inicio {
        /** EPC já conhecido: localiza direto, sem consultar o servidor (funciona sem rede). */
        data class PorEpc(val epc: String) : Inicio()
        /** Só o patrimônio: precisa perguntar o EPC ao servidor. */
        data class PorPatrimonio(val patrimonio: String) : Inicio()
        object Nada : Inicio()
    }

    fun inicio(epc: String?, patrimonio: String?): Inicio = when {
        !epc.isNullOrBlank() -> Inicio.PorEpc(epc.trim())
        !patrimonio.isNullOrBlank() -> Inicio.PorPatrimonio(patrimonio.trim())
        else -> Inicio.Nada
    }

    enum class AcaoTecla {
        /** Dispara a busca (mesma ação do botão Buscar). */
        BUSCAR,
        /** Engole o evento sem buscar (ex.: o "solto" do Enter, que já buscou no "apertado"). */
        CONSUMIR,
        /** Não é com a gente: o campo trata normalmente. */
        IGNORAR
    }

    /**
     * Tecla de ação do campo de patrimônio. O teclado virtual manda IME_ACTION_SEARCH; o leitor de código
     * de barras (DataWedge) e o teclado físico mandam Enter, que chega duas vezes (apertado e solto):
     * busca só no apertado, senão a busca sairia duplicada.
     */
    fun acaoTecla(actionId: Int, keyCode: Int?, keyAction: Int?): AcaoTecla = when {
        actionId == EditorInfo.IME_ACTION_SEARCH -> AcaoTecla.BUSCAR
        keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ->
            if (keyAction == KeyEvent.ACTION_DOWN) AcaoTecla.BUSCAR else AcaoTecla.CONSUMIR
        else -> AcaoTecla.IGNORAR
    }
}

/**
 * Vigia de sinal do Localizar: cada leitura da etiqueta rearma o prazo; se ele vencer sem leitura,
 * chama [aoPerderSinal]. O agendamento é recebido de fora (Handler na tela, falso nos testes).
 * Usar só na thread principal.
 */
class VigiaSinal(
    private val agendador: Agendador,
    private val esperaMs: Long = RegrasLocalizar.SEM_SINAL_MS,
    private val aoPerderSinal: () -> Unit
) {
    fun interface Agendador {
        /** Agenda [acao] para daqui a [ms] e devolve como cancelar. */
        fun agendar(ms: Long, acao: () -> Unit): () -> Unit
    }

    private var cancelarPendente: (() -> Unit)? = null

    val armado: Boolean
        get() = cancelarPendente != null

    /** Chegou leitura. Com [localizando] false (sem etiqueta definida ou tela em pausa) só desarma. */
    fun leitura(localizando: Boolean) {
        parar()
        if (!localizando) return
        cancelarPendente = agendador.agendar(esperaMs) {
            cancelarPendente = null
            aoPerderSinal()
        }
    }

    fun parar() {
        cancelarPendente?.invoke()
        cancelarPendente = null
    }
}
