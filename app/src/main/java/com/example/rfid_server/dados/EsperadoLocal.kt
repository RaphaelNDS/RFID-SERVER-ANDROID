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
    val modelo: String?
) {
    /** "Notebook · Dell · Latitude" (só o que houver). */
    fun descricao(): String = listOfNotNull(tipo, marca, modelo).joinToString(" · ")
}

/**
 * O que é esperado num local (GET /api/android/inventarios/esperado): o que foi encontrado no último
 * inventário finalizado dele. [temReferencia] = false: primeiro inventário do local, sem lista.
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
    /** "inventário de 10/10/2026 (Ana)", para a tela dizer com o que está comparando. */
    fun descricaoReferencia(): String = buildString {
        append("inventário")
        dataReferencia()?.let { append(" de $it") }
        operador?.let { append(" ($it)") }
    }

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
                )
            }
        })
        .toString()

    companion object {
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
                        modelo = item.textoOuNulo("modelo")
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
