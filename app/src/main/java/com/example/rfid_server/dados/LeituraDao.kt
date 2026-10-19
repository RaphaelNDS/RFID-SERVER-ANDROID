package com.example.rfid_server.dados

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * Acesso à fila de leituras. Os métodos sem LiveData são bloqueantes:
 * chame fora da thread principal (RepositorioLeituras e Sincronizador já fazem isso).
 */
@Dao
interface LeituraDao {

    @Insert
    fun inserir(leitura: LeituraEntity): Long

    // Atualizações campo a campo (e não @Update da linha inteira): o Sincronizador trabalha com uma
    // cópia lida no início da passada, e regravar tudo desfaria um "arquivada" feito nesse meio-tempo.

    @Query(
        """UPDATE leituras SET situacao = 'CADASTRADA', codigoInterno = :codigoInterno, tipo = :tipo,
           marca = :marca, modelo = :modelo, patrimonio = :patrimonio, numeroSerie = :numeroSerie,
           ultimoErro = NULL WHERE id = :id"""
    )
    fun marcarCadastrada(
        id: Long, codigoInterno: String, tipo: String?, marca: String?,
        modelo: String?, patrimonio: String, numeroSerie: String
    )

    @Query("UPDATE leituras SET situacao = 'NAO_CADASTRADA', ultimoErro = NULL WHERE id = :id")
    fun marcarNaoCadastrada(id: Long)

    /**
     * A etiqueta acabou de ser cadastrada pelo coletor: as leituras dela viram "cadastradas" e voltam
     * para a fila (envioConcluido = 0), para o servidor registrar a leitura com a hora real.
     * Devolve quantas leituras mudaram.
     */
    @Query(
        """UPDATE leituras SET situacao = 'CADASTRADA', codigoInterno = :codigoInterno, tipo = :tipo,
           marca = :marca, modelo = :modelo, patrimonio = :patrimonio, numeroSerie = :numeroSerie,
           envioConcluido = 0, ultimoErro = NULL WHERE epc = :epc AND situacao != 'CADASTRADA'"""
    )
    fun marcarCadastradaPorEpc(
        epc: String, codigoInterno: String, tipo: String?, marca: String?,
        modelo: String?, patrimonio: String, numeroSerie: String
    ): Int

    @Query("UPDATE leituras SET envioConcluido = 1, ultimoErro = NULL WHERE id = :id")
    fun marcarEnviada(id: Long)

    @Query("UPDATE leituras SET ultimoErro = :erro WHERE id = :id")
    fun registrarErro(id: Long, erro: String)

    /** O servidor recusou de vez: sai da fila. Se nem chegou a ser classificada, fica como ERRO. */
    @Query(
        """UPDATE leituras SET envioConcluido = 1, ultimoErro = :erro,
           situacao = CASE WHEN situacao = 'PENDENTE' THEN 'ERRO' ELSE situacao END WHERE id = :id"""
    )
    fun marcarRecusada(id: Long, erro: String)

    /** Tudo que ainda precisa falar com o servidor, da mais antiga para a mais nova. */
    @Query("SELECT * FROM leituras WHERE situacao = 'PENDENTE' OR envioConcluido = 0 ORDER BY id")
    fun pendentes(): List<LeituraEntity>

    @Query("SELECT epc FROM leituras WHERE arquivada = 0")
    fun epcsDaSessao(): List<String>

    @Query("UPDATE leituras SET arquivada = 1 WHERE arquivada = 0")
    fun arquivarSessao(): Int

    /** Leituras de sessões anteriores que já foram enviadas não servem mais para nada. */
    @Query("DELETE FROM leituras WHERE arquivada = 1 AND situacao != 'PENDENTE' AND envioConcluido = 1")
    fun apagarArquivadasConcluidas(): Int

    @Query("SELECT * FROM leituras WHERE arquivada = 0 AND situacao = 'CADASTRADA' ORDER BY id DESC")
    fun cadastradasDaSessao(): LiveData<List<LeituraEntity>>

    @Query("SELECT * FROM leituras WHERE arquivada = 0 AND situacao = 'NAO_CADASTRADA' ORDER BY id DESC")
    fun naoCadastradasDaSessao(): LiveData<List<LeituraEntity>>

    @Query("SELECT COUNT(*) FROM leituras WHERE situacao = 'PENDENTE' OR envioConcluido = 0")
    fun contarPendentes(): LiveData<Int>

    /** Contadores da sessão atual (o que está na tela). COALESCE: SUM de tabela vazia é NULL. */
    @Query(
        """SELECT COUNT(*) AS lidas,
           COALESCE(SUM(CASE WHEN situacao = 'CADASTRADA' THEN 1 ELSE 0 END), 0) AS cadastradas,
           COALESCE(SUM(CASE WHEN situacao = 'NAO_CADASTRADA' THEN 1 ELSE 0 END), 0) AS naoCadastradas,
           COALESCE(SUM(CASE WHEN situacao = 'PENDENTE' THEN 1 ELSE 0 END), 0) AS aguardandoServidor
           FROM leituras WHERE arquivada = 0"""
    )
    fun contarSessao(): LiveData<ContagemSessao>
}

data class ContagemSessao(
    val lidas: Int = 0,
    val cadastradas: Int = 0,
    val naoCadastradas: Int = 0,
    /** Lidas, mas ainda sem resposta do servidor (cadastrada ou não?). */
    val aguardandoServidor: Int = 0
) {
    /**
     * Texto da linha de contadores da tela principal. "Aguardando" só aparece quando há.
     * Dentro de cada contador os espaços são inseparáveis: com números maiores a linha quebra
     * entre um contador e outro, nunca no meio (ex.: "Não" numa linha e "cadastradas: 150" na outra).
     */
    fun resumo(): String {
        val itens = mutableListOf("Lidas: $lidas", "Cadastradas: $cadastradas", "Não cadastradas: $naoCadastradas")
        if (aguardandoServidor > 0) itens += "Aguardando: $aguardandoServidor"
        return itens.joinToString("  •  ") { it.replace(' ', ESPACO_INSEPARAVEL) }
    }

    companion object {
        const val ESPACO_INSEPARAVEL = ' '
    }
}
