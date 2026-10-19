package com.example.rfid_server.dados

import org.json.JSONObject

/** Versão do app publicada no servidor (GET /api/android/atualizacao). */
data class VersaoPublicada(
    val versaoNome: String,
    val codigo: Int,
    val tamanho: Long,
    /** SHA-256 do APK (hex minúsculo): o app confere o arquivo baixado antes de instalar. */
    val sha256: String,
    val notas: String?
) {
    /** Mais nova que a instalada? (o Android só instala por cima uma versão com código maior) */
    fun maisNovaQue(codigoInstalado: Int): Boolean = codigo > codigoInstalado

    /** "1,5 MB" para a mensagem de atualização. */
    fun tamanhoLegivel(): String = "%.1f MB".format(java.util.Locale("pt", "BR"), tamanho / (1024.0 * 1024.0))

    companion object {
        /** Lança JSONException se a resposta não for a esperada. */
        fun deJson(texto: String): VersaoPublicada {
            val o = JSONObject(texto)
            val sha = o.getString("sha256").lowercase()
            require(sha.length == 64 && sha.all { it in "0123456789abcdef" }) { "SHA-256 inválido na resposta do servidor" }
            return VersaoPublicada(
                versaoNome = o.getString("versaoNome"),
                codigo = o.getInt("codigo"),
                tamanho = o.getLong("tamanho"),
                sha256 = sha,
                notas = if (o.isNull("notas")) null else o.optString("notas").ifBlank { null }
            )
        }
    }
}
