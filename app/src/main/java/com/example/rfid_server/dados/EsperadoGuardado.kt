package com.example.rfid_server.dados

import java.io.File
import java.security.MessageDigest

/**
 * Lista do esperado de cada local guardada no coletor, um arquivo por local (pasta files/esperado).
 *
 * Antes ficava no SharedPreferences: com milhares de etiquetas por local, o XML de configurações inteiro
 * (servidor, chave, sessão...) era lido e regravado a cada mudança de qualquer configuração.
 *
 * O nome do arquivo é o SHA-256 do local normalizado (ignora maiúsculas e espaços extras, como o servidor):
 * o nome do local pode ter "/", acentos ou ser longo demais para nome de arquivo.
 */
class EsperadoGuardado(private val pasta: File) {

    fun salvar(local: String, json: String) {
        pasta.mkdirs()
        val destino = arquivo(chave(local))
        // Grava num temporário e troca: o app fechar no meio não deixa uma lista pela metade
        val temporario = File(pasta, destino.name + ".tmp")
        temporario.writeText(json)
        if (!temporario.renameTo(destino)) {
            destino.delete()
            if (!temporario.renameTo(destino)) {
                temporario.delete()
                throw IllegalStateException("Não foi possível gravar a lista do esperado de '$local' em $destino")
            }
        }
    }

    /** null se o esperado deste local nunca foi baixado neste coletor. */
    fun ler(local: String): String? = arquivo(chave(local)).takeIf { it.isFile }?.readText()

    /**
     * Traz as listas que estavam no SharedPreferences (chave já normalizada → JSON). Não sobrescreve uma lista
     * que já esteja em arquivo (seria mais nova). Devolve quantas foram trazidas.
     */
    fun importar(antigas: Map<String, String>): Int {
        if (antigas.isEmpty()) return 0
        pasta.mkdirs()
        var trazidas = 0
        for ((chave, json) in antigas) {
            val destino = arquivo(chave)
            if (destino.isFile) continue
            destino.writeText(json)
            trazidas++
        }
        return trazidas
    }

    private fun arquivo(chave: String) = File(pasta, sha256(chave) + ".json")

    companion object {
        /** Mesma regra do servidor: ignora maiúsculas e espaços extras. */
        fun chave(local: String): String = local.trim().replace(Regex("\\s+"), " ").lowercase()

        private fun sha256(texto: String): String =
            MessageDigest.getInstance("SHA-256").digest(texto.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
