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

    /**
     * Faltando filtrado pelo [termo]. Os que o cadastro já explica (emprestado, em manutenção) vão para o fim, na
     * mesma ordem entre si: o operador procura primeiro os que deviam estar na sala.
     */
    fun faltando(itens: List<ItemEsperado>, termo: String): List<ItemEsperado> =
        filtrar(itens, termo) { listOf(it.patrimonio, it.epc) }.sortedBy { ResumoFinalizacao.motivoPeloCadastro(it) != null }

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

/**
 * Quais etiquetas fora da lista consultar no servidor (ficha do bem): cada uma uma vez só, no máximo [maximo] por
 * inventário (uma sala inteira fora da lista não vira centenas de consultas), e a que falhou (sem rede) só de novo
 * depois de [esperaFalhaMs]. Sem dependência do Android, para os testes; usado só na thread principal.
 */
class ConsultasForaDaLista(private val maximo: Int = 300, private val esperaFalhaMs: Long = 60_000) {
    private val pedidas = HashSet<String>()
    private val falhas = HashMap<String, Long>()

    /** Marca e devolve as de [epcs] que devem ser consultadas agora. */
    fun paraConsultar(epcs: List<String>, agora: Long): List<String> {
        val novas = ArrayList<String>()
        for (epc in epcs) {
            if (epc in pedidas) continue
            val falhou = falhas[epc]
            if (falhou != null && agora - falhou < esperaFalhaMs) continue
            if (falhou == null && pedidas.size + falhas.size >= maximo) break
            falhas.remove(epc)
            pedidas += epc
            novas += epc
        }
        return novas
    }

    /** A consulta não deu certo (sem rede, servidor fora): pode ser pedida de novo depois da espera. */
    fun falhou(epc: String, agora: Long) {
        pedidas.remove(epc)
        falhas[epc] = agora
    }

    /** Novo inventário: tudo de novo. */
    fun limpar() {
        pedidas.clear()
        falhas.clear()
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
            .groupBy { com.example.rfid_server.adapter.BuscaLeituras.normalizar(it.tipo.orEmpty().ifBlank { semTipo }) }
            .map { (_, itens) ->
                ContagemTipo(itens.first().tipo?.trim().orEmpty().ifEmpty { semTipo }, itens.count { it.epc !in faltando }, itens.size)
            }
            .sortedWith(compareByDescending<ContagemTipo> { it.faltam }.thenBy { com.example.rfid_server.adapter.BuscaLeituras.normalizar(it.tipo) })
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
        fun motivoPeloCadastro(item: ItemEsperado): com.example.rfid_server.sincronizacao.FilaJustificativas.Motivo? =
            when (item.situacao) {
                "EMPRESTADO" -> com.example.rfid_server.sincronizacao.FilaJustificativas.Motivo.EMPRESTADO
                "EM_MANUTENCAO" -> com.example.rfid_server.sincronizacao.FilaJustificativas.Motivo.MANUTENCAO
                "BAIXADO" -> com.example.rfid_server.sincronizacao.FilaJustificativas.Motivo.BAIXADO
                else -> null
            }

        /** Faltantes sem justificativa que o cadastro explica, agrupados pelo motivo (EPCs). */
        fun justificativasPeloCadastro(
            semJustificativa: List<ItemEsperado>
        ): Map<com.example.rfid_server.sincronizacao.FilaJustificativas.Motivo, List<String>> =
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
