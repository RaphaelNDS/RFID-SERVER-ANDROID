package com.example.rfid_server.view

data class TagView(
    val epc: String,
    val codigoInterno: String,
    val tipo: String?,
    val marca: String?,
    val modelo: String?,
    val patrimonio: String,
    val numeroSerie: String,
    /** Situação do bem no cadastro (BAIXADO, EXTRAVIADO...), quando o servidor manda; null se não manda. */
    val situacao: String? = null,
    /** Texto pronto da situação ("Baixado"). */
    val situacaoRotulo: String? = null
) {
    /** "Notebook · Dell · Latitude" (só o que houver; partes em branco ficam de fora). A mesma em todas as telas. */
    fun descricao(): String = listOfNotNull(tipo, marca, modelo).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" · ")

    /**
     * Texto do alerta para o cartão da leitura: só para bem baixado ou extraviado, que não deveria estar circulando
     * (as outras situações aparecem na ficha). Null quando não há o que avisar.
     */
    fun alertaSituacao(): String? = when (situacao) {
        "BAIXADO", "EXTRAVIADO", "ALUGADO", "VENDIDO" -> situacaoRotulo ?: situacao
        else -> null
    }
}
