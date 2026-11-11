package com.example.rfid_server

/** Mensagem que a faixa do leitor mostra. */
enum class MensagemFaixa {
    CONECTANDO, CONECTADO, DESCONECTADO, DESCONECTADO_MOTIVO, PARADO, PARADO_MOTIVO,
    FALHA_LEITURA, FALHA_CONFIGURACAO, BATERIA_BAIXA, BATERIA_CRITICA
}

enum class CorFaixa { SUCESSO, ATENCAO, ERRO }

object FaixaLeitor {

    /** Por quanto tempo a faixa mostra "O leitor não começou a ler". */
    const val DURACAO_FALHA_LEITURA_MS = 6_000L

    /**
     * Conectado, o mais grave vence: falha recente do gatilho, bateria crítica, falha de configuração, bateria baixa.
     * Desconectado, mostra o motivo, se houver.
     */
    fun escolher(estado: EstadoLeitor, detalhe: DetalheLeitor, agora: Long): MensagemFaixa = when (estado) {
        EstadoLeitor.CONECTANDO -> MensagemFaixa.CONECTANDO
        EstadoLeitor.DESCONECTADO ->
            if (detalhe.motivoDesconexao != null) MensagemFaixa.DESCONECTADO_MOTIVO else MensagemFaixa.DESCONECTADO
        EstadoLeitor.PARADO ->
            if (detalhe.motivoDesconexao != null) MensagemFaixa.PARADO_MOTIVO else MensagemFaixa.PARADO
        EstadoLeitor.CONECTADO -> {
            val bateria = BateriaLeitor.classificar(detalhe.bateria, detalhe.carregando)
            when {
                falhaLeituraVisivel(detalhe, agora) -> MensagemFaixa.FALHA_LEITURA
                bateria == AlertaBateria.CRITICA -> MensagemFaixa.BATERIA_CRITICA
                detalhe.falhaConfiguracao != null -> MensagemFaixa.FALHA_CONFIGURACAO
                bateria == AlertaBateria.BAIXA -> MensagemFaixa.BATERIA_BAIXA
                else -> MensagemFaixa.CONECTADO
            }
        }
    }

    fun cor(mensagem: MensagemFaixa): CorFaixa = when (mensagem) {
        MensagemFaixa.CONECTADO -> CorFaixa.SUCESSO
        MensagemFaixa.CONECTANDO, MensagemFaixa.FALHA_CONFIGURACAO, MensagemFaixa.BATERIA_BAIXA -> CorFaixa.ATENCAO
        MensagemFaixa.DESCONECTADO, MensagemFaixa.DESCONECTADO_MOTIVO, MensagemFaixa.PARADO, MensagemFaixa.PARADO_MOTIVO,
        MensagemFaixa.FALHA_LEITURA, MensagemFaixa.BATERIA_CRITICA -> CorFaixa.ERRO
    }

    /** Daqui a quanto tempo a falha de leitura sai da faixa (para redesenhar); null se não está na faixa. */
    fun redesenharEmMs(detalhe: DetalheLeitor, agora: Long): Long? =
        if (falhaLeituraVisivel(detalhe, agora)) detalhe.momentoFalhaLeitura + DURACAO_FALHA_LEITURA_MS - agora else null

    private fun falhaLeituraVisivel(detalhe: DetalheLeitor, agora: Long): Boolean =
        detalhe.falhaLeitura != null &&
            agora >= detalhe.momentoFalhaLeitura &&
            agora - detalhe.momentoFalhaLeitura < DURACAO_FALHA_LEITURA_MS
}
