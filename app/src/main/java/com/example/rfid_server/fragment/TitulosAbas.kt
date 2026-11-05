package com.example.rfid_server.fragment

/**
 * Títulos das abas da tela principal com a quantidade da lista: "Leitura (12)" e "Não cadastradas (3)".
 * Substituem os títulos de seção que ficavam acima de cada lista (lógica pura, testável sem Android).
 */
object TitulosAbas {

    /** Aba "Leitura" (cadastradas e as que aguardam o servidor) e aba "Não cadastradas". */
    const val ABA_LEITURA = 0
    const val ABA_NAO_CADASTRADAS = 1

    /** [quantidade] null: a lista ainda não carregou (mostra só o rótulo, sem "(0)" enganoso). */
    fun titulo(rotulo: String, quantidade: Int?): String =
        if (quantidade == null) rotulo else "$rotulo ($quantidade)"
}
