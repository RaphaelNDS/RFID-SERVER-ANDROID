package com.example.rfid_server.dados

import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** Uma etiqueta que deveria estar no local (dados atuais do cadastro, para localizar pelo patrimônio). */
data class ItemEsperado(
    val epc: String,
    val patrimonio: String?,
    val codigoInterno: String?,
    val tipo: String?,
    val marca: String?,
    val modelo: String?,
    /** Situação no cadastro (servidor que manda: EMPRESTADO, EM_MANUTENCAO...); null se o servidor não informa. */
    val situacao: String? = null,
    /** Texto pronto da situação ("Emprestado"). */
    val situacaoRotulo: String? = null
) {
    /** "Notebook · Dell · Latitude" (só o que houver). */
    fun descricao(): String = listOfNotNull(tipo, marca, modelo).joinToString(" · ")
}

/**
 * O que é esperado num local (GET /api/android/inventarios/esperado): o que foi encontrado no último
 * inventário finalizado dele e (servidor 1.6.0) os bens cadastrados nele. [temReferencia] = false: local sem
 * inventário anterior e sem bens cadastrados, sem lista.
 * Guardado no coletor como JSON (AppConfig), para funcionar sem rede depois de baixado uma vez.
 */
data class EsperadoLocal(
    val local: String,
    val temReferencia: Boolean,
    /** ISO-8601 do servidor (ex.: 2026-10-10T10:30:00-03:00). */
    val finalizadoEm: String? = null,
    val operador: String? = null,
    val itens: List<ItemEsperado> = emptyList()
) {
    /** "01/11/2026 (Ana)": a referência sem a palavra "inventário", para as linhas curtas da tela. */
    fun descricaoCurta(): String =
        if (semInventarioAnterior()) SO_CADASTRO
        else descricaoReferencia().removePrefix("inventário").removePrefix(" de ").trim().ifEmpty { "inventário anterior" }

    /**
     * "inventário de 10/10/2026 (Ana)", para a tela dizer com o que está comparando. Sem data e sem operador, o
     * servidor 1.6.0 montou a lista só com os bens cadastrados no local, que ainda não teve inventário: diz isso, e não
     * "inventário" (parecia que havia um inventário anterior).
     */
    fun descricaoReferencia(): String = if (semInventarioAnterior()) SO_CADASTRO else buildString {
        append("inventário")
        dataReferencia()?.let { append(" de $it") }
        // Só o primeiro nome: com o nome completo a linha da referência cortava na tela de 320dp
        operador?.trim()?.substringBefore(" ")?.takeIf { it.isNotEmpty() }?.let { append(" ($it)") }
    }

    private fun semInventarioAnterior() = finalizadoEm.isNullOrBlank() && operador.isNullOrBlank()

    private fun dataReferencia(): String? =
        try {
            finalizadoEm?.let { OffsetDateTime.parse(it).format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) }
        } catch (e: Exception) {
            null
        }

    fun paraJson(): String = JSONObject()
        .put("local", local)
        .put("temReferencia", temReferencia)
        .put("finalizadoEm", finalizadoEm ?: JSONObject.NULL)
        .put("operador", operador ?: JSONObject.NULL)
        .put("itens", JSONArray().apply {
            itens.forEach {
                put(
                    JSONObject().put("tag", it.epc).put("patrimonio", it.patrimonio ?: JSONObject.NULL)
                        .put("codigoInterno", it.codigoInterno ?: JSONObject.NULL).put("tipo", it.tipo ?: JSONObject.NULL)
                        .put("marca", it.marca ?: JSONObject.NULL).put("modelo", it.modelo ?: JSONObject.NULL)
                        .put("situacao", it.situacao ?: JSONObject.NULL).put("situacaoRotulo", it.situacaoRotulo ?: JSONObject.NULL)
                )
            }
        })
        .toString()

    companion object {
        /** Referência de um local sem inventário anterior, com a lista montada pelo cadastro dos bens. */
        const val SO_CADASTRO = "bens cadastrados no local"

        /** Lê a resposta do servidor (ou o JSON guardado no coletor). Lança JSONException se não for o esperado. */
        fun deJson(texto: String): EsperadoLocal {
            val o = JSONObject(texto)
            val itens = o.optJSONArray("itens") ?: JSONArray()

            return EsperadoLocal(
                local = o.getString("local"),
                temReferencia = o.getBoolean("temReferencia"),
                finalizadoEm = o.textoOuNulo("finalizadoEm"),
                operador = o.textoOuNulo("operador"),
                itens = (0 until itens.length()).map { i ->
                    val item = itens.getJSONObject(i)
                    ItemEsperado(
                        epc = item.getString("tag"),
                        patrimonio = item.textoOuNulo("patrimonio"),
                        codigoInterno = item.textoOuNulo("codigoInterno"),
                        tipo = item.textoOuNulo("tipo"),
                        marca = item.textoOuNulo("marca"),
                        modelo = item.textoOuNulo("modelo"),
                        situacao = item.textoOuNulo("situacao"),
                        situacaoRotulo = item.textoOuNulo("situacaoRotulo")
                    )
                }
            )
        }

        private fun JSONObject.textoOuNulo(nome: String): String? =
            if (!has(nome) || isNull(nome)) null else getString(nome).ifBlank { null }
    }
}

/** Situação do inventário em andamento comparada com o esperado. */
data class ComparacaoInventario(
    val esperados: Int,
    /** Esperadas que já foram lidas. */
    val encontradas: Int,
    /** Esperadas que ainda não foram lidas, na ordem da lista (por patrimônio). */
    val faltando: List<ItemEsperado>,
    /** Lidas que não estão na lista do esperado (chegaram ao local depois, ou não cadastradas). */
    val foraDaLista: Int
) {
    companion object {
        fun comparar(esperado: EsperadoLocal, lidas: Collection<String>): ComparacaoInventario {
            val lidasSet = lidas.toSet()
            val epcsEsperados = esperado.itens.mapTo(HashSet()) { it.epc }
            val faltando = esperado.itens.filter { it.epc !in lidasSet }

            return ComparacaoInventario(
                esperados = esperado.itens.size,
                encontradas = esperado.itens.size - faltando.size,
                faltando = faltando,
                foraDaLista = lidasSet.count { it !in epcsEsperados }
            )
        }
    }
}

/** Uma etiqueta lida no inventário, como aparece na aba Lidas. */
data class LidaInventario(
    val epc: String,
    /** Patrimônio, quando a etiqueta está na lista do esperado. */
    val patrimonio: String?,
    /** Lida mas não esperada no local (só quando há lista do esperado para comparar). */
    val foraDaLista: Boolean,
    /** Cadastro da fora da lista consultado no servidor (patrimônio, tipo, situação); null enquanto não consultou. */
    val ficha: com.example.rfid_server.view.TagView? = null,
    /** O servidor respondeu que a etiqueta fora da lista não está cadastrada. */
    val semCadastro: Boolean = false,
    /** Transferência para este local pedida pelo operador (pendente ou já respondida pelo servidor); null = nenhuma. */
    val transferencia: com.example.rfid_server.sincronizacao.FilaTransferencias.Item? = null
) {
    /** Patrimônio para mostrar: o da lista do esperado ou, na fora da lista, o do cadastro consultado. */
    val patrimonioConhecido: String? get() = patrimonio ?: ficha?.patrimonio?.ifBlank { null }

    /**
     * Dá para pedir a transferência para este local: fora da lista, cadastro consultado (o servidor recusa etiqueta sem
     * cadastro), bem não baixado (o servidor recusa com 409) e sem pedido pendente ou já feito. Extraviado pode: achar
     * o bem extraviado na sala é justamente o caso de trazê-lo para o local onde está.
     */
    val podePedirTransferencia: Boolean
        get() = foraDaLista && ficha != null && !semCadastro && ficha.situacao != "BAIXADO" &&
            transferencia?.estado.let { it == null || it == com.example.rfid_server.sincronizacao.FilaTransferencias.Estado.RECUSADA }

    companion object {
        /** Junta às lidas o que já se sabe das fora da lista ([fichas] por EPC, as [semCadastro] e as [transferencias]). */
        fun comFichas(
            lidas: List<LidaInventario>, fichas: Map<String, com.example.rfid_server.view.TagView>, semCadastro: Set<String>,
            transferencias: Map<String, com.example.rfid_server.sincronizacao.FilaTransferencias.Item> = emptyMap()
        ): List<LidaInventario> =
            if (fichas.isEmpty() && semCadastro.isEmpty() && transferencias.isEmpty()) lidas
            else lidas.map {
                if (it.foraDaLista) it.copy(ficha = fichas[it.epc], semCadastro = it.epc in semCadastro, transferencia = transferencias[it.epc])
                else it
            }

        /**
         * Monta a aba Lidas: com lista do esperado ([patrimoniosEsperados] = EPC → patrimônio), as fora da lista
         * vêm primeiro (são as que pedem atenção), cada grupo na ordem de leitura. Sem lista (null), nenhuma
         * fica marcada como fora da lista e a ordem é a de leitura.
         */
        fun classificar(lidas: List<String>, patrimoniosEsperados: Map<String, String?>?): List<LidaInventario> {
            if (patrimoniosEsperados == null) return lidas.map { LidaInventario(it, null, foraDaLista = false) }

            val todas = lidas.map { epc ->
                LidaInventario(epc, patrimoniosEsperados[epc], foraDaLista = !patrimoniosEsperados.containsKey(epc))
            }
            val (fora, esperadas) = todas.partition { it.foraDaLista }
            return fora + esperadas
        }
    }
}
