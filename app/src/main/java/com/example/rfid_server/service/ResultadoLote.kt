package com.example.rfid_server.service

import com.example.rfid_server.view.TagView

/** Resultado de uma leitura enviada em lote (POST /api/android/leituras/lote). */
enum class StatusLote {
    CADASTRADA,
    NAO_CADASTRADA,
    /** Recusa definitiva desta leitura: não adianta reenviar. */
    RECUSADA,
    /**
     * Status que este app não conhece (servidor mais novo), numa resposta 2xx: o servidor registrou a leitura,
     * então ela sai da fila. Não vem do servidor com esse nome.
     */
    ACEITA,
    /** Falha passageira no servidor: tenta de novo depois. */
    ERRO
}

/**
 * [indice] = posição da leitura no lote enviado; [tag] vem preenchida quando é CADASTRADA.
 * [motivoSessao]: em RECUSADA por causa da sessão do operador, o "motivo" do servidor (null nas outras recusas
 * e em servidor de antes desse campo).
 */
data class ResultadoLote(
    val indice: Int,
    val status: StatusLote,
    val tag: TagView?,
    val mensagem: String?,
    val motivoSessao: String? = null
)
