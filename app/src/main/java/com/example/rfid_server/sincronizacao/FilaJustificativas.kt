package com.example.rfid_server.sincronizacao

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Justificativas dos não encontrados (motivo por etiqueta que faltou num inventário) esperando o envio ao servidor.
 * Num arquivo JSON, como a fila de erros (sem mudar o banco local): são poucas e só existem até o servidor confirmar.
 * Justificar de novo a mesma etiqueta do mesmo inventário troca o motivo (o servidor também trata assim).
 * Sem dependência do Android, para os testes. Uma instância por app (ver [SincronizadorJustificativas]).
 */
class FilaJustificativas(private val arquivo: File) {

    /** Motivos aceitos pelo servidor (código), na ordem em que o operador escolhe. */
    enum class Motivo { EMPRESTADO, MANUTENCAO, BAIXADO, NAO_ENCONTRADO, OUTRO }

    companion object {
        /** Limite do servidor para a observação. */
        const val MAXIMO_OBSERVACAO = 500
    }

    data class Item(
        val inventarioId: String,
        val epc: String,
        val motivo: Motivo,
        /** Referência da sessão de quem justificou (vai no X-Sessao-Operador). */
        val sessao: String?,
        val criadoEm: Long,
        /** Texto livre (até 500 caracteres no servidor), usado no motivo "Outro". */
        val observacao: String? = null
    )

    @Synchronized
    fun adicionar(inventarioId: String, epcs: Collection<String>, motivo: Motivo, sessao: String?, agora: Long, observacao: String? = null) {
        val itens = ler().filterNot { it.inventarioId == inventarioId && it.epc in epcs } +
            epcs.map { Item(inventarioId, it, motivo, sessao, agora, observacao?.trim()?.take(MAXIMO_OBSERVACAO)?.ifEmpty { null }) }
        gravar(itens)
    }

    @Synchronized
    fun pendentes(): List<Item> = ler()

    /** Motivos já escolhidos para as etiquetas do [inventarioId] (para o resumo e a planilha). */
    @Synchronized
    fun motivos(inventarioId: String): Map<String, Motivo> =
        ler().filter { it.inventarioId == inventarioId }.associate { it.epc to it.motivo }

    @Synchronized
    fun remover(inventarioId: String, epcs: Collection<String>) {
        gravar(ler().filterNot { it.inventarioId == inventarioId && it.epc in epcs })
    }

    /** Tira as criadas antes de [limite] (servidor que nunca aceitou); devolve quantas saíram. */
    @Synchronized
    fun descartarAntigas(limite: Long): Int {
        val itens = ler()
        val restantes = itens.filter { it.criadoEm >= limite }
        if (restantes.size != itens.size) gravar(restantes)
        return itens.size - restantes.size
    }

    private fun ler(): List<Item> =
        try {
            if (!arquivo.exists()) emptyList()
            else JSONArray(arquivo.readText()).let { lista ->
                (0 until lista.length()).mapNotNull { i ->
                    val o = lista.getJSONObject(i)
                    val motivo = Motivo.entries.firstOrNull { it.name == o.optString("motivo") } ?: return@mapNotNull null
                    Item(
                        o.getString("inventarioId"), o.getString("epc"), motivo,
                        o.optString("sessao").ifEmpty { null }, o.optLong("criadoEm"),
                        o.optString("observacao").ifEmpty { null }
                    )
                }
            }
        } catch (e: Exception) {
            // Arquivo ilegível (gravação interrompida): recomeça vazio em vez de travar a sincronização
            emptyList()
        }

    private fun gravar(itens: List<Item>) {
        arquivo.parentFile?.mkdirs()
        val texto = JSONArray().apply {
            itens.forEach {
                put(JSONObject().put("inventarioId", it.inventarioId).put("epc", it.epc).put("motivo", it.motivo.name)
                    .put("sessao", it.sessao ?: "").put("criadoEm", it.criadoEm).put("observacao", it.observacao ?: ""))
            }
        }.toString()
        val temporario = File(arquivo.parentFile, arquivo.name + ".tmp")
        temporario.writeText(texto)
        if (!temporario.renameTo(arquivo)) {
            arquivo.writeText(texto)
            temporario.delete()
        }
    }
}
