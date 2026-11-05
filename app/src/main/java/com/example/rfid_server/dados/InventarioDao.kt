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

    /** Inventários com algo ainda não enviado ao servidor, do mais antigo para o mais novo (sem os recusados de vez). */
    @Query(
        """SELECT * FROM inventarios WHERE recusadoEm IS NULL AND (aberturaEnviada = 0
           OR (finalizadoEm IS NOT NULL AND finalizacaoEnviada = 0)
           OR id IN (SELECT inventarioId FROM inventario_itens WHERE enviado = 0))
           ORDER BY iniciadoEm"""
    )
    fun comPendencias(): List<InventarioEntity>

    /** Etiquetas ainda não enviadas com id maior que [aposId] (paginação: as recusadas na passada ficam para trás). */
    @Query("SELECT * FROM inventario_itens WHERE inventarioId = :inventarioId AND enviado = 0 AND id > :aposId ORDER BY id LIMIT :limite")
    fun itensNaoEnviados(inventarioId: String, aposId: Long, limite: Int): List<InventarioItemEntity>

    @Query("SELECT * FROM inventarios WHERE id = :id")
    fun inventarioAgora(id: String): InventarioEntity?

    /** O servidor recusou estas etiquetas: conta mais uma e guarda quando foi a primeira recusa de cada uma. */
    @Query(
        """UPDATE inventario_itens SET ultimoErro = :erro, recusas = recusas + 1,
           primeiraRecusaEm = COALESCE(primeiraRecusaEm, :agora) WHERE id IN (:ids)"""
    )
    fun registrarRecusaItens(ids: List<Long>, erro: String, agora: Long)

    /**
     * Desiste das etiquetas recusadas pelo menos [limite] vezes, a primeira antes de [antesDe]: saem da fila
     * (enviado = 1), com o motivo guardado em ultimoErro. Devolve quantas.
     */
    @Query(
        """UPDATE inventario_itens SET enviado = 1 WHERE id IN (:ids) AND recusas >= :limite
           AND primeiraRecusaEm IS NOT NULL AND primeiraRecusaEm <= :antesDe"""
    )
    fun desistirItens(ids: List<Long>, limite: Int, antesDe: Long): Int

    @Query("UPDATE inventarios SET aberturaEnviada = :enviada, ultimoErro = NULL WHERE id = :id")
    fun definirAberturaEnviada(id: String, enviada: Boolean)

    /**
     * Aceitas pelo servidor: saem da fila e as recusas anteriores são apagadas. Assim "enviada com recusa
     * guardada" passa a significar só "o coletor desistiu dela" (ver [reenviarItens]).
     */
    @Query("UPDATE inventario_itens SET enviado = 1, recusas = 0, primeiraRecusaEm = NULL, ultimoErro = NULL WHERE id IN (:ids)")
    fun marcarItensEnviados(ids: List<Long>)

    /**
     * O servidor não conhece o inventário (ex.: banco dele restaurado de um backup): abertura, etiquetas e
     * finalização vão de novo, senão o inventário ficaria incompleto lá. Reenviar é seguro (o id do inventário
     * é um UUID e o servidor ignora etiqueta repetida). As etiquetas de que o coletor já desistiu por recusa
     * continuam fora da fila. Devolve quantas etiquetas voltaram.
     */
    @Transaction
    fun prepararReabertura(id: String): Int {
        reabrirEtapas(id)
        return reenviarItens(id)
    }

    @Query("UPDATE inventarios SET aberturaEnviada = 0, finalizacaoEnviada = 0, ultimoErro = NULL WHERE id = :id")
    fun reabrirEtapas(id: String)

    /**
     * Volta para a fila as etiquetas já enviadas, menos as desistidas: enviada com primeiraRecusaEm guardada só
     * acontece quando o coletor desistiu (as aceitas têm as recusas apagadas em [marcarItensEnviados]). Vale
     * também para a desistência na primeira recusa (sessão do operador recusada de vez).
     */
    @Query(
        """UPDATE inventario_itens SET enviado = 0 WHERE inventarioId = :inventarioId AND enviado = 1
           AND primeiraRecusaEm IS NULL"""
    )
    fun reenviarItens(inventarioId: String): Int

    @Query("UPDATE inventarios SET finalizacaoEnviada = 1, ultimoErro = NULL WHERE id = :id")
    fun marcarFinalizacaoEnviada(id: String)

    @Query("UPDATE inventarios SET ultimoErro = :erro WHERE id = :id")
    fun registrarErro(id: String, erro: String)

    /** Recusa definitiva do servidor: conta mais uma e guarda quando foi a primeira da sequência. */
    @Query(
        """UPDATE inventarios SET ultimoErro = :erro, recusas = recusas + 1,
           primeiraRecusaEm = COALESCE(primeiraRecusaEm, :agora) WHERE id = :id"""
    )
    fun registrarRecusa(id: String, erro: String, agora: Long)

    /** O servidor aceitou alguma etapa: a sequência de recusas acabou. */
    @Query("UPDATE inventarios SET recusas = 0, primeiraRecusaEm = NULL WHERE id = :id")
    fun zerarRecusas(id: String)

    /** O coletor desiste de enviar: sai da fila e da contagem de "aguardando envio". */
    @Query("UPDATE inventarios SET recusadoEm = :agora WHERE id = :id")
    fun desistir(id: String, agora: Long)

    /** Etiquetas de inventário ainda não enviadas, de todos os inventários (aviso na tela principal). Sem os recusados de vez. */
    @Query(
        """SELECT COUNT(*) FROM inventario_itens WHERE enviado = 0
           AND inventarioId NOT IN (SELECT id FROM inventarios WHERE recusadoEm IS NOT NULL)"""
    )
    fun contarItensNaoEnviados(): LiveData<Int>

    @Query("SELECT COUNT(*) FROM inventario_itens WHERE inventarioId = :inventarioId AND enviado = 0")
    fun contarItensNaoEnviados(inventarioId: String): LiveData<Int>

    /** Etiquetas deste inventário que já saíram da fila (enviadas ou desistidas): com alguma, não dá para cancelar. */
    @Query("SELECT COUNT(*) FROM inventario_itens WHERE inventarioId = :inventarioId AND enviado = 1")
    fun contarItensEnviadosAgora(inventarioId: String): Int

    /**
     * O que o coletor desistiu de enviar e o operador precisa saber (faixa vermelha nas telas):
     *  - inventários recusados de vez a partir de [desde] (epoch ms), com [RecusaInventario.etiquetas] = 0;
     *  - etiquetas desistidas (enviadas com primeiraRecusaEm guardada, ver [reenviarItens]) de inventários que
     *    não foram recusados inteiros: uma linha por inventário, com quantas são.
     * Antes, os dois casos só saíam da contagem "aguardando envio", sem aviso nenhum.
     */
    @Query(
        """SELECT id AS inventarioId, local, ultimoErro AS motivo, 0 AS etiquetas FROM inventarios
           WHERE recusadoEm IS NOT NULL AND recusadoEm >= :desde
           UNION ALL
           SELECT inv.id AS inventarioId, inv.local AS local, MAX(it.ultimoErro) AS motivo, COUNT(*) AS etiquetas
           FROM inventario_itens it JOIN inventarios inv ON inv.id = it.inventarioId
           WHERE it.enviado = 1 AND it.primeiraRecusaEm IS NOT NULL AND inv.recusadoEm IS NULL
           GROUP BY inv.id, inv.local
           ORDER BY local, inventarioId"""
    )
    fun recusas(desde: Long): LiveData<List<RecusaInventario>>

    /** O operador cancelou o inventário antes de qualquer envio: some do aparelho, com as etiquetas lidas. */
    @Transaction
    fun apagarInventario(id: String) {
        apagarItens(listOf(id))
        apagarInventarios(listOf(id))
    }

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

    /** Apaga do coletor os inventários recusados de vez antes de [antesDe] (epoch ms). Devolve quantos. */
    @Transaction
    fun apagarRecusadosAntigos(antesDe: Long): Int {
        val ids = idsRecusadosAntes(antesDe)
        if (ids.isEmpty()) return 0
        apagarItens(ids)
        apagarInventarios(ids)
        return ids.size
    }

    @Query("SELECT id FROM inventarios WHERE recusadoEm IS NOT NULL AND recusadoEm < :antesDe")
    fun idsRecusadosAntes(antesDe: Long): List<String>

    /** Referências de sessão (CofreSessoes) ainda gravadas nos inventários e etiquetas: o token delas não pode sair do cofre. */
    @Query(
        """SELECT sessaoOperador FROM inventarios WHERE sessaoOperador LIKE 'sessao1:%'
           UNION SELECT sessaoFinalizacao FROM inventarios WHERE sessaoFinalizacao LIKE 'sessao1:%'
           UNION SELECT sessaoOperador FROM inventario_itens WHERE sessaoOperador LIKE 'sessao1:%'"""
    )
    fun sessoesEmUso(): List<String>

    @Query("DELETE FROM inventario_itens WHERE inventarioId IN (:ids)")
    fun apagarItens(ids: List<String>)

    @Query("DELETE FROM inventarios WHERE id IN (:ids)")
    fun apagarInventarios(ids: List<String>)
}

/**
 * Aviso de recusa definitiva do servidor (ver [InventarioDao.recusas]). [etiquetas] = 0: o inventário inteiro foi
 * recusado; > 0: só essas etiquetas dele.
 */
data class RecusaInventario(
    val inventarioId: String,
    val local: String,
    val motivo: String?,
    val etiquetas: Int
) {
    val inventarioInteiro: Boolean get() = etiquetas == 0

    /**
     * Identifica o aviso no "Ciente". Nas etiquetas entra a quantidade: se o coletor desistir de mais etiquetas
     * do mesmo inventário depois, o aviso volta a aparecer.
     */
    fun chave(): String = if (inventarioInteiro) "inventario:$inventarioId" else "etiquetas:$inventarioId:$etiquetas"

    companion object {
        /** Os avisos que o operador ainda não marcou como "Ciente". */
        fun naoVistos(avisos: List<RecusaInventario>, cientes: Set<String>): List<RecusaInventario> =
            avisos.filter { it.chave() !in cientes }
    }
}
