package com.example.rfid_server.dados

import com.example.rfid_server.ExecutorProtegido
import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import com.example.rfid_server.CofreSessoes
import com.example.rfid_server.SessaoAtual
import com.example.rfid_server.sincronizacao.Sincronizador
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/**
 * Porta de entrada das leituras do coletor: grava no aparelho primeiro e só depois
 * tenta falar com o servidor (Sincronizador). Assim a leitura nunca se perde por falta de rede.
 *
 * "Sessão" = o que aparece na tela. Começa uma nova a cada abertura do app (como antes,
 * quando as listas ficavam só na memória); leituras ainda não enviadas continuam na fila.
 */
class RepositorioLeituras internal constructor(
    private val dao: LeituraDao,
    private val executor: Executor,
    private val solicitarSincronizacao: () -> Unit,
    private val relogio: () -> Long = System::currentTimeMillis,
    /** Token da sessão do operador logado (null = ninguém logado ou sessão expirada). */
    private val sessaoAtual: () -> String? = { null }
) {

    // EPCs já lidos nesta sessão. Só muda na thread do executor; o gatilho consulta de outra thread (por isso concorrente)
    private val epcsDaSessao: MutableSet<String> = ConcurrentHashMap.newKeySet()

    init {
        executor.execute { epcsDaSessao.addAll(dao.epcsDaSessao()) }
    }

    val cadastradas: LiveData<List<LeituraEntity>> = dao.cadastradasDaSessao()
    val naoCadastradas: LiveData<List<LeituraEntity>> = dao.naoCadastradasDaSessao()
    /** Lidas na sessão e ainda sem resposta do servidor (sem rede): aba de leitura, como "aguardando". */
    val aguardando: LiveData<List<LeituraEntity>> = dao.aguardandoDaSessao()

    /** Aba de leitura (cadastradas e aguardando) numa consulta só: não pisca quando uma muda de situação. */
    val abaLeitura: LiveData<List<LeituraEntity>> = dao.abaLeituraDaSessao()
    val pendentes: LiveData<Int> = dao.contarPendentes()
    val contagemSessao: LiveData<ContagemSessao> = dao.contarSessao()

    /**
     * Chamado a cada tag lida (de qualquer thread). Repetições na mesma sessão são ignoradas.
     * [aoNova] roda (na thread do repositório) só quando a tag é nova na sessão: bip/vibração na tela.
     */
    fun registrar(epc: String, aoNova: (() -> Unit)? = null) {
        // Repetição (a mesma etiqueta chega várias vezes por segundo com o gatilho apertado): descarta antes de
        // consultar a sessão, que lê as preferências e calcula o SHA-256 da referência a cada chamada
        if (epc in epcsDaSessao) return
        val lidoEm = relogio()
        val sessao = sessaoAtual() ?: run {
            // O login é obrigatório: sem operador a leitura não teria autoria e o servidor recusaria
            Log.w(TAG, "Tag $epc lida sem operador logado: ignorada")
            return
        }

        executor.execute {
            if (epc in epcsDaSessao) return@execute

            // Marca como lida só depois de gravar: se a gravação falhar (memória cheia), a próxima leitura tenta de novo
            dao.inserir(LeituraEntity(epc = epc, lidoEm = lidoEm, sessaoOperador = sessao))
            epcsDaSessao.add(epc)
            Log.d(TAG, "Tag $epc lida e guardada no aparelho")
            aoNova?.invoke()

            solicitarSincronizacao()
        }
    }

    /**
     * Limpa a tela. Leituras ainda não enviadas continuam guardadas e serão enviadas.
     * [motivo] só aparece no log (ex.: "app aberto", "pedido do operador").
     */
    fun iniciarNovaSessao(motivo: String) {
        executor.execute {
            val arquivadas = dao.arquivarSessao()
            val apagadas = dao.apagarArquivadasConcluidas()
            epcsDaSessao.clear()

            val aguardando = arquivadas - apagadas
            Log.i(TAG, "Nova sessão de leitura iniciada ($motivo): $arquivadas tag(s) saíram da tela" +
                if (aguardando > 0) "; $aguardando ainda serão enviadas ao servidor" else "")
        }
    }

    /**
     * Leituras recusadas de vez ainda guardadas (da sessão e arquivadas, até 30 dias), com o motivo em ultimoErro.
     * [aoTerminar] roda na thread do repositório.
     */
    fun recusadas(aoTerminar: (List<LeituraEntity>) -> Unit) {
        executor.execute { aoTerminar(dao.recusadasAgora()) }
    }

    /**
     * "Tentar de novo": as recusadas [ids] voltam para a fila (ex.: o problema era do cadastro, já corrigido no
     * servidor) e a sincronização é pedida. [aoTerminar] recebe quantas voltaram (na thread do repositório).
     */
    fun tentarDeNovo(ids: List<Long>, aoTerminar: (Int) -> Unit = {}) {
        if (ids.isEmpty()) return aoTerminar(0)
        executor.execute {
            // Em partes: o SQLite limita quantos valores cabem num IN (...)
            ids.chunked(LOTE_IDS).forEach { dao.desfazerRecusas(it) }
            Log.i(TAG, "Operador pediu para tentar de novo ${ids.size} leitura(s) recusada(s): voltaram para a fila")
            solicitarSincronizacao()
            aoTerminar(ids.size)
        }
    }

    companion object {
        private const val TAG = "LEITURAS"
        private const val LOTE_IDS = 500

        @Volatile
        private var instancia: RepositorioLeituras? = null

        fun get(context: Context): RepositorioLeituras =
            instancia ?: synchronized(this) {
                instancia ?: context.applicationContext.let { app ->
                    val sincronizador = Sincronizador.get(app)
                    RepositorioLeituras(
                        dao = BancoLocal.get(app).leituraDao(),
                        executor = ExecutorProtegido.umaThread(app, "gravar as leituras"),
                        // Junta os pedidos de ~2,5 s: com o gatilho apertado, cada etiqueta nova pedia uma passada
                        solicitarSincronizacao = sincronizador::solicitarEmBreve,
                        sessaoAtual = { SessaoAtual.token(app)?.let { CofreSessoes.get(app).referencia(it) } }
                    )
                }.also { instancia = it }
            }
    }
}
