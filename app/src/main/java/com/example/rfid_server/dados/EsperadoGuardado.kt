package com.example.rfid_server.dados

import java.io.File
import java.io.FileOutputStream
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

    /**
     * Guarda a lista do [local] e o [etag] dela (servidor 1.4.4 em diante; null = servidor sem ETag). O ETag antigo sai
     * antes de trocar a lista e o novo só entra depois: se o app fechar no meio, fica a lista sem ETag (a próxima
     * consulta baixa inteira), nunca um ETag apontando para a lista errada.
     */
    fun salvar(local: String, json: String, etag: String? = null) {
        pasta.mkdirs()
        val chave = chave(local)
        arquivoEtag(chave).delete()
        salvarLista(local, chave, json)
        if (etag != null) salvarEtag(local, etag)
    }

    /** ETag da lista guardada do [local], para o If-None-Match; null se não há (lista sem ETag ou nunca baixada). */
    fun lerEtag(local: String): String? {
        val chave = chave(local)
        if (!arquivo(chave).isFile) return null
        return arquivoEtag(chave).takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }
    }

    /** O servidor confirmou que a lista guardada é a atual (mesmo conteúdo, ETag novo): troca só o ETag. */
    fun salvarEtag(local: String, etag: String) {
        val chave = chave(local)
        if (!arquivo(chave).isFile) return
        val destino = arquivoEtag(chave)
        val temporario = File(pasta, destino.name + ".tmp")
        gravarNoDisco(temporario, etag)
        if (!temporario.renameTo(destino)) {
            destino.delete()
            if (!temporario.renameTo(destino)) temporario.delete()
        }
    }

    private fun salvarLista(local: String, chave: String, json: String) {
        val destino = arquivo(chave)
        // Grava num temporário e troca: o app fechar no meio não deixa uma lista pela metade
        val temporario = File(pasta, destino.name + ".tmp")
        gravarNoDisco(temporario, json)
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
            gravarNoDisco(destino, json)
            trazidas++
        }
        return trazidas
    }

    // Com sync antes de trocar o nome: tirar a bateria logo depois (rotina no MC3300x) podia deixar o arquivo novo vazio
    private fun gravarNoDisco(arquivo: File, texto: String) {
        FileOutputStream(arquivo).use { saida ->
            saida.write(texto.toByteArray(Charsets.UTF_8))
            saida.fd.sync()
        }
    }

    private fun arquivo(chave: String) = File(pasta, sha256(chave) + ".json")

    private fun arquivoEtag(chave: String) = File(pasta, sha256(chave) + ".etag")

    companion object {
        /** Mesma regra do servidor: ignora maiúsculas e espaços extras. */
        fun chave(local: String): String = local.trim().replace(Regex("\\s+"), " ").lowercase()

        private fun sha256(texto: String): String =
            MessageDigest.getInstance("SHA-256").digest(texto.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
