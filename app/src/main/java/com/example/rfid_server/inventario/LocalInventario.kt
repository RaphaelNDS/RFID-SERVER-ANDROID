package com.example.rfid_server.inventario

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
