package com.example.rfid_server.dados

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/** InventarioDao em memória, com as mesmas regras das queries do Room. Os LiveData precisam da InstantTaskExecutorRule. */
class FakeInventarioDao : InventarioDao {

    val inventarios = mutableListOf<InventarioEntity>()
    val itens = mutableListOf<InventarioItemEntity>()
    private var proximoItemId = 1L

    private val aberto = MutableLiveData<InventarioEntity?>()
    private val naoEnviados = MutableLiveData<Int>()

    fun inventario(id: String) = inventarios.single { it.id == id }

    private fun publicar() {
        aberto.value = inventarioAbertoAgora()
        naoEnviados.value = itens.count { !it.enviado }
    }

    private fun alterar(id: String, mudanca: (InventarioEntity) -> InventarioEntity) {
        val i = inventarios.indexOfFirst { it.id == id }
        if (i >= 0) inventarios[i] = mudanca(inventarios[i])
        publicar()
    }

    override fun inserirInventario(inventario: InventarioEntity) {
        inventarios += inventario
        publicar()
    }

    override fun inserirItem(item: InventarioItemEntity): Long {
        if (itens.any { it.inventarioId == item.inventarioId && it.epc == item.epc }) return -1
        val id = proximoItemId++
        itens += item.copy(id = id)
        publicar()
        return id
    }

    override fun inventarioAberto(): LiveData<InventarioEntity?> = aberto.also { publicar() }

    override fun inventarioAbertoAgora(): InventarioEntity? =
        inventarios.filter { it.finalizadoEm == null }.maxByOrNull { it.iniciadoEm }

    override fun itens(inventarioId: String): LiveData<List<InventarioItemEntity>> =
        MutableLiveData(itens.filter { it.inventarioId == inventarioId }.sortedByDescending { it.id })

    override fun epcs(inventarioId: String): List<String> = itens.filter { it.inventarioId == inventarioId }.map { it.epc }

    override fun contarItensAgora(inventarioId: String): Int = itens.count { it.inventarioId == inventarioId }

    override fun finalizar(id: String, finalizadoEm: Long, sessao: String?): Int {
        val atual = inventarios.single { it.id == id }
        if (atual.finalizadoEm != null) return 0
        alterar(id) { it.copy(finalizadoEm = finalizadoEm, sessaoFinalizacao = sessao) }
        return 1
    }

    override fun comPendencias(): List<InventarioEntity> =
        inventarios.filter { inv ->
            !inv.aberturaEnviada ||
                (inv.finalizadoEm != null && !inv.finalizacaoEnviada) ||
                itens.any { it.inventarioId == inv.id && !it.enviado }
        }.sortedBy { it.iniciadoEm }

    override fun itensNaoEnviados(inventarioId: String, limite: Int): List<InventarioItemEntity> =
        itens.filter { it.inventarioId == inventarioId && !it.enviado }.sortedBy { it.id }.take(limite)

    override fun definirAberturaEnviada(id: String, enviada: Boolean) =
        alterar(id) { it.copy(aberturaEnviada = enviada, ultimoErro = null) }

    override fun marcarItensEnviados(ids: List<Long>) {
        itens.replaceAll { if (it.id in ids) it.copy(enviado = true) else it }
        publicar()
    }

    override fun marcarFinalizacaoEnviada(id: String) =
        alterar(id) { it.copy(finalizacaoEnviada = true, ultimoErro = null) }

    override fun registrarErro(id: String, erro: String) = alterar(id) { it.copy(ultimoErro = erro) }

    override fun contarItensNaoEnviados(): LiveData<Int> = naoEnviados.also { publicar() }

    override fun contarItensNaoEnviados(inventarioId: String): LiveData<Int> =
        MutableLiveData(itens.count { it.inventarioId == inventarioId && !it.enviado })

    override fun idsConcluidos(): List<String> =
        inventarios.filter { inv ->
            inv.finalizadoEm != null && inv.aberturaEnviada && inv.finalizacaoEnviada &&
                itens.none { it.inventarioId == inv.id && !it.enviado }
        }.map { it.id }

    override fun apagarItens(ids: List<String>) {
        itens.removeAll { it.inventarioId in ids }
        publicar()
    }

    override fun apagarInventarios(ids: List<String>) {
        inventarios.removeAll { it.id in ids }
        publicar()
    }
}
