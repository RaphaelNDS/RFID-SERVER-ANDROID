package com.example.rfid_server.dados

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import com.example.rfid_server.SessaoAtual
import com.example.rfid_server.sincronizacao.Sincronizador
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Inventário por local no coletor: abre, registra as etiquetas lidas e finaliza.
 * Tudo é gravado no aparelho primeiro e enviado pelo Sincronizador, então funciona sem rede.
 * Só existe um inventário aberto por vez.
 */
class RepositorioInventario internal constructor(
    private val dao: InventarioDao,
    private val executor: Executor,
    private val solicitarSincronizacao: () -> Unit,
    private val relogio: () -> Long = System::currentTimeMillis,
    private val gerarId: () -> String = { UUID.randomUUID().toString() },
    /** Token da sessão do operador logado (null = ninguém logado ou sessão expirada). */
    private val sessaoAtual: () -> String? = { null }
) {

    // Inventário aberto e EPCs já lidos nele. Só acessados na thread do executor
    private var abertoId: String? = null
    private val epcsLidos = HashSet<String>()

    init {
        executor.execute {
            dao.inventarioAbertoAgora()?.let { aberto ->
                abertoId = aberto.id
                epcsLidos.addAll(dao.epcs(aberto.id))
                Log.i(TAG, "Inventário em andamento retomado: ${aberto.local} (${epcsLidos.size} etiqueta(s) já lida(s))")
            }
        }
    }

    val inventarioAberto: LiveData<InventarioEntity?> = dao.inventarioAberto()

    /** Etiquetas do inventário que ainda não chegaram ao servidor (todos os inventários). */
    val itensNaoEnviados: LiveData<Int> = dao.contarItensNaoEnviados()

    fun itens(inventarioId: String): LiveData<List<InventarioItemEntity>> = dao.itens(inventarioId)

    fun itensNaoEnviados(inventarioId: String): LiveData<Int> = dao.contarItensNaoEnviados(inventarioId)

    /** Abre um inventário no [local]. Se já houver um aberto, não abre outro (devolve false em [aoTerminar]). */
    fun abrir(local: String, aoTerminar: (Boolean) -> Unit = {}) {
        val nome = local.trim().replace(Regex("\\s+"), " ")
        val iniciadoEm = relogio()
        val sessao = sessaoAtual()

        executor.execute {
            if (sessao == null) {
                Log.w(TAG, "Inventário não aberto: nenhum operador logado")
                aoTerminar(false)
                return@execute
            }
            if (nome.isEmpty() || abertoId != null) {
                Log.w(TAG, "Inventário não aberto: " + if (nome.isEmpty()) "local vazio" else "já existe um inventário em andamento")
                aoTerminar(false)
                return@execute
            }

            val id = gerarId()
            dao.inserirInventario(InventarioEntity(id = id, local = nome, iniciadoEm = iniciadoEm, sessaoOperador = sessao))
            abertoId = id
            epcsLidos.clear()

            Log.i(TAG, "Inventário aberto no local '$nome' (id $id)")
            solicitarSincronizacao()
            aoTerminar(true)
        }
    }

    /** Chamado a cada tag lida durante o inventário. Repetições no mesmo inventário são ignoradas. */
    fun registrar(epc: String) {
        val lidoEm = relogio()
        val sessao = sessaoAtual() ?: run {
            Log.w(TAG, "Tag $epc lida no inventário sem operador logado: ignorada")
            return
        }

        executor.execute {
            val id = abertoId ?: run {
                Log.d(TAG, "Tag $epc lida sem inventário aberto: ignorada")
                return@execute
            }
            if (!epcsLidos.add(epc)) return@execute

            dao.inserirItem(InventarioItemEntity(inventarioId = id, epc = epc, lidoEm = lidoEm, sessaoOperador = sessao))
            Log.d(TAG, "Tag $epc registrada no inventário (${epcsLidos.size} no total)")

            solicitarSincronizacao()
        }
    }

    /** Finaliza o inventário aberto. [aoTerminar] recebe quantas etiquetas foram lidas (ou null se não havia inventário aberto). */
    fun finalizar(aoTerminar: (Int?) -> Unit = {}) {
        val finalizadoEm = relogio()
        val sessao = sessaoAtual()

        executor.execute {
            val id = abertoId ?: run {
                Log.w(TAG, "Pedido de finalização sem inventário aberto")
                aoTerminar(null)
                return@execute
            }

            dao.finalizar(id, finalizadoEm, sessao)
            val total = dao.contarItensAgora(id)
            abertoId = null
            epcsLidos.clear()

            Log.i(TAG, "Inventário $id finalizado no coletor com $total etiqueta(s) lida(s)")
            solicitarSincronizacao()
            aoTerminar(total)
        }
    }

    companion object {
        private const val TAG = "INVENTARIO"

        @Volatile
        private var instancia: RepositorioInventario? = null

        fun get(context: Context): RepositorioInventario =
            instancia ?: synchronized(this) {
                instancia ?: context.applicationContext.let { app ->
                    val sincronizador = Sincronizador.get(app)
                    RepositorioInventario(
                        dao = BancoLocal.get(app).inventarioDao(),
                        executor = Executors.newSingleThreadExecutor(),
                        solicitarSincronizacao = sincronizador::solicitar,
                        sessaoAtual = { SessaoAtual.token(app) }
                    )
                }.also { instancia = it }
            }
    }
}
