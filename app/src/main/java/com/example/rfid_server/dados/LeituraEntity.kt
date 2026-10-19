package com.example.rfid_server.dados

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.rfid_server.view.TagView

enum class Situacao {
    /** Lida, mas ainda não consultada no servidor (ex.: coletor sem rede). */
    PENDENTE,
    CADASTRADA,
    NAO_CADASTRADA,
    /** O servidor recusou esta tag de forma definitiva (ex.: HTTP 400): não é reenviada. */
    ERRO
}

/**
 * Uma leitura feita pelo coletor. Fica guardada no aparelho até ser consultada e enviada ao
 * servidor, então nada se perde se a rede cair ou o app fechar.
 */
@Entity(
    tableName = "leituras",
    indices = [Index("arquivada"), Index("situacao", "envioConcluido")]
)
data class LeituraEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val epc: String,

    /** Hora em que o coletor leu a tag (epoch em ms). Vai para o servidor como "lidoEm". */
    val lidoEm: Long,

    val situacao: Situacao = Situacao.PENDENTE,

    /** true quando o servidor já recebeu a leitura (cadastrada) ou a tag não cadastrada. */
    val envioConcluido: Boolean = false,

    /** true quando a leitura é de uma sessão anterior: some da tela, mas ainda é enviada se faltar. */
    val arquivada: Boolean = false,

    // Dados da tag, preenchidos quando o servidor responde que ela é cadastrada
    val codigoInterno: String? = null,
    val tipo: String? = null,
    val marca: String? = null,
    val modelo: String? = null,
    val patrimonio: String? = null,
    val numeroSerie: String? = null,

    val ultimoErro: String? = null,

    /** Token da sessão do operador que leu (vai junto no envio, mesmo depois de a sessão expirar). */
    val sessaoOperador: String? = null
) {
    fun paraTagView() = TagView(
        epc = epc,
        codigoInterno = codigoInterno.orEmpty(),
        tipo = tipo,
        marca = marca,
        modelo = modelo,
        patrimonio = patrimonio.orEmpty(),
        numeroSerie = numeroSerie.orEmpty()
    )
}
