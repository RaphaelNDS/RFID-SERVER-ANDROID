package com.example.rfid_server.dados

import com.example.rfid_server.ExecutorProtegido
import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import com.example.rfid_server.CofreSessoes
import com.example.rfid_server.SessaoAtual
import com.example.rfid_server.sincronizacao.Sincronizador
import java.util.UUID
import java.util.concurrent.Executor

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
    private val sessaoAtual: () -> String? = { null },
    /** Avisos de recusa que o operador já marcou como "Ciente". */
    private val cientes: GuardaCientes = GuardaCientes.EmMemoria()
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

    /**
     * Chamado a cada tag lida durante o inventário. Repetições no mesmo inventário são ignoradas.
     * [aoNova] roda (na thread do repositório) só quando a tag é nova no inventário: bip/vibração na tela.
     */
    fun registrar(epc: String, aoNova: (() -> Unit)? = null) {
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
            aoNova?.invoke()

            solicitarSincronizacao()
        }
    }

    /**
     * Finaliza o inventário aberto. [aoTerminar] recebe quantas etiquetas foram lidas, ou null se não havia
     * inventário aberto ou ninguém está logado (a tela manda entrar de novo).
     */
    fun finalizar(aoTerminar: (Int?) -> Unit = {}) {
        val finalizadoEm = relogio()
        val sessao = sessaoAtual()

        executor.execute {
            // Como no abrir: sem sessão a finalização iria ao servidor sem operador e seria recusada.
            // O inventário continua aberto, e o operador finaliza depois de entrar de novo
            if (sessao == null) {
                Log.w(TAG, "Inventário não finalizado: nenhum operador logado")
                aoTerminar(null)
                return@execute
            }

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

    /**
     * Cancela o inventário aberto: apaga do aparelho o inventário e as etiquetas lidas. Só enquanto nada foi
     * enviado ao servidor (abertura e etiquetas); depois disso o servidor já tem o inventário e é preciso
     * finalizar. [aoTerminar] roda na thread do repositório.
     *
     * Limite conhecido: se a sincronização estiver enviando a abertura neste exato momento, o servidor pode ficar
     * com um inventário aberto sem etiquetas (a marcação "enviada" cai num inventário que não existe mais).
     */
    fun cancelar(aoTerminar: (ResultadoCancelamento) -> Unit = {}) {
        executor.execute {
            val inventario = abertoId?.let { dao.inventarioAgora(it) }
            if (inventario == null) {
                Log.w(TAG, "Pedido de cancelamento sem inventário aberto")
                aoTerminar(ResultadoCancelamento.SEM_INVENTARIO)
                return@execute
            }

            val enviadas = dao.contarItensEnviadosAgora(inventario.id)
            if (!podeCancelar(inventario, enviadas)) {
                Log.w(TAG, "Inventário ${inventario.id} (${inventario.local}) não cancelado: já foi enviado ao servidor " +
                    "(abertura ${if (inventario.aberturaEnviada) "enviada" else "não enviada"}, $enviadas etiqueta(s) enviada(s))")
                aoTerminar(ResultadoCancelamento.JA_ENVIADO)
                return@execute
            }

            val total = dao.contarItensAgora(inventario.id)
            dao.apagarInventario(inventario.id)
            abertoId = null
            epcsLidos.clear()

            Log.i(TAG, "Inventário ${inventario.id} (${inventario.local}) cancelado pelo operador: apagado do coletor com $total etiqueta(s) lida(s)")
            aoTerminar(ResultadoCancelamento.CANCELADO)
        }
    }

    // ================= AVISOS DE RECUSA =================

    // Chaves dos avisos que o operador já marcou como "Ciente" (null até ler do aparelho: sem piscar o aviso)
    private val cientesLidos = MutableLiveData<Set<String>>()

    private val recusas: LiveData<List<RecusaInventario>> = dao.recusas(relogio() - GUARDA_AVISO_MS)

    init {
        executor.execute { cientesLidos.postValue(cientes.ler()) }
    }

    /**
     * Inventários e etiquetas que o coletor desistiu de enviar (recusa definitiva do servidor) e que o operador
     * ainda não viu: faixa vermelha na tela principal e na de inventário.
     */
    val avisosRecusa: LiveData<List<RecusaInventario>> = MediatorLiveData<List<RecusaInventario>>().apply {
        val montar = { _: Any? ->
            val lista = recusas.value
            val vistos = cientesLidos.value
            if (lista != null && vistos != null) value = RecusaInventario.naoVistos(lista, vistos)
        }
        addSource(recusas, montar)
        addSource(cientesLidos, montar)
    }

    /** Botão "Ciente" da faixa: o aviso some (e não volta ao reabrir o app). Chame na thread principal. */
    fun marcarCiente(aviso: RecusaInventario) {
        // Guarda só as chaves de avisos que ainda existem: os apagados do aparelho (30 dias) saem da lista
        val existentes = recusas.value.orEmpty().map { it.chave() }.toSet()
        val novos = (cientesLidos.value.orEmpty() intersect existentes) + aviso.chave()
        cientesLidos.value = novos
        Log.i(TAG, "Operador ciente da recusa do servidor: ${aviso.chave()} (${aviso.local}): ${aviso.motivo}")
        executor.execute { cientes.gravar(novos) }
    }

    /** Onde ficam guardados os avisos já vistos. Nos testes, [EmMemoria]. */
    interface GuardaCientes {
        fun ler(): Set<String>
        fun gravar(chaves: Set<String>)

        class EmMemoria(iniciais: Set<String> = emptySet()) : GuardaCientes {
            var chaves: Set<String> = iniciais
                private set
            override fun ler() = chaves
            override fun gravar(chaves: Set<String>) { this.chaves = chaves }
        }

        /** SharedPreferences próprias (não as configurações do AppConfig): só uma lista de chaves. */
        class Preferencias(context: Context) : GuardaCientes {
            private val prefs = context.applicationContext.getSharedPreferences(PREFS_CIENTES, Context.MODE_PRIVATE)
            override fun ler(): Set<String> = prefs.getStringSet(KEY_CIENTES, emptySet()).orEmpty().toSet()
            override fun gravar(chaves: Set<String>) = prefs.edit { putStringSet(KEY_CIENTES, chaves) }
        }
    }

    enum class ResultadoCancelamento { CANCELADO, JA_ENVIADO, SEM_INVENTARIO }

    companion object {
        private const val TAG = "INVENTARIO"
        private const val PREFS_CIENTES = "avisos_recusa"
        private const val KEY_CIENTES = "cientes"

        /** Avisos de inventário recusado aparecem por 30 dias (depois disso ele é apagado do aparelho). */
        internal const val GUARDA_AVISO_MS = 30L * 24 * 60 * 60 * 1000

        /** Só dá para cancelar o que o servidor ainda não conhece: abertura não enviada e nenhuma etiqueta enviada. */
        fun podeCancelar(inventario: InventarioEntity, etiquetasEnviadas: Int): Boolean =
            !inventario.aberturaEnviada && etiquetasEnviadas == 0

        /**
         * O local digitado não está entre os conhecidos (sugestões)? Compara como o servidor: sem diferenciar
         * maiúsculas nem espaços extras. Sem nenhum local conhecido (coletor novo, nunca falou com o servidor),
         * não há com o que comparar: não é tratado como novo.
         */
        fun localNovo(local: String, conhecidos: Collection<String>): Boolean {
            if (conhecidos.isEmpty()) return false
            val chave = EsperadoGuardado.chave(local)
            return conhecidos.none { EsperadoGuardado.chave(it) == chave }
        }

        @Volatile
        private var instancia: RepositorioInventario? = null

        fun get(context: Context): RepositorioInventario =
            instancia ?: synchronized(this) {
                instancia ?: context.applicationContext.let { app ->
                    val sincronizador = Sincronizador.get(app)
                    RepositorioInventario(
                        dao = BancoLocal.get(app).inventarioDao(),
                        executor = ExecutorProtegido.umaThread(app, "gravar o inventário"),
                        solicitarSincronizacao = sincronizador::solicitar,
                        sessaoAtual = { SessaoAtual.token(app)?.let { CofreSessoes.get(app).referencia(it) } },
                        cientes = GuardaCientes.Preferencias(app)
                    )
                }.also { instancia = it }
            }
    }
}
