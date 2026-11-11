package com.example.rfid_server.leituras

/**
 * Decide quando tocar o alerta de "não deveria estar aqui" na tela de Leituras: a situação de cada etiqueta chega
 * depois da leitura (a sincronização classifica), então o som toca quando aparece uma etiqueta com alerta que não
 * estava antes. As que já estavam na tela quando ela abriu não tocam (o operador já viu; voltar à tela não é novidade).
 */
class NovosAlertas {
    private var conhecidos: Set<String>? = null

    /** [atuais]: etiquetas da leitura com alerta agora. Devolve true se apareceu alguma nova (tocar). */
    fun mudou(atuais: Set<String>): Boolean {
        val antes = conhecidos
        conhecidos = atuais
        if (antes == null) return false
        return atuais.any { it !in antes }
    }
}
