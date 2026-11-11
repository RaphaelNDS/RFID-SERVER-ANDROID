package com.example.rfid_server.configuracoes

/**
 * QR de configuração do coletor, mostrado na tela Coletores do servidor (para ADMIN) e lido pelo leitor de código de
 * barras nas Configurações. Texto: RFIDCFG1|<host:porta>|<chave do coletor>|<SHA-256 do certificado, com ":">.
 * Preenche o servidor e a chave e confirma o certificado de uma vez, sem digitar (o PIN continua sendo pedido ao salvar).
 * Sem dependência do Android, para os testes.
 */
data class ConfigQr(val servidor: String, val chave: String, val impressaoDigital: String?) {

    companion object {
        private const val PREFIXO = "RFIDCFG1|"
        private val IMPRESSAO = Regex("^([0-9A-F]{2}:){31}[0-9A-F]{2}$")

        /** null se o texto não é um QR de configuração (ex.: código de barras de patrimônio) ou está incompleto. */
        fun ler(texto: String): ConfigQr? {
            if (!texto.startsWith(PREFIXO)) return null
            val partes = texto.removePrefix(PREFIXO).split('|')
            if (partes.size < 2) return null
            val servidor = partes[0].trim()
            val chave = partes[1].trim()
            if (servidor.isEmpty() || chave.isEmpty()) return null
            if (!AppConfig.chaveValida(chave)) return null
            // Impressão digital opcional; malformada é ignorada (o certificado é confirmado do jeito de sempre)
            val impressao = partes.getOrNull(2)?.trim()?.uppercase()?.takeIf { IMPRESSAO.matches(it) }
            return ConfigQr(servidor, chave, impressao)
        }
    }
}
