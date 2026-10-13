package com.example.rfid_server.dados

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/** CadastroDao em memória, com as mesmas regras das queries do Room. Os LiveData precisam da InstantTaskExecutorRule. */
class FakeCadastroDao : CadastroDao {

    val cadastros = mutableListOf<CadastroEntity>()
    private var proximoId = 1L

    private val todosLive = MutableLiveData<List<CadastroEntity>>()
    private val pendentesLive = MutableLiveData<Int>()

    fun porEpcAgora(epc: String) = cadastros.singleOrNull { it.epc == epc }

    private fun publicar() {
        todosLive.value = cadastros.sortedBy { it.id }
        pendentesLive.value = cadastros.count { it.situacao == SituacaoCadastro.PENDENTE }
    }

    private fun alterar(id: Long, mudanca: (CadastroEntity) -> CadastroEntity) {
        cadastros.replaceAll { if (it.id == id) mudanca(it) else it }
        publicar()
    }

    // REPLACE do SQLite: apaga a linha que conflita no índice único (epc) e insere com id novo
    override fun salvar(cadastro: CadastroEntity): Long {
        cadastros.removeAll { it.epc == cadastro.epc }
        val id = proximoId++
        cadastros += cadastro.copy(id = id)
        publicar()
        return id
    }

    override fun porEpc(epc: String): CadastroEntity? = porEpcAgora(epc)

    override fun todos(): LiveData<List<CadastroEntity>> = todosLive.also { publicar() }

    override fun pendentes(): List<CadastroEntity> =
        cadastros.filter { it.situacao == SituacaoCadastro.PENDENTE }.sortedBy { it.id }

    override fun contarPendentes(): LiveData<Int> = pendentesLive.also { publicar() }

    override fun marcarRecusado(id: Long, erro: String) =
        alterar(id) { it.copy(situacao = SituacaoCadastro.RECUSADO, erro = erro) }

    override fun registrarErro(id: Long, erro: String) = alterar(id) { it.copy(erro = erro) }

    override fun apagar(id: Long) {
        cadastros.removeAll { it.id == id }
        publicar()
    }
}
