package com.example.rfid_server.leitor

import com.example.rfid_server.R
import androidx.annotation.StringRes
import java.util.Locale

/*
 * Regras puras da faixa do leitor RFID (motivos de falha, bateria, agrupamento dos registros de erro): estes tipos,
 * BateriaLeitor, FaixaLeitor e AgrupadorFalhas. Sem dependência do SDK da Zebra nem do Android (fora os ids de
 * R.string), para poder ser testados no computador. O RfidManager converte o que vem do SDK para estes tipos; o
 * IndicadorLeitor transforma em texto e cor.
 */

/** Por que o leitor não conectou, caiu ou não começou a ler. */
enum class CausaFalhaLeitor {
    NENHUM_LEITOR,
    OCUPADO_OUTRO_APP,
    OPERACAO_EM_ANDAMENTO,
    SEM_COMUNICACAO,
    TEMPO_ESGOTADO,
    REGIAO_NAO_CONFIGURADA,
    BATERIA,
    /** Coletor no carregador (berço): o MC3300x não deixa ler enquanto carrega ("Charging in Progress"). */
    CARREGANDO,
    TEMPERATURA,
    QUEDA,
    /** Nenhuma das conhecidas: a tela mostra o texto do próprio SDK ([MotivoFalhaLeitor.detalhe]). */
    DESCONHECIDA
}

/** [causa] traduzível e o [detalhe] original do SDK (vai para o log/servidor e, se a causa for desconhecida, para a tela). */
data class MotivoFalhaLeitor(val causa: CausaFalhaLeitor, val detalhe: String)

/** Parte da configuração feita logo depois de conectar. */
enum class ParteConfiguracao { GATILHO, POTENCIA }

data class FalhaConfiguracao(val parte: ParteConfiguracao, val motivo: MotivoFalhaLeitor)

/** O que a faixa do leitor mostra além do [EstadoLeitor]. [momentoFalhaLeitura] no relógio de [FaixaLeitor.escolher]. */
data class DetalheLeitor(
    val motivoDesconexao: MotivoFalhaLeitor? = null,
    val falhaConfiguracao: FalhaConfiguracao? = null,
    val falhaLeitura: MotivoFalhaLeitor? = null,
    val momentoFalhaLeitura: Long = 0L,
    val bateria: Int? = null,
    val carregando: Boolean = false
)

object MotivoLeitor {

    private const val TAMANHO_MAXIMO_DETALHE = 120

    /**
     * Junta a causa reconhecida pelo código do SDK ([causaSdk], se houver) com palavras-chave dos textos do erro
     * ([textos]: mensagem, descrição, código em texto). A causa do código vence; sem ela, procura nos textos.
     */
    fun classificar(causaSdk: CausaFalhaLeitor?, textos: List<String?>): MotivoFalhaLeitor {
        val detalhe = detalhe(textos)
        // "Charging in Progress-Command Not allowed": o SDK manda com o código de operação em andamento, mas o que o
        // operador precisa saber é que o coletor está no carregador (visto no MC3300x em 09/11)
        if (textos.any { it?.contains("charging", ignoreCase = true) == true }) return MotivoFalhaLeitor(CausaFalhaLeitor.CARREGANDO, detalhe)
        if (causaSdk != null && causaSdk != CausaFalhaLeitor.DESCONHECIDA) return MotivoFalhaLeitor(causaSdk, detalhe)

        val tudo = textos.filterNotNull().joinToString(" ").lowercase(Locale.ROOT)
        val causa = when {
            "batter" in tudo || "bateria" in tudo -> CausaFalhaLeitor.BATERIA
            "temperat" in tudo || "overheat" in tudo -> CausaFalhaLeitor.TEMPERATURA
            // COMM_OPEN_ERROR: a porta do leitor já está aberta por outro app (ex.: os apps de demonstração da Zebra
            // ou a versão de teste deste app); visto no MC3300x em 09/11
            "in use" in tudo || "busy" in tudo || "another application" in tudo || "comm_open_error" in tudo -> CausaFalhaLeitor.OCUPADO_OUTRO_APP
            "in progress" in tudo || "in_progress" in tudo -> CausaFalhaLeitor.OPERACAO_EM_ANDAMENTO
            "region" in tudo -> CausaFalhaLeitor.REGIAO_NAO_CONFIGURADA
            "timeout" in tudo || "timed out" in tudo -> CausaFalhaLeitor.TEMPO_ESGOTADO
            "no_connection" in tudo || "not connected" in tudo -> CausaFalhaLeitor.SEM_COMUNICACAO
            else -> CausaFalhaLeitor.DESCONHECIDA
        }
        return MotivoFalhaLeitor(causa, detalhe)
    }

    /**
     * A causa é um defeito para registrar na tela de erros do servidor? Coletor no carregador é situação de uso: o
     * operador resolve tirando do berço, e registrar cada aperto do gatilho só esconderia os defeitos de verdade.
     */
    fun ehDefeito(causa: CausaFalhaLeitor): Boolean = causa != CausaFalhaLeitor.CARREGANDO

    /** Primeiro texto não vazio, numa linha e com tamanho limitado ("" se não houver nenhum). */
    fun detalhe(textos: List<String?>): String =
        textos.firstOrNull { !it.isNullOrBlank() }
            ?.replace(Regex("\\s+"), " ")?.trim()
            ?.take(TAMANHO_MAXIMO_DETALHE)
            .orEmpty()

    /** Texto traduzido da causa; null em [CausaFalhaLeitor.DESCONHECIDA] (a tela usa o detalhe do SDK). */
    @StringRes
    fun recurso(causa: CausaFalhaLeitor): Int? = when (causa) {
        CausaFalhaLeitor.NENHUM_LEITOR -> R.string.leitor_motivo_nenhum_leitor
        CausaFalhaLeitor.OCUPADO_OUTRO_APP -> R.string.leitor_motivo_ocupado
        CausaFalhaLeitor.OPERACAO_EM_ANDAMENTO -> R.string.leitor_motivo_operacao_em_andamento
        CausaFalhaLeitor.SEM_COMUNICACAO -> R.string.leitor_motivo_sem_comunicacao
        CausaFalhaLeitor.TEMPO_ESGOTADO -> R.string.leitor_motivo_tempo_esgotado
        CausaFalhaLeitor.REGIAO_NAO_CONFIGURADA -> R.string.leitor_motivo_regiao
        CausaFalhaLeitor.BATERIA -> R.string.leitor_motivo_bateria
        CausaFalhaLeitor.CARREGANDO -> R.string.leitor_motivo_carregando
        CausaFalhaLeitor.TEMPERATURA -> R.string.leitor_motivo_temperatura
        CausaFalhaLeitor.QUEDA -> R.string.leitor_motivo_queda
        CausaFalhaLeitor.DESCONHECIDA -> null
    }

    /**
     * Para usar o motivo depois de dois-pontos: "Nenhum leitor..." vira "nenhum leitor...". Siglas e códigos do SDK
     * ("RFID...", "SDK") ficam como estão.
     */
    fun minusculaInicial(texto: String): String {
        if (texto.length < 2 || !texto[0].isUpperCase() || !texto[1].isLowerCase()) return texto
        return texto.replaceFirstChar { it.lowercase(Locale.forLanguageTag("pt-BR")) }
    }
}
