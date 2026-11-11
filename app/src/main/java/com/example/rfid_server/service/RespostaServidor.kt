package com.example.rfid_server.service

/** Resultado de uma chamada ao servidor: separa "não existe" de "não deu para perguntar". */
sealed class RespostaServidor<out T> {
    data class Sucesso<T>(val valor: T) : RespostaServidor<T>()
    object NaoEncontrado : RespostaServidor<Nothing>()
    /**
     * [motivoSessao]: só na recusa de sessão (SESSAO_INVALIDA), o "motivo" que o servidor manda (DESCONHECIDA,
     * DESATIVADO, EXPIRADA...). null em servidor de antes desse campo.
     */
    data class Falha(val motivo: MotivoFalha, val mensagem: String, val motivoSessao: String? = null) : RespostaServidor<Nothing>()
}

enum class MotivoFalha {
    /** Sem rede, rede lenta (tempo esgotado) ou o endereço não leva a lugar nenhum. */
    SEM_CONEXAO,
    /**
     * A conexão foi recusada na hora (porta fechada): a rede chega até a máquina do servidor, mas ele não está rodando
     * (parado, reiniciando para uma versão nova). Tratada como SEM_CONEXAO na sincronização (para tudo e tenta de novo),
     * só com outra mensagem: o operador sabe que não é problema do coletor.
     */
    SERVIDOR_FORA_DO_AR,
    CHAVE_INVALIDA,
    ENDERECO_INVALIDO,
    /** Erro 5xx, redirecionamento ou resposta que não é o JSON esperado: vale tentar de novo depois. */
    ERRO_SERVIDOR,
    /** O servidor recusou ESTA requisição (4xx): repetir não adianta. */
    REQUISICAO_RECUSADA,
    /** A sessão do operador não vale para esta ação (expirada, encerrada, de outro coletor, usuário excluído). */
    SESSAO_INVALIDA,
    /** O certificado HTTPS do servidor não foi confirmado neste coletor, ou é diferente do confirmado. */
    CERTIFICADO,
    /**
     * HTTP 429: o servidor bloqueou o coletor por muitas tentativas com a chave inválida. Repetir só mantém o bloqueio:
     * quem resolve é o responsável (chave certa nas Configurações), e a sincronização para até lá.
     */
    MUITAS_TENTATIVAS,
    /**
     * Só da sincronização (não vem do servidor): registros retidos porque o servidor recusou a sessão, mas nenhuma das
     * sessões retidas é a sessão atual do aparelho (sessões anteriores ou de outro operador). Entrar de novo não muda
     * nada: eles esperam o prazo de 24 h ou o servidor voltar a aceitar.
     */
    SESSAO_ANTERIOR_RETIDA
}
