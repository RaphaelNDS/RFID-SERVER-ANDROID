package com.example.rfid_server.adapter

import com.example.rfid_server.dados.ItemNaoCadastrada
import java.text.Normalizer

/**
 * Busca da tela de Leituras e planilha da leitura atual (sem dependência do Android, para os testes).
 * A busca ignora maiúsculas, acentos e espaços nas pontas e procura em patrimônio, tipo, marca, modelo, código interno,
 * série e EPC (nas não cadastradas e nas que esperam o servidor, só o EPC).
 */
object BuscaLeituras {

    fun filtrar(itens: List<ItemLeitura>, termo: String): List<ItemLeitura> {
        val t = normalizar(termo)
        if (t.isEmpty()) return itens
        return itens.filter { item ->
            when (item) {
                is ItemLeitura.Cadastrada -> with(item.tag) {
                    listOf(patrimonio, tipo, marca, modelo, codigoInterno, numeroSerie, epc).any { normalizar(it.orEmpty()).contains(t) }
                }
                is ItemLeitura.Aguardando -> normalizar(item.epc).contains(t)
            }
        }
    }

    fun filtrarNaoCadastradas(itens: List<ItemNaoCadastrada>, termo: String): List<ItemNaoCadastrada> {
        val t = normalizar(termo)
        if (t.isEmpty()) return itens
        return itens.filter { normalizar(it.epc).contains(t) }
    }

    /**
     * Quantos bens de cada tipo há na leitura atual ("Notebook" 12, "Monitor" 5...), do tipo mais comum para o menos
     * comum (empate: ordem alfabética). Tipo em branco conta como [semTipo]; mesmo tipo escrito com maiúsculas ou
     * acentos diferentes conta junto (vale a grafia do primeiro). Só as cadastradas: as outras não têm tipo.
     */
    fun contagemPorTipo(leituras: List<ItemLeitura>, semTipo: String): List<Pair<String, Int>> =
        leituras.filterIsInstance<ItemLeitura.Cadastrada>()
            .map { it.tag.tipo?.trim().orEmpty().ifEmpty { semTipo } }
            .groupBy { normalizar(it) }
            .map { (_, nomes) -> nomes.first() to nomes.size }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { normalizar(it.first) })

    /**
     * Cadastradas por situação no cadastro (Disponível, Alugado, Em manutenção...: locadora, servidor 1.12.0), as que
     * têm mais primeiro. Vazio se o servidor não manda a situação de nenhuma (aí o resumo não mostra a parte).
     */
    fun contagemPorSituacao(leituras: List<ItemLeitura>, semSituacao: String): List<Pair<String, Int>> {
        val cadastradas = leituras.filterIsInstance<ItemLeitura.Cadastrada>()
        if (cadastradas.none { it.tag.situacao != null || it.tag.situacaoRotulo != null }) return emptyList()
        return cadastradas
            .map { (it.tag.situacaoRotulo ?: it.tag.situacao)?.trim().orEmpty().ifEmpty { semSituacao } }
            .groupBy { normalizar(it) }
            .map { (_, nomes) -> nomes.first() to nomes.size }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { normalizar(it.first) })
    }

    /**
     * Planilha (CSV com ";", para o Excel em português) da leitura atual: uma linha por etiqueta, com a situação
     * (Cadastrada, Aguardando o servidor, Não cadastrada), os dados do bem e o EPC.
     */
    fun csv(leituras: List<ItemLeitura>, naoCadastradas: List<ItemNaoCadastrada>): String = buildString {
        appendLine("Situação;Patrimônio;Tipo;Marca;Modelo;Código interno;Nº de série;Etiqueta (EPC);Situação no cadastro")
        leituras.forEach { item ->
            when (item) {
                is ItemLeitura.Cadastrada -> with(item.tag) {
                    linha("Cadastrada", patrimonio, tipo, marca, modelo, codigoInterno, numeroSerie, epc, situacaoRotulo ?: situacao)
                }
                is ItemLeitura.Aguardando -> linha("Aguardando o servidor", "", "", "", "", "", "", item.epc, "")
            }
        }
        naoCadastradas.forEach { linha("Não cadastrada", "", "", "", "", "", "", it.epc, "") }
    }

    private fun StringBuilder.linha(vararg celulas: String?) {
        appendLine(celulas.joinToString(";") { com.example.rfid_server.CelulaCsv.formatar(it.orEmpty()) })
    }

    /** Minúsculas, sem acentos e sem espaços nas pontas: para comparar textos digitados de jeitos diferentes. */
    fun normalizar(texto: String): String =
        Normalizer.normalize(texto.trim().lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
}
