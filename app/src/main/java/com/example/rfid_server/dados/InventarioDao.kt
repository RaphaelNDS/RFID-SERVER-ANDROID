package com.example.rfid_server.dados

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/** Acesso aos inventários guardados no coletor. Métodos sem LiveData são bloqueantes (use fora da thread principal). */
@Dao
interface InventarioDao {

    @Insert
    fun inserirInventario(inventario: InventarioEntity)

    /** Devolve -1 se a etiqueta já tinha sido lida neste inventário. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun inserirItem(item: InventarioItemEntity): Long

    @Query("SELECT * FROM inventarios WHERE finalizadoEm IS NULL ORDER BY iniciadoEm DESC LIMIT 1")
    fun inventarioAberto(): LiveData<InventarioEntity?>

    @Query("SELECT * FROM inventarios WHERE finalizadoEm IS NULL ORDER BY iniciadoEm DESC LIMIT 1")
    fun inventarioAbertoAgora(): InventarioEntity?

    @Query("SELECT * FROM inventario_itens WHERE inventarioId = :inventarioId ORDER BY id DESC")
    fun itens(inventarioId: String): LiveData<List<InventarioItemEntity>>

    @Query("SELECT epc FROM inventario_itens WHERE inventarioId = :inventarioId")
    fun epcs(inventarioId: String): List<String>

    @Query("SELECT COUNT(*) FROM inventario_itens WHERE inventarioId = :inventarioId")
    fun contarItensAgora(inventarioId: String): Int

    @Query("UPDATE inventarios SET finalizadoEm = :finalizadoEm, sessaoFinalizacao = :sessao WHERE id = :id AND finalizadoEm IS NULL")
    fun finalizar(id: String, finalizadoEm: Long, sessao: String?): Int

    // ---------- Sincronização ----------

    /** Inventários com algo ainda não enviado ao servidor, do mais antigo para o mais novo. */
    @Query(
        """SELECT * FROM inventarios WHERE aberturaEnviada = 0
           OR (finalizadoEm IS NOT NULL AND finalizacaoEnviada = 0)
           OR id IN (SELECT inventarioId FROM inventario_itens WHERE enviado = 0)
           ORDER BY iniciadoEm"""
    )
    fun comPendencias(): List<InventarioEntity>

    @Query("SELECT * FROM inventario_itens WHERE inventarioId = :inventarioId AND enviado = 0 ORDER BY id LIMIT :limite")
    fun itensNaoEnviados(inventarioId: String, limite: Int): List<InventarioItemEntity>

    @Query("UPDATE inventarios SET aberturaEnviada = :enviada, ultimoErro = NULL WHERE id = :id")
    fun definirAberturaEnviada(id: String, enviada: Boolean)

    @Query("UPDATE inventario_itens SET enviado = 1 WHERE id IN (:ids)")
    fun marcarItensEnviados(ids: List<Long>)

    @Query("UPDATE inventarios SET finalizacaoEnviada = 1, ultimoErro = NULL WHERE id = :id")
    fun marcarFinalizacaoEnviada(id: String)

    @Query("UPDATE inventarios SET ultimoErro = :erro WHERE id = :id")
    fun registrarErro(id: String, erro: String)

    /** Etiquetas de inventário ainda não enviadas, de todos os inventários (aviso na tela principal). */
    @Query("SELECT COUNT(*) FROM inventario_itens WHERE enviado = 0")
    fun contarItensNaoEnviados(): LiveData<Int>

    @Query("SELECT COUNT(*) FROM inventario_itens WHERE inventarioId = :inventarioId AND enviado = 0")
    fun contarItensNaoEnviados(inventarioId: String): LiveData<Int>

    /** Apaga do coletor os inventários finalizados que já chegaram inteiros ao servidor. Devolve quantos. */
    @Transaction
    fun apagarConcluidos(): Int {
        val ids = idsConcluidos()
        if (ids.isEmpty()) return 0
        apagarItens(ids)
        apagarInventarios(ids)
        return ids.size
    }

    @Query(
        """SELECT id FROM inventarios WHERE finalizadoEm IS NOT NULL AND aberturaEnviada = 1
           AND finalizacaoEnviada = 1
           AND id NOT IN (SELECT inventarioId FROM inventario_itens WHERE enviado = 0)"""
    )
    fun idsConcluidos(): List<String>

    @Query("DELETE FROM inventario_itens WHERE inventarioId IN (:ids)")
    fun apagarItens(ids: List<String>)

    @Query("DELETE FROM inventarios WHERE id IN (:ids)")
    fun apagarInventarios(ids: List<String>)
}
