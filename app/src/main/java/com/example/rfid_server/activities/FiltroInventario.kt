package com.example.rfid_server.activities

import com.example.rfid_server.dados.ItemEsperado
import com.example.rfid_server.dados.LidaInventario

/**
 * Filtro das abas Faltando e Lidas do inventário: só em memória (o banco não muda). Sem dependência do Android,
 * para poder ser testado no computador.
 */
object FiltroInventario {

    /** Abaixo disso a lista cabe na tela e o campo de filtro só ocuparia espaço. */
    const val MINIMO_PARA_FILTRAR = 20

    /** O campo aparece com lista grande, e não some enquanto houver texto digitado (senão o filtro ficaria escondido). */
    fun mostrarCampo(tamanhoDaLista: Int, termo: String): Boolean =
        tamanhoDaLista >= MINIMO_PARA_FILTRAR || termo.isNotBlank()

    /**
     * Faltando filtrado pelo [termo]. Os que o cadastro já explica (emprestado, em manutenção) vão para o fim, na
     * mesma ordem entre si: o operador procura primeiro os que deviam estar na sala.
     */
    fun faltando(itens: List<ItemEsperado>, termo: String): List<ItemEsperado> =
        filtrar(itens, termo) { listOf(it.patrimonio, it.epc) }.sortedBy { ResumoFinalizacao.motivoPeloCadastro(it) != null }

    fun lidas(itens: List<LidaInventario>, termo: String): List<LidaInventario> =
        filtrar(itens, termo) { listOf(it.patrimonio, it.epc) }

    /** Mantém os itens em que o patrimônio ou o EPC contém o [termo] (sem diferença de maiúsculas e minúsculas). */
    private fun <T> filtrar(itens: List<T>, termo: String, textos: (T) -> List<String?>): List<T> {
        val procurado = termo.trim()
        if (procurado.isEmpty()) return itens
        return itens.filter { item -> textos(item).any { it?.contains(procurado, ignoreCase = true) == true } }
    }
}
