package com.example.rfid_server

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import com.example.rfid_server.adapter.BuscaLeituras
import androidx.lifecycle.distinctUntilChanged
import androidx.lifecycle.map
import com.example.rfid_server.dados.Situacao
import com.example.rfid_server.adapter.ItemLeitura
import com.example.rfid_server.dados.ContagemSessao
import com.example.rfid_server.dados.ItemNaoCadastrada
import com.example.rfid_server.dados.RepositorioCadastros
import com.example.rfid_server.dados.RepositorioInventario
import com.example.rfid_server.dados.RepositorioLeituras
import com.example.rfid_server.sincronizacao.EstadoSincronizacao
import com.example.rfid_server.sincronizacao.Sincronizador

/**
 * Estado da tela principal, compartilhado entre a MainActivity e as abas.
 * As listas vêm do banco local (RepositorioLeituras): sobrevivem a rotação, troca de tela e
 * falta de rede, e as abas mostram as tags mesmo que tenham sido abertas depois da leitura.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repositorio = RepositorioLeituras.get(application)

    /**
     * Aba "Leitura": cadastradas e as que ainda esperam o servidor identificar (sem rede). O banco refaz a consulta a
     * cada gravação na tabela (inclusive de uma não cadastrada): lista igual à anterior não remonta a aba nem o título.
     */
    private val leiturasTodas: LiveData<List<ItemLeitura>> = repositorio.abaLeitura.distinctUntilChanged().map { lista ->
        ItemLeitura.montar(
            lista.filter { it.situacao == Situacao.CADASTRADA },
            lista.filter { it.situacao == Situacao.PENDENTE }
        )
    }

    private val cadastros = RepositorioCadastros.get(application)

    /** Aba "Não cadastradas", com a situação do cadastro feito no coletor (se houver). */
    private val naoCadastradasTodas: LiveData<List<ItemNaoCadastrada>> = MediatorLiveData<List<ItemNaoCadastrada>>().apply {
        val leituras = repositorio.naoCadastradas.distinctUntilChanged()
        val guardados = cadastros.todos
        val montar = { _: Any? -> value = ItemNaoCadastrada.montar(leituras.value.orEmpty(), guardados.value.orEmpty()) }
        addSource(leituras, montar)
        addSource(guardados, montar)
    }

    /** Texto da busca da tela (vazio = sem filtro). Vale para as duas abas. */
    val termoBusca = MutableLiveData("")

    /** Aba "Leitura" já filtrada pela [termoBusca]. */
    val leituras: LiveData<List<ItemLeitura>> = MediatorLiveData<List<ItemLeitura>>().apply {
        val montar = { _: Any? -> value = BuscaLeituras.filtrar(leiturasTodas.value.orEmpty(), termoBusca.value.orEmpty()) }
        addSource(leiturasTodas, montar)
        addSource(termoBusca, montar)
    }

    /** Aba "Não cadastradas" já filtrada pela [termoBusca]. */
    val naoCadastradas: LiveData<List<ItemNaoCadastrada>> = MediatorLiveData<List<ItemNaoCadastrada>>().apply {
        val montar = { _: Any? -> value = BuscaLeituras.filtrarNaoCadastradas(naoCadastradasTodas.value.orEmpty(), termoBusca.value.orEmpty()) }
        addSource(naoCadastradasTodas, montar)
        addSource(termoBusca, montar)
    }

    /**
     * Com busca: (quantas aparecem, quantas foram lidas) somando as duas abas; null sem busca. Avisa o operador que a
     * lista está filtrada (uma etiqueta lida agora que não bate com a busca não aparece, e parecia que não leu).
     */
    val contagemBusca: LiveData<Pair<Int, Int>?> = MediatorLiveData<Pair<Int, Int>?>().apply {
        val montar = { _: Any? ->
            value = if (termoBusca.value.isNullOrBlank()) null
            else Pair(
                leituras.value.orEmpty().size + naoCadastradas.value.orEmpty().size,
                leiturasTodas.value.orEmpty().size + naoCadastradasTodas.value.orEmpty().size
            )
        }
        addSource(leituras, montar)
        addSource(naoCadastradas, montar)
    }

    /** Cadastradas da leitura atual por tipo, e quantas aguardam o servidor e quantas não são cadastradas (sem busca). */
    fun resumoPorTipo(semTipo: String): Triple<List<Pair<String, Int>>, Int, Int> {
        val todas = leiturasTodas.value.orEmpty()
        return Triple(
            BuscaLeituras.contagemPorTipo(todas, semTipo),
            todas.count { it is com.example.rfid_server.adapter.ItemLeitura.Aguardando },
            naoCadastradasTodas.value.orEmpty().size
        )
    }

    /** Cadastradas da leitura atual por situação no cadastro (sem busca); vazio se o servidor não manda a situação. */
    fun resumoPorSituacao(semSituacao: String): List<Pair<String, Int>> =
        BuscaLeituras.contagemPorSituacao(leiturasTodas.value.orEmpty(), semSituacao)

    /** Planilha de TODA a leitura atual (sem o filtro da busca). */
    fun planilha(): String = BuscaLeituras.csv(leiturasTodas.value.orEmpty(), naoCadastradasTodas.value.orEmpty())

    /** O que está guardado no aparelho e ainda não chegou ao servidor (leituras avulsas, de inventário e cadastros). */
    val pendentes: LiveData<Int> = MediatorLiveData<Int>().apply {
        val fontes = listOf(
            repositorio.pendentes,
            RepositorioInventario.get(application).itensNaoEnviados,
            cadastros.pendentes
        )
        val somar = { _: Int? -> value = fontes.sumOf { it.value ?: 0 } }
        fontes.forEach { addSource(it, somar) }
    }

    val estadoSincronizacao: LiveData<EstadoSincronizacao> = Sincronizador.get(application).estado

    val contagem: LiveData<ContagemSessao> = repositorio.contagemSessao.distinctUntilChanged()

    /** Botão "Nova leitura": limpa a tela; o que não foi enviado continua na fila. */
    fun novaLeitura() {
        repositorio.iniciarNovaSessao("pedido do operador")
    }
}
