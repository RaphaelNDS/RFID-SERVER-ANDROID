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
    private val recusasPublicadas = MutableLiveData<List<RecusaInventario>>()
    private var desdeRecusas: Long? = null

    fun inventario(id: String) = inventarios.single { it.id == id }

    private fun publicar() {
        aberto.value = inventarioAbertoAgora()
        naoEnviados.value = itens.count { item -> !item.enviado && inventarios.none { it.id == item.inventarioId && it.recusadoEm != null } }
        desdeRecusas?.let { recusasPublicadas.value = calcularRecusas(it) }
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

    override fun epcsLidos(inventarioId: String): LiveData<List<String>> =
        MutableLiveData(itens.filter { it.inventarioId == inventarioId }.sortedByDescending { it.id }.map { it.epc })

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
            inv.recusadoEm == null && (!inv.aberturaEnviada ||
                (inv.finalizadoEm != null && !inv.finalizacaoEnviada) ||
                itens.any { it.inventarioId == inv.id && !it.enviado })
        }.sortedBy { it.iniciadoEm }

    override fun itensNaoEnviados(inventarioId: String, aposId: Long, limite: Int): List<InventarioItemEntity> =
        itens.filter { it.inventarioId == inventarioId && !it.enviado && it.id > aposId }.sortedBy { it.id }.take(limite)

    override fun inventarioAgora(id: String): InventarioEntity? = inventarios.singleOrNull { it.id == id }

    override fun registrarRecusaItens(ids: List<Long>, erro: String, agora: Long) {
        itens.replaceAll {
            if (it.id in ids) it.copy(ultimoErro = erro, recusas = it.recusas + 1, primeiraRecusaEm = it.primeiraRecusaEm ?: agora) else it
        }
        publicar()
    }

    override fun desistirItens(ids: List<Long>, limite: Int, antesDe: Long): Int {
        var desistidas = 0
        itens.replaceAll {
            val primeira = it.primeiraRecusaEm
            if (it.id in ids && it.recusas >= limite && primeira != null && primeira <= antesDe) {
                desistidas++
                it.copy(enviado = true)
            } else it
        }
        publicar()
        return desistidas
    }

    override fun definirAberturaEnviada(id: String, enviada: Boolean) =
        alterar(id) { it.copy(aberturaEnviada = enviada, ultimoErro = null) }

    override fun marcarItensEnviados(ids: List<Long>) {
        itens.replaceAll {
            if (it.id in ids) it.copy(enviado = true, recusas = 0, primeiraRecusaEm = null, ultimoErro = null) else it
        }
        publicar()
    }

    override fun reabrirEtapas(id: String) =
        alterar(id) { it.copy(aberturaEnviada = false, finalizacaoEnviada = false, ultimoErro = null) }

    override fun reenviarItens(inventarioId: String): Int {
        var n = 0
        itens.replaceAll {
            val desistida = it.primeiraRecusaEm != null
            if (it.inventarioId == inventarioId && it.enviado && !desistida) {
                n++
                it.copy(enviado = false)
            } else it
        }
        publicar()
        return n
    }

    override fun marcarFinalizacaoEnviada(id: String) =
        alterar(id) { it.copy(finalizacaoEnviada = true, ultimoErro = null) }

    override fun registrarErro(id: String, erro: String) = alterar(id) { it.copy(ultimoErro = erro) }

    override fun registrarRecusa(id: String, erro: String, agora: Long) =
        alterar(id) { it.copy(ultimoErro = erro, recusas = it.recusas + 1, primeiraRecusaEm = it.primeiraRecusaEm ?: agora) }

    override fun zerarRecusas(id: String) = alterar(id) { it.copy(recusas = 0, primeiraRecusaEm = null) }

    override fun desistir(id: String, agora: Long) = alterar(id) { it.copy(recusadoEm = agora) }

    override fun idsRecusadosAntes(antesDe: Long): List<String> =
        inventarios.filter { it.recusadoEm != null && it.recusadoEm < antesDe }.map { it.id }

    override fun sessoesEmUso(): List<String> =
        (inventarios.flatMap { listOf(it.sessaoOperador, it.sessaoFinalizacao) } + itens.map { it.sessaoOperador })
            .filterNotNull().filter { it.startsWith("sessao1:") }.distinct()

    override fun contarItensNaoEnviados(): LiveData<Int> = naoEnviados.also { publicar() }

    override fun contarItensNaoEnviadosAgora(): Int {
        val recusados = inventarios.filter { it.recusadoEm != null }.map { it.id }.toSet()
        return itens.count { !it.enviado && it.inventarioId !in recusados }
    }

    override fun contarItensNaoEnviados(inventarioId: String): LiveData<Int> =
        MutableLiveData(itens.count { it.inventarioId == inventarioId && !it.enviado })

    override fun contarItensEnviadosAgora(inventarioId: String): Int =
        itens.count { it.inventarioId == inventarioId && it.enviado }

    override fun recusas(desde: Long): LiveData<List<RecusaInventario>> {
        desdeRecusas = desde
        return recusasPublicadas.also { publicar() }
    }

    private fun calcularRecusas(desde: Long): List<RecusaInventario> {
        val inteiros = inventarios.filter { it.recusadoEm != null && it.recusadoEm >= desde }
            .map { RecusaInventario(it.id, it.local, it.ultimoErro, 0) }
        val etiquetas = inventarios.filter { it.recusadoEm == null }.mapNotNull { inv ->
            val desistidas = itens.filter { it.inventarioId == inv.id && it.enviado && it.primeiraRecusaEm != null }
            if (desistidas.isEmpty()) null
            else RecusaInventario(inv.id, inv.local, desistidas.mapNotNull { it.ultimoErro }.maxOrNull(), desistidas.size)
        }
        return (inteiros + etiquetas).sortedWith(compareBy({ it.local }, { it.inventarioId }))
    }

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
