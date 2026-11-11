package com.example.rfid_server.activities

import com.example.rfid_server.CelulaCsv
import com.example.rfid_server.adapter.BuscaLeituras
import com.example.rfid_server.dados.ComparacaoInventario
import com.example.rfid_server.dados.ItemEsperado
import com.example.rfid_server.sincronizacao.FilaJustificativas
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Resumo do inventário finalizado: aparece num diálogo e pode ser compartilhado (texto simples). */
data class ResumoFinalizacao(
    val local: String,
    val finalizadoEm: Long,
    val operador: String?,
    val lidas: Int,
    /** Null: sem lista do esperado para comparar. */
    val comparacao: ComparacaoInventario?,
    val inventarioId: String = "",
    /** EPCs gravados no inventário na finalização (para a planilha). */
    val etiquetas: List<String> = emptyList(),
    /** Itens esperados (para a planilha dizer o patrimônio das encontradas). */
    val esperados: List<ItemEsperado> = emptyList(),
    /**
     * Lidas que o cadastro marca como baixadas ou extraviadas (EPC → "Patrimônio 123 - Monitor (Baixado)"): bens que
     * não deveriam estar circulando, para quem recebe o resumo agir.
     */
    val alertas: Map<String, String> = emptyMap()
) {
    /** Esperadas de um tipo de bem: quantas eram e quantas foram lidas. */
    data class ContagemTipo(val tipo: String, val encontradas: Int, val esperadas: Int) {
        val faltam: Int get() = esperadas - encontradas
    }

    /**
     * Esperadas por tipo de bem ("Notebook: 10 de 12"), primeiro os tipos com mais faltantes (empate: ordem
     * alfabética). Tipo em branco conta como [semTipo]; grafias diferentes do mesmo tipo contam juntas. Vazio sem
     * lista do esperado (as fora da lista não têm tipo no coletor).
     */
    fun porTipo(semTipo: String = SEM_TIPO): List<ContagemTipo> {
        val c = comparacao ?: return emptyList()
        val faltando = c.faltando.mapTo(HashSet()) { it.epc }
        return esperados
            .groupBy { BuscaLeituras.normalizar(it.tipo.orEmpty().ifBlank { semTipo }) }
            .map { (_, itens) ->
                ContagemTipo(itens.first().tipo?.trim().orEmpty().ifEmpty { semTipo }, itens.count { it.epc !in faltando }, itens.size)
            }
            .sortedWith(compareByDescending<ContagemTipo> { it.faltam }.thenBy { BuscaLeituras.normalizar(it.tipo) })
    }

    /** "2 Notebook, 1 Monitor": os tipos que tiveram faltantes, no máximo [maximo] (o resto vira "…"); null se nada faltou. */
    fun faltantesPorTipo(semTipo: String = SEM_TIPO, maximo: Int = 3): String? {
        val comFalta = porTipo(semTipo).filter { it.faltam > 0 }
        if (comFalta.isEmpty()) return null
        return comFalta.take(maximo).joinToString(", ") { "${it.faltam} ${it.tipo}" } + if (comFalta.size > maximo) ", …" else ""
    }

    /**
     * Planilha (CSV com ";", que o Excel em português abre direto) com uma linha por etiqueta: situação (Encontrada,
     * Faltando, Fora da lista), patrimônio, descrição, EPC e a justificativa dos que faltaram ([motivos]: EPC → texto).
     */
    fun csv(motivos: Map<String, String> = emptyMap()): String = buildString {
        appendLine("Situação;Patrimônio;Descrição;Etiqueta (EPC);Justificativa")
        val porEpc = esperados.associateBy { it.epc }
        val lidas = etiquetas.toSet()
        etiquetas.forEach { epc ->
            val item = porEpc[epc]
            linha(if (item != null || comparacao == null) "Encontrada" else "Fora da lista", item?.patrimonio, item?.descricao(), epc, null)
        }
        comparacao?.faltando?.filter { it.epc !in lidas }?.forEach {
            linha("Faltando", it.patrimonio, it.descricao(), it.epc, motivos[it.epc])
        }
    }

    private fun StringBuilder.linha(situacao: String, patrimonio: String?, descricao: String?, epc: String, motivo: String?) {
        appendLine(listOf(situacao, patrimonio.orEmpty(), descricao.orEmpty(), epc, motivo.orEmpty())
            .joinToString(";") { CelulaCsv.formatar(it) })
    }

    /**
     * Texto para compartilhar (WhatsApp, e-mail...): local, data/hora, operador, contagens e o que faltou, com a
     * justificativa de cada faltante entre parênteses quando houver ([justificativas]: EPC → texto).
     */
    fun textoCompartilhar(fuso: ZoneId = ZoneId.systemDefault(), justificativas: Map<String, String> = emptyMap()): String = buildString {
        val data = FORMATO_DATA.format(Instant.ofEpochMilli(finalizadoEm).atZone(fuso))

        appendLine("Inventário de $local")
        appendLine("Finalizado em: $data")
        appendLine("Operador: ${operador ?: "não identificado"}")
        appendLine("Etiquetas lidas: $lidas")

        val c = comparacao
        if (c == null) {
            append("Sem lista do esperado para comparar (primeiro inventário do local ou lista indisponível)")
            anexarAlertas()
            return@buildString
        }

        appendLine("Encontradas: ${c.encontradas} de ${c.esperados} esperada(s)")
        appendLine("Faltando: ${c.faltando.size}")
        append("Fora da lista: ${c.foraDaLista}")

        // Por tipo de bem: quem recebe o texto vê de cara se faltou notebook ou cadeira
        val tipos = porTipo()
        if (tipos.size > 1 || tipos.any { it.faltam > 0 }) {
            appendLine().appendLine().append("Por tipo (encontradas de esperadas):")
            tipos.forEach { appendLine().append("- ${it.tipo}: ${it.encontradas} de ${it.esperadas}") }
        }

        if (c.faltando.isNotEmpty()) {
            appendLine().appendLine().append("O que faltou:")
            c.faltando.forEach { item ->
                appendLine().append("- ").append(descreverFaltante(item))
                justificativas[item.epc]?.let { append(" (").append(it).append(')') }
            }
        }
        anexarAlertas()
    }

    private fun StringBuilder.anexarAlertas() {
        if (alertas.isEmpty()) return
        appendLine().appendLine().append("Atenção, lidos com alerta no cadastro:")
        alertas.values.forEach { appendLine().append("- ").append(it) }
    }

    companion object {
        private val FORMATO_DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

        /** Nome usado para bens sem tipo cadastrado (o mesmo da tela de Leituras). */
        const val SEM_TIPO = "Sem tipo informado"

        /**
         * Motivo que a situação no cadastro já explica (o bem consta como emprestado ou em manutenção, então faltar
         * no local é esperado). Null para as outras situações: aí só o operador sabe o motivo.
         */
        fun motivoPeloCadastro(item: ItemEsperado): FilaJustificativas.Motivo? =
            when (item.situacao) {
                // ALUGADO: locação (servidor 1.12.0; o EMPRESTADO antigo virou ALUGADO)
                "EMPRESTADO", "ALUGADO" -> FilaJustificativas.Motivo.EMPRESTADO
                "EM_MANUTENCAO" -> FilaJustificativas.Motivo.MANUTENCAO
                "BAIXADO" -> FilaJustificativas.Motivo.BAIXADO
                else -> null
            }

        /** Faltantes sem justificativa que o cadastro explica, agrupados pelo motivo (EPCs). */
        fun justificativasPeloCadastro(
            semJustificativa: List<ItemEsperado>
        ): Map<FilaJustificativas.Motivo, List<String>> =
            semJustificativa.mapNotNull { item -> motivoPeloCadastro(item)?.let { it to item.epc } }
                .groupBy({ it.first }, { it.second })

        /** "Patrimônio 123 - Notebook · Dell" ou, sem patrimônio, "EPC E200... - Notebook". */
        fun descreverFaltante(item: ItemEsperado): String {
            val identificacao = item.patrimonio?.let { "Patrimônio $it" } ?: "EPC ${item.epc}"
            val descricao = item.descricao()
            return if (descricao.isEmpty()) identificacao else "$identificacao - $descricao"
        }
    }
}
