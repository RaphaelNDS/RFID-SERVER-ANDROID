package com.example.rfid_server

/**
 * Uma célula das planilhas (CSV com ";") que o coletor compartilha: a do inventário e a da tela de Leituras.
 * Sem dependência do Android, para os testes.
 */
object CelulaCsv {

    /**
     * Aspas quando o texto tem ";", aspas ou quebra de linha (padrão do CSV). Texto que começa com "=", "+", "-", "@"
     * ou tabulação ganha um apóstrofo na frente: o Excel trataria como fórmula (um patrimônio ou modelo cadastrado
     * como "=..." viraria conta, ou até um link, na planilha de quem abre). O apóstrofo não aparece na célula.
     */
    fun formatar(texto: String): String {
        val seguro = if (texto.firstOrNull()?.let { it in INICIO_DE_FORMULA } == true) "'$texto" else texto
        return if (seguro.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) "\"" + seguro.replace("\"", "\"\"") + "\"" else seguro
    }

    private val INICIO_DE_FORMULA = setOf('=', '+', '-', '@', '\t', '\r')
}
