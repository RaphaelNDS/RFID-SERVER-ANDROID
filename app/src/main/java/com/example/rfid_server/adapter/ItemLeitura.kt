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

    /**
     * O que vai em cada linha do cartão da etiqueta cadastrada (item_tag.xml), sem os rótulos (vêm do strings.xml):
     *  1. [patrimonio] em destaque (null: "Sem patrimônio");
     *  2. [descricao] "Tipo · Marca · Modelo", só com o que foi informado (null: nenhum dos três);
     *  3. [codigo] e [serie] (null: sem número de série) em texto menor.
     */
    data class LinhasTag(val patrimonio: String?, val descricao: String?, val codigo: String, val serie: String?) {
        companion object {
            fun de(tag: TagView) = LinhasTag(
                patrimonio = tag.patrimonio.preenchido(),
                descricao = listOfNotNull(tag.tipo.preenchido(), tag.marca.preenchido(), tag.modelo.preenchido())
                    .joinToString(" · ")
                    .ifEmpty { null },
                codigo = tag.codigoInterno.trim(),
                serie = tag.numeroSerie.preenchido()
            )

            private fun String?.preenchido(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    companion object {
        /** Junta as cadastradas e as que aguardam o servidor, da leitura mais nova para a mais antiga. */
        fun montar(cadastradas: List<LeituraEntity>, aguardando: List<LeituraEntity>): List<ItemLeitura> =
            (cadastradas + aguardando).sortedByDescending { it.id }.map {
                if (it.situacao == Situacao.CADASTRADA) Cadastrada(it.paraTagView()) else Aguardando(it.epc)
            }
    }
}
