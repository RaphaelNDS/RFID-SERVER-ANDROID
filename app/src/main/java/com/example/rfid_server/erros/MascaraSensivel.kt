package com.example.rfid_server.erros

/**
 * Tira dados sensíveis do texto de um erro antes de guardar/enviar ao servidor: mensagens de exceção e stack
 * traces podem carregar o token da sessão do operador, a chave do coletor ou uma senha digitada.
 * A mesma regra existe no servidor (MascaraSensivel.kt), como segunda barreira.
 */
object MascaraSensivel {

    private val regras = listOf(
        // Headers da API do coletor: "X-Sessao-Operador: abc", "X-Chave-Dispositivo=abc"
        Regex("(?i)(X-(?:Sessao-Operador|Chave-Dispositivo)\\s*[:=]\\s*)\\S+") to "$1***",
        // "senha=...", "password: ...", "token=...", "chave: ...", "secret=..."
        Regex("(?i)\\b(senha|password|passwd|token|chave|key|secret)(\\s*[:=]\\s*)[^\\s,;&\"']+") to "$1$2***",
        Regex("(?i)\\b(Bearer)\\s+\\S+") to "$1 ***",
        // Sequências longas sem espaço (tokens, chaves): 32+ letras/números/-/_ seguidos
        Regex("[A-Za-z0-9_-]{32,}") to "***"
    )

    fun limpar(texto: String): String = regras.fold(texto) { atual, (regra, troca) -> regra.replace(atual, troca) }
}
