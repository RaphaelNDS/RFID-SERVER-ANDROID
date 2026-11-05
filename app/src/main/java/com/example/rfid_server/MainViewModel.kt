package com.example.rfid_server

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
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

    /** Aba "Leitura": cadastradas e as que ainda esperam o servidor identificar (sem rede). */
    val leituras: LiveData<List<ItemLeitura>> = repositorio.abaLeitura.map { lista ->
        ItemLeitura.montar(
            lista.filter { it.situacao == Situacao.CADASTRADA },
            lista.filter { it.situacao == Situacao.PENDENTE }
        )
    }

    private val cadastros = RepositorioCadastros.get(application)

    /** Aba "Não cadastradas", com a situação do cadastro feito no coletor (se houver). */
    val naoCadastradas: LiveData<List<ItemNaoCadastrada>> = MediatorLiveData<List<ItemNaoCadastrada>>().apply {
        val leituras = repositorio.naoCadastradas
        val guardados = cadastros.todos
        val montar = { _: Any? -> value = ItemNaoCadastrada.montar(leituras.value.orEmpty(), guardados.value.orEmpty()) }
        addSource(leituras, montar)
        addSource(guardados, montar)
    }

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

    val contagem: LiveData<ContagemSessao> = repositorio.contagemSessao

    /** Botão "Nova leitura": limpa a tela; o que não foi enviado continua na fila. */
    fun novaLeitura() {
        repositorio.iniciarNovaSessao("pedido do operador")
    }
}
