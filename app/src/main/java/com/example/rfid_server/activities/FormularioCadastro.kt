package com.example.rfid_server.activities

/**
 * Valores do formulário de cadastro de etiqueta (lógica pura, testável sem Android).
 * Usado para saber se o operador mudou algo que ainda não salvou, e perguntar antes de descartar.
 * Textos comparados sem espaços nas pontas (o salvar também usa trim).
 */
data class FormularioCadastro(
    val codigoInterno: String = "",
    val patrimonio: String = "",
    val numeroSerie: String = "",
    val tipoId: Long? = null,
    val marcaId: Long? = null,
    val modeloId: Long? = null
) {
    private fun normalizado() = copy(
        codigoInterno = codigoInterno.trim(),
        patrimonio = patrimonio.trim(),
        numeroSerie = numeroSerie.trim()
    )

    /** true quando há diferença em relação ao que estava salvo (ou ao formulário vazio, num cadastro novo). */
    fun alteradoEmRelacaoA(base: FormularioCadastro): Boolean = normalizado() != base.normalizado()
}

/**
 * Sessão do operador na tela de cadastro (lógica pura). As telas de leitura voltam para o login quando a sessão
 * acaba; no cadastro isso perdia o formulário. Aqui a sessão vencida com algo por salvar pede a senha na própria
 * tela e, renovada, salva de novo o mesmo cadastro.
 */
object SessaoNoCadastro {

    /**
     * O AvisoSessao para este tempo antes do fim da sessão: depois do fim, a verificação dele manda para o login
     * (fechando a tela). Os avisos de 30 e 5 min já foram dados até lá.
     */
    const val MARGEM_PARAR_AVISO_MS = 2_000L

    /** Depois do fim, espera este tanto antes de conferir a sessão (o relógio do Handler não é exato). */
    const val FOLGA_FIM_MS = 500L

    enum class Acao {
        /** Pedir a senha aqui mesmo (renovar a sessão sem fechar o formulário). */
        PEDIR_SENHA,
        /** Voltar para o login como as outras telas: não há nada a perder (ou não há de quem renovar). */
        LOGIN,
        /** A sessão está boa: a falha foi ao gravar no coletor (já avisada); só liberar o botão para tentar de novo. */
        TENTAR_DE_NOVO
    }

    /**
     * O repositório não guardou o cadastro: sem sessão é a sessão que acabou (pedir a senha e salvar de novo);
     * com sessão, foi falha ao gravar no coletor.
     */
    fun aposNaoGuardar(temSessao: Boolean, email: String?): Acao = when {
        temSessao -> Acao.TENTAR_DE_NOVO
        email.isNullOrBlank() -> Acao.LOGIN
        else -> Acao.PEDIR_SENHA
    }

    /**
     * A sessão acabou com a tela aberta (ou ao voltar para ela): com alterações não salvas ou um cadastro esperando
     * a sessão, pede a senha aqui; sem nada a perder, vai para o login como as outras telas.
     */
    fun semSessao(alterado: Boolean, cadastroEsperando: Boolean, email: String?): Acao =
        if ((alterado || cadastroEsperando) && !email.isNullOrBlank()) Acao.PEDIR_SENHA else Acao.LOGIN

    /** Daqui a quanto parar o AvisoSessao (0 = já: não iniciar). */
    fun esperaAtePararAviso(expiraEm: Long, agora: Long): Long = (expiraEm - agora - MARGEM_PARAR_AVISO_MS).coerceAtLeast(0)

    /** Daqui a quanto conferir se a sessão acabou. */
    fun esperaAteFim(expiraEm: Long, agora: Long): Long = (expiraEm - agora).coerceAtLeast(0) + FOLGA_FIM_MS
}
