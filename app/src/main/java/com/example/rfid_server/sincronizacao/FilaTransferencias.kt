package com.example.rfid_server.sincronizacao

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Transferências de bem para o local do inventário (servidor 1.7.0: POST /api/android/tags/{epc}/transferencia),
 * pedidas pelo operador numa etiqueta lida fora da lista. Num arquivo JSON, como as justificativas: são poucas.
 * O pedido fica PENDENTE até o servidor responder; depois fica com o resultado (FEITA, com o nº da guia, ou RECUSADA,
 * com o motivo) para a aba Lidas mostrar, até sair pelo prazo. Reenviar ao mesmo local é seguro (o servidor responde
 * "jaEstava" sem guia nova), então um pedido cuja resposta se perdeu vai de novo sem problema.
 * Sem dependência do Android, para os testes. Uma instância por app (ver [SincronizadorTransferencias]).
 */
class FilaTransferencias(private val arquivo: File) {

    enum class Estado { PENDENTE, FEITA, RECUSADA }

    companion object {
        /** Limite do servidor para a observação (o mesmo das justificativas). */
        const val MAXIMO_OBSERVACAO = 500
    }

    data class Item(
        val inventarioId: String,
        val epc: String,
        /** Nome do local do inventário, como o servidor conhece. */
        val local: String,
        /** Referência da sessão de quem pediu (vai no X-Sessao-Operador). */
        val sessao: String?,
        val criadoEm: Long,
        val observacao: String? = null,
        val estado: Estado = Estado.PENDENTE,
        /** Nº da guia de transferência gerada no servidor (null = o bem já estava no local). */
        val guia: Long? = null,
        val jaEstava: Boolean = false,
        /** Motivo da recusa, como o servidor mandou. */
        val mensagem: String? = null
    )

    /** Pede a transferência; pedir de novo a mesma etiqueta no mesmo inventário substitui o pedido anterior. */
    @Synchronized
    fun adicionar(inventarioId: String, epc: String, local: String, sessao: String?, agora: Long, observacao: String? = null) {
        val item = Item(inventarioId, epc, local, sessao, agora, observacao?.trim()?.take(MAXIMO_OBSERVACAO)?.ifEmpty { null })
        gravar(ler().filterNot { it.inventarioId == inventarioId && it.epc == epc } + item)
    }

    @Synchronized
    fun pendentes(): List<Item> = ler().filter { it.estado == Estado.PENDENTE }

    /** Pedidos do [inventarioId] (pendentes e respondidos), por EPC, para a aba Lidas. */
    @Synchronized
    fun doInventario(inventarioId: String): Map<String, Item> =
        ler().filter { it.inventarioId == inventarioId }.associateBy { it.epc }

    @Synchronized
    fun concluir(item: Item, guia: Long?, jaEstava: Boolean) =
        atualizar(item) { it.copy(estado = Estado.FEITA, guia = guia, jaEstava = jaEstava, mensagem = null) }

    @Synchronized
    fun recusar(item: Item, mensagem: String) = atualizar(item) { it.copy(estado = Estado.RECUSADA, mensagem = mensagem) }

    /** Tira os criados antes de [limite]; devolve quantos ainda estavam PENDENTES (o servidor nunca respondeu). */
    @Synchronized
    fun descartarAntigas(limite: Long): Int {
        val itens = ler()
        val (antigos, restantes) = itens.partition { it.criadoEm < limite }
        if (antigos.isNotEmpty()) gravar(restantes)
        return antigos.count { it.estado == Estado.PENDENTE }
    }

    /** Só muda o pedido se ele ainda for o mesmo (o operador pode ter pedido de novo enquanto o envio rodava). */
    private fun atualizar(item: Item, mudar: (Item) -> Item) {
        gravar(ler().map { if (it.inventarioId == item.inventarioId && it.epc == item.epc && it.criadoEm == item.criadoEm) mudar(it) else it })
    }

    private fun ler(): List<Item> =
        try {
            if (!arquivo.exists()) emptyList()
            else JSONArray(arquivo.readText()).let { lista ->
                (0 until lista.length()).mapNotNull { i ->
                    val o = lista.getJSONObject(i)
                    val estado = Estado.entries.firstOrNull { it.name == o.optString("estado") } ?: return@mapNotNull null
                    Item(
                        inventarioId = o.getString("inventarioId"),
                        epc = o.getString("epc"),
                        local = o.getString("local"),
                        sessao = o.optString("sessao").ifEmpty { null },
                        criadoEm = o.optLong("criadoEm"),
                        observacao = o.optString("observacao").ifEmpty { null },
                        estado = estado,
                        guia = if (o.has("guia")) o.getLong("guia") else null,
                        jaEstava = o.optBoolean("jaEstava"),
                        mensagem = o.optString("mensagem").ifEmpty { null }
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
                put(JSONObject().put("inventarioId", it.inventarioId).put("epc", it.epc).put("local", it.local)
                    .put("sessao", it.sessao ?: "").put("criadoEm", it.criadoEm).put("observacao", it.observacao ?: "")
                    .put("estado", it.estado.name).put("jaEstava", it.jaEstava).put("mensagem", it.mensagem ?: "")
                    .apply { it.guia?.let { guia -> put("guia", guia) } })
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
