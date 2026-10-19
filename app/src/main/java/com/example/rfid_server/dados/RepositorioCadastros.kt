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

    /** Como está o envio de um cadastro recém-guardado, para a tela dar a mensagem certa ao operador. */
    sealed class ResultadoEnvio {
        /** O Sincronizador ainda não terminou de tentar. */
        object Aguardando : ResultadoEnvio()
        /** O servidor confirmou. */
        object Enviado : ResultadoEnvio()
        /** O servidor recusou: o operador precisa corrigir. */
        data class Recusado(val motivo: String?) : ResultadoEnvio()
        /** Não deu para enviar agora (ex.: sem rede): segue guardado e vai depois. */
        data class NaoEnviado(val motivo: String?) : ResultadoEnvio()
    }

    companion object {
        private const val TAG = "CADASTRO"

        /**
         * [atual] é o cadastro da etiqueta no aparelho. O Sincronizador apaga o cadastro quando o servidor
         * confirma, marca RECUSADO quando o servidor recusa e grava o erro quando não consegue enviar
         * ([salvar] zera o erro, então um erro aqui é sempre da tentativa depois de salvar).
         */
        fun resultadoEnvio(atual: CadastroEntity?): ResultadoEnvio = when {
            atual == null -> ResultadoEnvio.Enviado
            atual.situacao == SituacaoCadastro.RECUSADO -> ResultadoEnvio.Recusado(atual.erro)
            atual.erro != null -> ResultadoEnvio.NaoEnviado(atual.erro)
            else -> ResultadoEnvio.Aguardando
        }

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
