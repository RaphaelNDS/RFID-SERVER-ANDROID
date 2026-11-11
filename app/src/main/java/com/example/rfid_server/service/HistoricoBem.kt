package com.example.rfid_server.service

/** Histórico do bem na ficha (campos opcionais do servidor; datas em ISO-8601). */
data class HistoricoBem(
    val ultimoLocal: String?,
    val ultimoInventarioEm: String?,
    val ultimaLeituraEm: String?,
    val ultimaLeituraPor: String?,
    // Servidor 1.6.0 (gestão de patrimônio): situação do bem no cadastro (código e rótulo pronto para mostrar),
    // unidade, local e responsável atuais. Servidor mais antigo não manda: ficam null
    val situacao: String? = null,
    val situacaoRotulo: String? = null,
    val unidade: String? = null,
    val local: String? = null,
    val responsavel: String? = null,
    // Servidor 1.12.0 (locação): com o bem ALUGADO ou EM_DEVOLUCAO, com quem está, o pedido, até quando e para onde foi
    val pedido: String? = null,
    val cliente: String? = null,
    val previsaoDevolucao: String? = null,
    val localEntrega: String? = null
) {
    val vazio: Boolean
        get() = listOf(ultimoLocal, ultimoInventarioEm, ultimaLeituraEm, situacao, situacaoRotulo, unidade, local, responsavel, pedido, cliente, localEntrega)
            .all { it == null }
}
