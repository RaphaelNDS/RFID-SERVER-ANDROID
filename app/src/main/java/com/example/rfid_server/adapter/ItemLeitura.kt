package com.example.rfid_server.adapter

import com.example.rfid_server.dados.LeituraEntity
import com.example.rfid_server.dados.Situacao
import com.example.rfid_server.view.TagView

/**
 * Linha da aba "Leitura": etiqueta cadastrada (com os dados do equipamento) ou etiqueta lida que ainda espera
 * o servidor dizer o que é (coletor sem rede). Antes, as que esperavam não apareciam em aba nenhuma.
 */
sealed class ItemLeitura {
    /** Identifica a linha na lista. Muda quando a etiqueta deixa de esperar, para a linha ser trocada inteira. */
    abstract val chave: String

    data class Cadastrada(val tag: TagView) : ItemLeitura() {
        override val chave: String get() = "c:${tag.epc}"
    }

    data class Aguardando(val epc: String) : ItemLeitura() {
        override val chave: String get() = "a:$epc"
    }

    companion object {
        /** Junta as cadastradas e as que aguardam o servidor, da leitura mais nova para a mais antiga. */
        fun montar(cadastradas: List<LeituraEntity>, aguardando: List<LeituraEntity>): List<ItemLeitura> =
            (cadastradas + aguardando).sortedByDescending { it.id }.map {
                if (it.situacao == Situacao.CADASTRADA) Cadastrada(it.paraTagView()) else Aguardando(it.epc)
            }
    }
}
