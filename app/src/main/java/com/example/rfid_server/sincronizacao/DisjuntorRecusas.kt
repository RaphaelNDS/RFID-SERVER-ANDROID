package com.example.rfid_server.sincronizacao

/**
 * Percebe quando o servidor passa a recusar tudo (uma regressão que devolva 400 para toda leitura, um proxy no
 * caminho...): [limite] recusas seguidas com a MESMA mensagem disparam o disjuntor. Aí a sincronização para e
 * essas leituras voltam para a fila, em vez de a fila inteira ser descartada leitura por leitura.
 * Recusas com mensagens diferentes (uma etiqueta com problema aqui, outra ali) não disparam.
 * Depois de disparar fica travado: o que vem depois na mesma passada não desfaz o disparo.
 */
class DisjuntorRecusas(private val limite: Int = LIMITE_PADRAO) {

    private val idsSeguidos = mutableListOf<Long>()
    private var mensagemAtual: String? = null

    /** Leitura recusada. Devolve true quando o disjuntor dispara. */
    fun recusada(id: Long, mensagem: String): Boolean {
        if (disparou) {
            if (mensagem == mensagemAtual) idsSeguidos += id
            return true
        }
        if (mensagem != mensagemAtual) {
            idsSeguidos.clear()
            mensagemAtual = mensagem
        }
        idsSeguidos += id
        return disparou
    }

    /** Leitura aceita pelo servidor: a sequência de recusas acabou. */
    fun aceita() {
        if (disparou) return
        idsSeguidos.clear()
        mensagemAtual = null
    }

    val disparou: Boolean get() = idsSeguidos.size >= limite

    /** As leituras da sequência que disparou: voltam para a fila. */
    fun idsDaSequencia(): List<Long> = idsSeguidos.toList()

    val mensagem: String? get() = mensagemAtual

    companion object {
        const val LIMITE_PADRAO = 5
    }
}
