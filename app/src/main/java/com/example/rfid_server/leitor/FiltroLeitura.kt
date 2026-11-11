package com.example.rfid_server.leitor

/**
 * Filtros das leituras do gatilho (Leitura e Inventário), escolhidos nas Configurações:
 *  - alcance: ignora etiquetas com sinal (RSSI) abaixo do mínimo, para não ler a sala vizinha através da parede;
 *  - só etiquetas da empresa: ignora EPCs que não começam com [PREFIXO_EMPRESA] (crachás, embalagens, produtos).
 *
 * O Localizar não passa por aqui: ele precisa justamente dos sinais fracos para guiar até a etiqueta.
 * Sem dependência do Android, para os testes.
 */
object FiltroLeitura {

    /**
     * Início dos EPCs gravados pelo coletor ("BK" em ASCII: 424B50 = "BKP" com o patrimônio, 424B48 = "BKH" com o
     * resumo do patrimônio longo; ver CodigoEtiqueta).
     */
    const val PREFIXO_EMPRESA = "424B"

    /** Opções de alcance das Configurações: RSSI mínimo em dBm (null = sem limite). */
    val ALCANCES: List<Int?> = listOf(null, -65, -55)

    /**
     * A leitura vale? [rssi] 0 = o leitor não informou o sinal: não descarta (melhor ler a mais do que perder).
     * [rssiMinimo] null = sem limite de alcance.
     */
    fun aceita(epc: String, rssi: Int, rssiMinimo: Int?, soEmpresa: Boolean): Boolean {
        if (soEmpresa && !epc.uppercase().startsWith(PREFIXO_EMPRESA)) return false
        if (rssiMinimo != null && rssi != 0 && rssi < rssiMinimo) return false
        return true
    }
}
