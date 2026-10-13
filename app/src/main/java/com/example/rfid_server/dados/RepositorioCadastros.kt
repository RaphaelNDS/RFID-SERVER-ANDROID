package com.example.rfid_server.dados

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import com.example.rfid_server.SessaoAtual
import com.example.rfid_server.sincronizacao.Sincronizador
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Cadastros de etiqueta feitos no coletor: grava no aparelho e o Sincronizador envia ao servidor
 * (funciona sem rede). Salvar de novo a mesma etiqueta substitui o cadastro anterior, inclusive
 * um recusado pelo servidor que o operador corrigiu.
 */
class RepositorioCadastros internal constructor(
    private val dao: CadastroDao,
    private val executor: Executor,
    private val solicitarSincronizacao: () -> Unit,
    private val relogio: () -> Long = System::currentTimeMillis,
    /** Token da sessão do operador logado (null = ninguém logado ou sessão expirada). */
    private val sessaoAtual: () -> String? = { null }
) {

    val todos: LiveData<List<CadastroEntity>> = dao.todos()
    val pendentes: LiveData<Int> = dao.contarPendentes()

    /** [aoTerminar] recebe false se não havia operador logado (nada é guardado). */
    fun salvar(cadastro: CadastroEntity, aoTerminar: (Boolean) -> Unit = {}) {
        val sessao = sessaoAtual() ?: run {
            Log.w(TAG, "Cadastro da etiqueta ${cadastro.epc} não guardado: nenhum operador logado")
            aoTerminar(false)
            return
        }
        val novo = cadastro.copy(
            id = 0,
            epc = cadastro.epc.trim(),
            codigoInterno = cadastro.codigoInterno.trim(),
            patrimonio = cadastro.patrimonio.trim(),
            numeroSerie = cadastro.numeroSerie.trim(),
            criadoEm = relogio(),
            situacao = SituacaoCadastro.PENDENTE,
            erro = null,
            sessaoOperador = sessao
        )

        executor.execute {
            val anterior = dao.porEpc(novo.epc)
            dao.salvar(novo)

            Log.i(
                TAG,
                (if (anterior == null) "Cadastro da etiqueta ${novo.epc} guardado no coletor"
                else "Cadastro da etiqueta ${novo.epc} corrigido (antes: ${anterior.situacao})") +
                    " (patrimônio ${novo.patrimonio.ifBlank { "—" }}): aguardando envio ao servidor"
            )

            solicitarSincronizacao()
            aoTerminar(true)
        }
    }

    /** Cadastro guardado para a etiqueta (pendente ou recusado), para preencher o formulário. */
    fun porEpc(epc: String, callback: (CadastroEntity?) -> Unit) {
        executor.execute { callback(dao.porEpc(epc)) }
    }

    companion object {
        private const val TAG = "CADASTRO"

        @Volatile
        private var instancia: RepositorioCadastros? = null

        fun get(context: Context): RepositorioCadastros =
            instancia ?: synchronized(this) {
                instancia ?: context.applicationContext.let { app ->
                    RepositorioCadastros(
                        dao = BancoLocal.get(app).cadastroDao(),
                        executor = Executors.newSingleThreadExecutor(),
                        solicitarSincronizacao = Sincronizador.get(app)::solicitar,
                        sessaoAtual = { SessaoAtual.token(app) }
                    )
                }.also { instancia = it }
            }
    }
}
