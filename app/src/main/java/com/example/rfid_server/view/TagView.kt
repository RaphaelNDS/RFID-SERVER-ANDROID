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
    /**
     * Texto do alerta para o cartão da leitura: só para bem baixado ou extraviado, que não deveria estar circulando
     * (as outras situações aparecem na ficha). Null quando não há o que avisar.
     */
    fun alertaSituacao(): String? = when (situacao) {
        "BAIXADO", "EXTRAVIADO" -> situacaoRotulo ?: situacao
        else -> null
    }
}
