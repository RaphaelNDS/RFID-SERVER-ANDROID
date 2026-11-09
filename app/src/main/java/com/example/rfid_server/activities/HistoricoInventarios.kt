package com.example.rfid_server.activities

import com.example.rfid_server.dados.ComparacaoInventario
import com.example.rfid_server.dados.EsperadoLocal
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Inventários anteriores feitos neste coletor: o resumo de cada inventário finalizado (o mesmo do diálogo do fim), num
 * arquivo por inventário na pasta [pasta]. O inventário em si sai do banco local assim que chega ao servidor; o resumo
 * fica para consultar, compartilhar de novo (texto e planilha) e justificar faltantes depois. Ficam os [MAXIMO] mais novos.
 * Sem dependência do Android, para os testes.
 */
class HistoricoInventarios(private val pasta: File) {

    fun salvar(resumo: ResumoFinalizacao) {
        if (resumo.inventarioId.isEmpty()) return
        pasta.mkdirs()
        val arquivo = File(pasta, nomeArquivo(resumo.inventarioId))
        val temporario = File(pasta, arquivo.name + ".tmp")
        temporario.writeText(paraJson(resumo))
        if (!temporario.renameTo(arquivo)) {
            arquivo.writeText(paraJson(resumo))
            temporario.delete()
        }
        // Só os mais novos (por data de finalização, que vai no nome do arquivo)
        listarArquivos().drop(MAXIMO).forEach { it.delete() }
    }

    /** Do mais novo para o mais antigo; arquivo ilegível é ignorado. */
    fun listar(): List<ResumoFinalizacao> = listarArquivos().mapNotNull { arquivo ->
        try {
            deJson(arquivo.readText())
        } catch (e: Exception) {
            null
        }
    }

    private fun listarArquivos(): List<File> =
        pasta.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().sortedByDescending { it.lastModified() }

    // O id do inventário é um UUID: vale como nome de arquivo
    private fun nomeArquivo(id: String) = id.replace(Regex("[^A-Za-z0-9-]"), "_") + ".json"

    companion object {
        const val MAXIMO = 20

        fun paraJson(r: ResumoFinalizacao): String = JSONObject()
            .put("inventarioId", r.inventarioId)
            .put("local", r.local)
            .put("finalizadoEm", r.finalizadoEm)
            .put("operador", r.operador ?: JSONObject.NULL)
            .put("lidas", r.lidas)
            .put("comLista", r.comparacao != null)
            .put("etiquetas", JSONArray(r.etiquetas))
            // Itens esperados no mesmo formato da lista do esperado guardada no coletor
            .put("esperados", JSONObject(EsperadoLocal(r.local, temReferencia = true, itens = r.esperados).paraJson()).getJSONArray("itens"))
            .toString()

        fun deJson(texto: String): ResumoFinalizacao {
            val o = JSONObject(texto)
            val local = o.getString("local")
            val etiquetas = o.optJSONArray("etiquetas")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            val esperado = EsperadoLocal.deJson(
                JSONObject().put("local", local).put("temReferencia", true).put("itens", o.optJSONArray("esperados") ?: JSONArray()).toString()
            )
            val comparacao = if (o.optBoolean("comLista")) ComparacaoInventario.comparar(esperado, etiquetas.toSet()) else null
            return ResumoFinalizacao(
                local = local,
                finalizadoEm = o.getLong("finalizadoEm"),
                operador = if (o.isNull("operador")) null else o.optString("operador"),
                lidas = o.optInt("lidas"),
                comparacao = comparacao,
                inventarioId = o.optString("inventarioId"),
                etiquetas = etiquetas,
                esperados = esperado.itens
            )
        }
    }
}
