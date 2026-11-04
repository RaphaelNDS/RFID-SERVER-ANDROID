package com.example.rfid_server.erros

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Tipos de erro enviados ao servidor (tela Coletores → Erros). */
object TipoErro {
    /** O app travou (exceção não tratada). */
    const val FALHA_GERAL = "FALHA_GERAL"
    /** O servidor recusou de vez uma leitura, inventário ou cadastro. */
    const val ENVIO_RECUSADO = "ENVIO_RECUSADO"
    /** Erro do servidor (5xx, resposta inválida) que interrompeu a sincronização. */
    const val ERRO_SERVIDOR = "ERRO_SERVIDOR"
    /** O leitor RFID não conectou. */
    const val LEITOR_RFID = "LEITOR_RFID"
    /** A atualização do app não foi baixada ou não conferiu. */
    const val ATUALIZACAO = "ATUALIZACAO"
}

/**
 * Fila dos erros do app que ainda não chegaram ao servidor, guardada num arquivo JSON (e não no banco local:
 * precisa funcionar até dentro do tratamento de um travamento). Sem dependência do Android, para os testes.
 *
 * - O texto passa pela [MascaraSensivel] antes de ser guardado.
 * - Repetidos em sequência (mesmo tipo, mensagem e primeira linha do stack em até [JANELA_AGRUPAMENTO_MS])
 *   viram um registro só, com [ocorrencias]: um app em loop de falha não gera milhares de registros.
 * - No máximo [MAXIMO_GUARDADOS]: acima disso, os mais antigos saem.
 */
class FilaErros(
    private val arquivo: File,
    private val versaoApp: String,
    private val codigoVersao: Int,
    private val relogio: () -> Long = System::currentTimeMillis,
    private val gerarId: () -> String = { UUID.randomUUID().toString() }
) {

    @Synchronized
    fun registrar(tipo: String, mensagem: String, detalhe: String? = null, operador: String? = null) {
        val agora = relogio()
        val msg = MascaraSensivel.limpar(mensagem).trim().take(500).ifEmpty { "(sem mensagem)" }
        val det = detalhe?.let { MascaraSensivel.limpar(it).trim().take(8000) }?.ifEmpty { null }
        val chave = "$tipo|$msg|${det?.lineSequence()?.drop(1)?.firstOrNull()?.trim().orEmpty()}"

        val itens = ler()
        val ultimo = (0 until itens.length()).map { itens.getJSONObject(it) }.lastOrNull()

        if (ultimo != null && ultimo.optString("chave") == chave && agora - ultimo.optLong("ultimoMs") <= JANELA_AGRUPAMENTO_MS) {
            ultimo.put("ocorrencias", ultimo.optInt("ocorrencias", 1) + 1)
            ultimo.put("ultimoMs", agora)
        } else {
            itens.put(
                JSONObject()
                    .put("id", gerarId())
                    .put("momento", iso(agora))
                    .put("tipo", tipo)
                    .put("mensagem", msg)
                    .put("detalhe", det ?: JSONObject.NULL)
                    .put("ocorrencias", 1)
                    .put("versaoApp", versaoApp)
                    .put("codigoVersao", codigoVersao)
                    .put("operador", operador ?: JSONObject.NULL)
                    .put("chave", chave)
                    .put("ultimoMs", agora)
            )
        }

        gravar(manterUltimos(itens))
    }

    /** Até [maximo] erros para enviar (os mais antigos primeiro), já no formato do servidor. */
    @Synchronized
    fun pendentes(maximo: Int = MAXIMO_POR_ENVIO): JSONArray {
        val itens = ler()
        val lote = JSONArray()
        for (i in 0 until minOf(maximo, itens.length())) {
            val copia = JSONObject(itens.getJSONObject(i).toString())
            copia.remove("chave")
            copia.remove("ultimoMs")
            lote.put(copia)
        }
        return lote
    }

    /** Tira da fila estes ids, inteiros (para o resultado de um envio, use [confirmarEnvio]). */
    @Synchronized
    fun remover(ids: Collection<String>) {
        val itens = ler()
        val restantes = JSONArray()
        for (i in 0 until itens.length()) {
            val item = itens.getJSONObject(i)
            if (item.optString("id") !in ids) restantes.put(item)
        }
        gravar(restantes)
    }

    /**
     * Tira da fila o [lote] que saiu de [pendentes] (enviado ao servidor, ou descartado). Só o que foi no lote:
     * se o mesmo erro se repetiu durante o envio, o registro guardado tem mais ocorrências do que as enviadas, e
     * as novas ficam na fila (com outro id e o momento da última, para o servidor não tratar como o mesmo envio).
     * Devolve quantos registros saíram da fila.
     */
    @Synchronized
    fun confirmarEnvio(lote: JSONArray): Int {
        val enviadas = (0 until lote.length()).map { lote.getJSONObject(it) }
            .associate { it.optString("id") to it.optInt("ocorrencias", 1) }

        val itens = ler()
        val restantes = JSONArray()
        var removidos = 0
        for (i in 0 until itens.length()) {
            val item = itens.getJSONObject(i)
            val jaEnviadas = enviadas[item.optString("id")]
            if (jaEnviadas == null) {
                restantes.put(item)
                continue
            }

            val novas = item.optInt("ocorrencias", 1) - jaEnviadas
            if (novas <= 0) {
                removidos++
                continue
            }
            restantes.put(
                item.put("id", gerarId())
                    .put("ocorrencias", novas)
                    .put("momento", iso(item.optLong("ultimoMs", relogio())))
            )
        }
        gravar(restantes)
        return removidos
    }

    @Synchronized
    fun quantidade(): Int = ler().length()

    private fun manterUltimos(itens: JSONArray): JSONArray {
        if (itens.length() <= MAXIMO_GUARDADOS) return itens
        val ultimos = JSONArray()
        for (i in itens.length() - MAXIMO_GUARDADOS until itens.length()) ultimos.put(itens.get(i))
        return ultimos
    }

    // Arquivo ilegível (ex.: gravação interrompida) não pode travar o app: recomeça vazio
    private fun ler(): JSONArray =
        try {
            if (arquivo.exists()) JSONArray(arquivo.readText()) else JSONArray()
        } catch (e: Exception) {
            JSONArray()
        }

    private fun gravar(itens: JSONArray) {
        arquivo.parentFile?.mkdirs()
        val temporario = File(arquivo.parentFile, arquivo.name + ".tmp")
        temporario.writeText(itens.toString())
        if (!temporario.renameTo(arquivo)) {
            arquivo.writeText(itens.toString())
            temporario.delete()
        }
    }

    private fun iso(epochMs: Long): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault()))

    companion object {
        const val MAXIMO_POR_ENVIO = 50
        const val MAXIMO_GUARDADOS = 200
        const val JANELA_AGRUPAMENTO_MS = 10 * 60 * 1000L
    }
}
