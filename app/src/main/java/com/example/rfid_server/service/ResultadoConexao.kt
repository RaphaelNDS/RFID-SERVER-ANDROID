package com.example.rfid_server.service

enum class StatusConexao(val descricao: String) {
    CONECTADO("conectado"),
    CHAVE_INVALIDA("chave do coletor inválida ou não configurada"),
    ENDERECO_INVALIDO("endereço do servidor inválido"),
    SEM_CONEXAO("sem conexão (o coletor está na rede?)"),
    /** A máquina do servidor recusou a conexão na hora: a porta está fechada (servidor parado ou reiniciando). */
    SERVIDOR_FORA_DO_AR("porta fechada neste endereço: confira o IP e a porta, ou se o servidor está rodando"),
    RESPOSTA_INESPERADA("resposta inesperada (servidor desatualizado?)"),
    /** HTTP 429: o servidor bloqueou este coletor por um tempo depois de muitas tentativas com chave inválida. */
    MUITAS_TENTATIVAS("muitas tentativas com chave inválida: aguarde alguns minutos e confira a chave"),
    CERTIFICADO_NAO_CONFIRMADO("certificado do servidor ainda não confirmado"),
    CERTIFICADO_DIFERENTE("certificado do servidor DIFERENTE do confirmado")
}

/** Resultado do teste de conexão; nos casos de certificado, traz a impressão digital que o servidor apresentou. */
data class ResultadoConexao(val status: StatusConexao, val impressaoDigital: String? = null)
