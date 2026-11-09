package com.example.rfid_server.activities

import com.example.rfid_server.dados.ComparacaoInventario
import com.example.rfid_server.dados.EsperadoLocal
import com.example.rfid_server.dados.ItemEsperado
import com.example.rfid_server.dados.LidaInventario
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Nome do local do inventário, com a mesma regra do servidor (InventarioService): espaços nas pontas tirados,
 * espaços repetidos viram um e no máximo [MAXIMO] caracteres. Local maior era aceito offline e o servidor
 * recusava a abertura (400) em toda passada, até o coletor desistir.
 */
object LocalInventario {

    const val MAXIMO = 120

    fun normalizar(local: String): String = local.trim().replace(Regex("\\s+"), " ")

    /** O local normalizado cabe no limite do servidor (o vazio é tratado à parte, com outra mensagem). */
    fun dentroDoLimite(local: String): Boolean = normalizar(local).length <= MAXIMO
}

/**
 * Filtro das abas Faltando e Lidas do inventário: só em memória (o banco não muda). Sem dependência do Android,
 * para poder ser testado no computador.
 */
object FiltroInventario {

    /** Abaixo disso a lista cabe na tela e o campo de filtro só ocuparia espaço. */
    const val MINIMO_PARA_FILTRAR = 20

    /** O campo aparece com lista grande, e não some enquanto houver texto digitado (senão o filtro ficaria escondido). */
    fun mostrarCampo(tamanhoDaLista: Int, termo: String): Boolean =
        tamanhoDaLista >= MINIMO_PARA_FILTRAR || termo.isNotBlank()

    fun faltando(itens: List<ItemEsperado>, termo: String): List<ItemEsperado> =
        filtrar(itens, termo) { listOf(it.patrimonio, it.epc) }

    fun lidas(itens: List<LidaInventario>, termo: String): List<LidaInventario> =
        filtrar(itens, termo) { listOf(it.patrimonio, it.epc) }

    /** Mantém os itens em que o patrimônio ou o EPC contém o [termo] (sem diferença de maiúsculas e minúsculas). */
    private fun <T> filtrar(itens: List<T>, termo: String, textos: (T) -> List<String?>): List<T> {
        val procurado = termo.trim()
        if (procurado.isEmpty()) return itens
        return itens.filter { item -> textos(item).any { it?.contains(procurado, ignoreCase = true) == true } }
    }
}

/**
 * Agrupa as atualizações da tela de inventário: com milhares de etiquetas, recalcular a cada leitura pesava.
 * Devolve quanto esperar (ms) para o próximo cálculo, de forma que ele rode no máximo uma vez a cada [intervaloMs].
 */
object RitmoComparacao {

    const val INTERVALO_MS = 300L

    fun espera(agora: Long, ultimoCalculo: Long?, intervaloMs: Long = INTERVALO_MS): Long {
        if (ultimoCalculo == null) return 0
        return (ultimoCalculo + intervaloMs - agora).coerceIn(0, intervaloMs)
    }
}

/** Decisões de apresentação da tela de inventário em andamento (testadas no computador). */
object RegrasTelaInventario {

    /**
     * A instrução "Aperte o gatilho…" só aparece até a primeira leitura: depois disso o operador já sabe o que
     * fazer, e a linha vira espaço para a lista do que falta.
     */
    fun mostrarInstrucao(totalLidas: Int): Boolean = totalLidas == 0
}

/** O que aparece na tela de inventário depois de cada cálculo (feito fora da thread principal). */
data class SituacaoTelaInventario(
    /** Null: sem lista do esperado para comparar (primeiro inventário, servidor sem a lista, ainda buscando). */
    val comparacao: ComparacaoInventario?,
    val totalLidas: Int,
    /** Já filtradas pelo termo. */
    val lidas: List<LidaInventario>,
    /** Já filtradas pelo termo. */
    val faltando: List<ItemEsperado>
) {
    companion object {
        fun calcular(
            esperado: EsperadoLocal?,
            patrimoniosEsperados: Map<String, String?>,
            epcsLidos: List<String>,
            termo: String
        ): SituacaoTelaInventario {
            val referencia = esperado?.takeIf { it.temReferencia }
            val comparacao = referencia?.let { ComparacaoInventario.comparar(it, epcsLidos) }
            val lidas = LidaInventario.classificar(epcsLidos, if (referencia != null) patrimoniosEsperados else null)

            return SituacaoTelaInventario(
                comparacao = comparacao,
                totalLidas = epcsLidos.size,
                lidas = FiltroInventario.lidas(lidas, termo),
                faltando = FiltroInventario.faltando(comparacao?.faltando.orEmpty(), termo)
            )
        }
    }
}

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
    val esperados: List<ItemEsperado> = emptyList()
) {
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
            .joinToString(";") { com.example.rfid_server.CelulaCsv.formatar(it) })
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
            return@buildString
        }

        appendLine("Encontradas: ${c.encontradas} de ${c.esperados} esperada(s)")
        appendLine("Faltando: ${c.faltando.size}")
        append("Fora da lista: ${c.foraDaLista}")

        if (c.faltando.isNotEmpty()) {
            appendLine().appendLine().append("O que faltou:")
            c.faltando.forEach { item ->
                appendLine().append("- ").append(descreverFaltante(item))
                justificativas[item.epc]?.let { append(" (").append(it).append(')') }
            }
        }
    }

    companion object {
        private val FORMATO_DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

        /** "Patrimônio 123 - Notebook · Dell" ou, sem patrimônio, "EPC E200... - Notebook". */
        fun descreverFaltante(item: ItemEsperado): String {
            val identificacao = item.patrimonio?.let { "Patrimônio $it" } ?: "EPC ${item.epc}"
            val descricao = item.descricao()
            return if (descricao.isEmpty()) identificacao else "$identificacao - $descricao"
        }
    }
}
