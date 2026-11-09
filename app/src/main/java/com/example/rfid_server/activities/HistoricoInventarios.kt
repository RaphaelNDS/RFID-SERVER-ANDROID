package com.example.rfid_server.activities

import com.example.rfid_server.dados.ComparacaoInventario
import com.example.rfid_server.dados.EsperadoLocal
import com.example.rfid_server.sincronizacao.FilaJustificativas
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Inventários anteriores feitos neste coletor: o resumo de cada inventário finalizado (o mesmo do diálogo do fim), num
 * arquivo por inventário na pasta [pasta]. O inventário em si sai do banco local assim que chega ao servidor; o resumo
 * fica para consultar, compartilhar de novo (texto e planilha) e justificar faltantes depois. Ficam os [MAXIMO] mais novos.
 *
 * As justificativas dos faltantes também ficam aqui ([justificar]): a fila de envio (FilaJustificativas) esquece cada
 * uma assim que o servidor confirma, e sem isto o resumo reaberto mostrava de novo como "sem justificativa" o que já
 * tinha sido justificado (e a planilha saía sem os motivos). Sem dependência do Android, para os testes.
 */
class HistoricoInventarios(private val pasta: File) {

    fun salvar(resumo: ResumoFinalizacao): Unit = synchronized(TRAVA) {
        if (resumo.inventarioId.isEmpty()) return
        // Justificativas que já estavam guardadas para este inventário continuam valendo
        val anteriores = lerMotivos(arquivoDe(resumo.inventarioId))
        gravar(arquivoDe(resumo.inventarioId), paraJson(resumo, anteriores))
        // Só os mais novos (por data de finalização; a data de alteração do arquivo muda quando alguém justifica)
        lerTodos().drop(MAXIMO).forEach { it.first.delete() }
    }

    /** Do mais novo para o mais antigo (data de finalização); arquivo ilegível é ignorado. */
    fun listar(): List<ResumoFinalizacao> = synchronized(TRAVA) { lerTodos().map { it.second } }

    /** Guarda o motivo das etiquetas [epcs] do inventário [inventarioId] (justificar de novo troca o motivo). */
    fun justificar(inventarioId: String, epcs: Collection<String>, motivo: FilaJustificativas.Motivo): Unit = synchronized(TRAVA) {
        val arquivo = arquivoDe(inventarioId)
        if (!arquivo.exists()) return
        val o = try { JSONObject(arquivo.readText()) } catch (e: Exception) { return }
        val motivos = o.optJSONObject("justificativas") ?: JSONObject()
        epcs.forEach { motivos.put(it, motivo.name) }
        gravar(arquivo, o.put("justificativas", motivos).toString())
    }

    /** Motivos guardados para as etiquetas do [inventarioId] (vazio se o inventário não está no histórico). */
    fun motivos(inventarioId: String): Map<String, FilaJustificativas.Motivo> =
        synchronized(TRAVA) { lerMotivos(arquivoDe(inventarioId)) }

    private fun lerMotivos(arquivo: File): Map<String, FilaJustificativas.Motivo> =
        try {
            if (!arquivo.exists()) emptyMap()
            else motivosDe(JSONObject(arquivo.readText()))
        } catch (e: Exception) {
            emptyMap()
        }

    private fun lerTodos(): List<Pair<File, ResumoFinalizacao>> =
        pasta.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { arquivo ->
                try {
                    arquivo to deJson(arquivo.readText())
                } catch (e: Exception) {
                    null
                }
            }
            .sortedByDescending { it.second.finalizadoEm }

    private fun gravar(arquivo: File, texto: String) {
        pasta.mkdirs()
        val temporario = File(pasta, arquivo.name + ".tmp")
        temporario.writeText(texto)
        if (!temporario.renameTo(arquivo)) {
            arquivo.writeText(texto)
            temporario.delete()
        }
    }

    // O id do inventário é um UUID: vale como nome de arquivo
    private fun arquivoDe(id: String) = File(pasta, id.replace(Regex("[^A-Za-z0-9-]"), "_") + ".json")

    companion object {
        const val MAXIMO = 20

        // Uma trava para todas as instâncias: a tela cria uma por uso, e o resumo e as justificativas mexem no mesmo arquivo
        private val TRAVA = Any()

        fun paraJson(r: ResumoFinalizacao, motivos: Map<String, FilaJustificativas.Motivo> = emptyMap()): String = JSONObject()
            .put("inventarioId", r.inventarioId)
            .put("local", r.local)
            .put("finalizadoEm", r.finalizadoEm)
            .put("operador", r.operador ?: JSONObject.NULL)
            .put("lidas", r.lidas)
            .put("comLista", r.comparacao != null)
            .put("etiquetas", JSONArray(r.etiquetas))
            // Itens esperados no mesmo formato da lista do esperado guardada no coletor
            .put("esperados", JSONObject(EsperadoLocal(r.local, temReferencia = true, itens = r.esperados).paraJson()).getJSONArray("itens"))
            .put("justificativas", JSONObject().apply { motivos.forEach { (epc, m) -> put(epc, m.name) } })
            .toString()

        private fun motivosDe(o: JSONObject): Map<String, FilaJustificativas.Motivo> {
            val j = o.optJSONObject("justificativas") ?: return emptyMap()
            return j.keys().asSequence().mapNotNull { epc ->
                FilaJustificativas.Motivo.entries.firstOrNull { it.name == j.optString(epc) }?.let { epc to it }
            }.toMap()
        }

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
