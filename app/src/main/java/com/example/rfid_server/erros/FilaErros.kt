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
    /** Relógio do coletor muito diferente do servidor (o servidor aceita qualquer tipo; antes ia como FALHA_GERAL e aparecia como travamento). */
    const val RELOGIO = "RELOGIO"
}

/**
 * Fila dos erros do app que ainda não chegaram ao servidor, guardada num arquivo JSON (e não no banco local:
 * precisa funcionar até dentro do tratamento de um travamento). Sem dependência do Android, para os testes.
 *
 * - O texto passa pela [MascaraSensivel] antes de ser guardado.
 * - Repetidos (mesmo tipo, mensagem e primeira linha do stack em até [JANELA_AGRUPAMENTO_MS], em qualquer ponto da
 *   fila) viram um registro só, com ocorrencias: um app em loop de falha não gera milhares de registros.
 * - No máximo [MAXIMO_GUARDADOS] registros e [MAXIMO_BYTES]: ver [limitar] (a primeira ocorrência de cada erro fica).
 */
class FilaErros(
    private val arquivo: File,
    private val versaoApp: String,
    private val codigoVersao: Int,
    private val relogio: () -> Long = System::currentTimeMillis,
    private val gerarId: () -> String = { UUID.randomUUID().toString() }
) {

    // Muda a cada gravação; com o momento do último envio sem sucesso, evita repetir o mesmo envio (só na memória)
    private var versao = 0L
    private var versaoTentada = -1L
    private var momentoTentativa: Long? = null

    @Synchronized
    fun registrar(tipo: String, mensagem: String, detalhe: String? = null, operador: String? = null) {
        val agora = relogio()
        val msg = MascaraSensivel.limpar(mensagem).trim().take(500).ifEmpty { "(sem mensagem)" }
        val det = detalhe?.let { MascaraSensivel.limpar(it).trim().take(8000) }?.ifEmpty { null }
        val chave = "$tipo|$msg|${det?.lineSequence()?.drop(1)?.firstOrNull()?.trim().orEmpty()}"

        val itens = ler()
        // Procura em toda a fila (o mais recente primeiro), não só no último: num loop de travamento com dois erros
        // alternados, cada um continua agrupado no seu registro
        val mesmo = (itens.length() - 1 downTo 0).map { itens.getJSONObject(it) }
            .firstOrNull { it.optString("chave") == chave && agora - it.optLong("ultimoMs") <= JANELA_AGRUPAMENTO_MS }

        if (mesmo != null) {
            mesmo.put("ocorrencias", mesmo.optInt("ocorrencias", 1) + 1)
            mesmo.put("ultimoMs", agora)
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

        gravar(limitar(itens))
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

    /**
     * true se a fila não mudou desde o último envio que não deu certo, feito há menos de [JANELA_REENVIO_MS]: tentar
     * de novo agora daria o mesmo resultado (ex.: o envio do início do SincronizacaoWorker e o do fim da passada).
     */
    @Synchronized
    fun envioRecenteSemNovidade(): Boolean {
        val momento = momentoTentativa ?: return false
        return versaoTentada == versao && relogio() - momento in 0 until JANELA_REENVIO_MS
    }

    /** Um envio não deu certo agora (sem rede, servidor antigo, erro do servidor): ver [envioRecenteSemNovidade]. */
    @Synchronized
    fun marcarEnvioSemSucesso() {
        versaoTentada = versao
        momentoTentativa = relogio()
    }

    /**
     * Mantém a fila dentro dos limites sem perder a causa original de um problema:
     *  1. acima de [MAXIMO_GUARDADOS] registros, saem primeiro as REPETIÇÕES (mesma chave de um registro mais antigo,
     *     fora da janela de agrupamento), das mais novas para as mais antigas; as ocorrências delas são somadas na
     *     primeira ocorrência, que fica. Só sem repetições saem os mais antigos;
     *  2. acima de [MAXIMO_BYTES], primeiro some o detalhe (stack) dos mais antigos; se ainda não bastar, saem
     *     registros na mesma ordem do item 1.
     */
    private fun limitar(itens: JSONArray): JSONArray {
        val lista = (0 until itens.length()).map { itens.getJSONObject(it) }.toMutableList()
        val tamanhos = lista.map { tamanho(it) }.toMutableList()

        fun total() = tamanhos.sum() + lista.size + 1

        fun descartarUm() {
            // A repetição mais nova (índice maior) de uma chave que aparece antes na fila
            val primeiraPorChave = mutableMapOf<String, Int>()
            var repetida = -1
            lista.forEachIndexed { i, item ->
                val chave = item.optString("chave")
                if (chave in primeiraPorChave) repetida = i else primeiraPorChave[chave] = i
            }
            if (repetida >= 0) {
                val item = lista[repetida]
                val primeira = primeiraPorChave.getValue(item.optString("chave"))
                val original = lista[primeira]
                original.put("ocorrencias", original.optInt("ocorrencias", 1) + item.optInt("ocorrencias", 1))
                original.put("ultimoMs", maxOf(original.optLong("ultimoMs"), item.optLong("ultimoMs")))
                tamanhos[primeira] = tamanho(original)
                lista.removeAt(repetida)
                tamanhos.removeAt(repetida)
            } else {
                lista.removeAt(0)
                tamanhos.removeAt(0)
            }
        }

        while (lista.size > MAXIMO_GUARDADOS) descartarUm()

        if (total() > MAXIMO_BYTES) {
            for (i in lista.indices) {
                if (total() <= MAXIMO_BYTES) break
                if (lista[i].isNull("detalhe")) continue
                lista[i].put("detalhe", JSONObject.NULL)
                tamanhos[i] = tamanho(lista[i])
            }
            while (lista.size > 1 && total() > MAXIMO_BYTES) descartarUm()
        }

        return JSONArray().apply { lista.forEach { put(it) } }
    }

    // Em bytes (UTF-8), como fica no arquivo
    private fun tamanho(item: JSONObject): Int = item.toString().toByteArray().size

    // Arquivo ilegível (ex.: gravação interrompida) não pode travar o app: recomeça vazio
    private fun ler(): JSONArray =
        try {
            if (arquivo.exists()) JSONArray(arquivo.readText()) else JSONArray()
        } catch (e: Exception) {
            JSONArray()
        }

    private fun gravar(itens: JSONArray) {
        versao++
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
        /** Tamanho máximo do arquivo (aprox.): acima disso some o stack dos mais antigos, depois registros. */
        const val MAXIMO_BYTES = 512 * 1024
        /** Dentro deste tempo, um envio sem sucesso não é repetido se a fila não mudou. */
        const val JANELA_REENVIO_MS = 60 * 1000L
    }
}
