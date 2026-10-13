package com.example.rfid_server.dados

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Inventário de um local feito neste coletor. Fica guardado no aparelho até tudo chegar ao
 * servidor (abertura, etiquetas lidas e finalização); depois é apagado daqui.
 *
 * O [id] é um UUID gerado no coletor: o inventário pode ser feito sem rede, e reenviar não duplica no servidor.
 */
@Entity(tableName = "inventarios")
data class InventarioEntity(
    @PrimaryKey
    val id: String,

    val local: String,

    val iniciadoEm: Long,

    /** null enquanto o operador não finalizar. */
    val finalizadoEm: Long? = null,

    val aberturaEnviada: Boolean = false,
    val finalizacaoEnviada: Boolean = false,

    val ultimoErro: String? = null,

    /** Sessão do operador que abriu e a de quem finalizou (podem ser operadores diferentes). */
    val sessaoOperador: String? = null,
    val sessaoFinalizacao: String? = null
)

/** Etiqueta lida num inventário (uma vez por inventário: o índice único descarta repetições). */
@Entity(
    tableName = "inventario_itens",
    indices = [Index(value = ["inventarioId", "epc"], unique = true), Index("enviado")]
)
data class InventarioItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val inventarioId: String,

    val epc: String,

    val lidoEm: Long,

    val enviado: Boolean = false,

    /** Sessão do operador que leu esta etiqueta. */
    val sessaoOperador: String? = null
)
