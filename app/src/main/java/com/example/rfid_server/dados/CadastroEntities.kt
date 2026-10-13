package com.example.rfid_server.dados

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

enum class SituacaoCadastro {
    /** Guardado no coletor, aguardando envio ao servidor. */
    PENDENTE,
    /** O servidor recusou (ex.: etiqueta já cadastrada com outros dados): o operador precisa corrigir. */
    RECUSADO
}

/**
 * Cadastro de etiqueta feito no coletor, guardado até o servidor confirmar (funciona sem rede).
 * Confirmado, sai daqui e a leitura da etiqueta passa a "cadastrada". Um por etiqueta: salvar de
 * novo (ex.: corrigir um recusado) substitui o anterior.
 */
@Entity(tableName = "cadastros", indices = [Index(value = ["epc"], unique = true)])
data class CadastroEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val epc: String,
    val codigoInterno: String,
    val patrimonio: String = "",
    val numeroSerie: String = "",

    // Ids vão para o servidor; nomes servem para mostrar na tela sem consultar o catálogo de novo
    val tipoId: Long? = null,
    val tipoNome: String? = null,
    val marcaId: Long? = null,
    val marcaNome: String? = null,
    val modeloId: Long? = null,
    val modeloNome: String? = null,

    val criadoEm: Long,
    val situacao: SituacaoCadastro = SituacaoCadastro.PENDENTE,
    val erro: String? = null,

    /** Sessão do operador que fez o cadastro. */
    val sessaoOperador: String? = null
)

@Dao
interface CadastroDao {

    /** Insere ou substitui o cadastro da mesma etiqueta. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun salvar(cadastro: CadastroEntity): Long

    @Query("SELECT * FROM cadastros WHERE epc = :epc")
    fun porEpc(epc: String): CadastroEntity?

    @Query("SELECT * FROM cadastros ORDER BY id")
    fun todos(): LiveData<List<CadastroEntity>>

    @Query("SELECT * FROM cadastros WHERE situacao = 'PENDENTE' ORDER BY id")
    fun pendentes(): List<CadastroEntity>

    @Query("SELECT COUNT(*) FROM cadastros WHERE situacao = 'PENDENTE'")
    fun contarPendentes(): LiveData<Int>

    @Query("UPDATE cadastros SET situacao = 'RECUSADO', erro = :erro WHERE id = :id")
    fun marcarRecusado(id: Long, erro: String)

    @Query("UPDATE cadastros SET erro = :erro WHERE id = :id")
    fun registrarErro(id: Long, erro: String)

    @Query("DELETE FROM cadastros WHERE id = :id")
    fun apagar(id: Long)
}
