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
