package com.example.rfid_server.erros

/**
 * Tira dados sensíveis do texto de um erro antes de guardar/enviar ao servidor: mensagens de exceção e stack
 * traces podem carregar o token da sessão do operador, a chave do coletor ou uma senha digitada.
 * A mesma regra existe no servidor (MascaraSensivel.kt), como segunda barreira.
 *
 * Cobre os formatos comuns: texto/query string (senha=abc), header (senha: abc) e JSON ("senha":"abc"),
 * com aspas opcionais em volta da chave e do valor.
 */
object MascaraSensivel {

    // Valor entre aspas (pode ter espaço dentro) ou sem aspas (até espaço, vírgula, ;, &, aspa ou fim do JSON)
    private const val VALOR = "(?:\"[^\"]*\"|'[^']*'|[^\\s,;&\"'}\\]]+)"

    /** Troca o valor por ***, mantendo as aspas que estavam em volta dele. */
    private fun mascararValor(m: MatchResult): String {
        val valor = m.groupValues[3]
        val aspa = valor.firstOrNull()?.takeIf { (it == '"' || it == '\'') && valor.length >= 2 && valor.last() == it }
        val mascara = if (aspa != null) "$aspa***$aspa" else "***"
        return m.groupValues[1] + m.groupValues[2] + mascara
    }

    private val regras: List<Pair<Regex, (MatchResult) -> CharSequence>> = listOf(
        // Headers da API do coletor: "X-Sessao-Operador: abc", "X-Chave-Dispositivo=abc", "X-Sessao-Operador":"abc"
        Regex("(?i)(X-(?:Sessao-Operador|Chave-Dispositivo))([\"']?\\s*[:=]\\s*)($VALOR)") to ::mascararValor,
        // "senha=...", "password: ...", "token=...", "chave: ...", "secret=...", "senha":"..." (JSON)
        Regex("(?i)\\b(senha|password|passwd|token|chave|key|secret)([\"']?\\s*[:=]\\s*)($VALOR)") to ::mascararValor,
        Regex("(?i)\\b(Bearer)\\s+\\S+") to { m -> "${m.groupValues[1]} ***" },
        // Sequências longas sem espaço (tokens, chaves): 32+ letras/números/-/_ seguidos
        Regex("[A-Za-z0-9_-]{32,}") to { _ -> "***" }
    )

    fun limpar(texto: String): String = regras.fold(texto) { atual, (regra, troca) -> regra.replace(atual, troca) }
}
