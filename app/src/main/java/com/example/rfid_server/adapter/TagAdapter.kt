package com.example.rfid_server.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.rfid_server.R

/**
 * Aba "Leitura": etiquetas cadastradas (cartão com os dados do equipamento) e, em estilo discreto, as lidas que
 * ainda esperam o servidor identificar (coletor sem rede).
 */
class TagAdapter : ListAdapter<ItemLeitura, RecyclerView.ViewHolder>(Diferenca { it.chave }) {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val txtCodigoInterno: TextView = view.findViewById(R.id.txtCodigoInterno)
        val txtTipo: TextView = view.findViewById(R.id.txtTipo)
        val txtMarca: TextView = view.findViewById(R.id.txtMarca)
        val txtModelo: TextView = view.findViewById(R.id.txtModelo)
        val txtPatrimonio: TextView = view.findViewById(R.id.txtPatrimonio)
        val txtSerie: TextView = view.findViewById(R.id.txtSerie)
    }

    class VHAguardando(view: View) : RecyclerView.ViewHolder(view) {
        val txt: TextView = view.findViewById(android.R.id.text1)
    }

    override fun getItemViewType(position: Int): Int =
        if (getItem(position) is ItemLeitura.Aguardando) TIPO_AGUARDANDO else TIPO_CADASTRADA

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TIPO_AGUARDANDO) VHAguardando(inflater.inflate(R.layout.item_aguardando, parent, false))
        else VH(inflater.inflate(R.layout.item_tag, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val ctx = holder.itemView.context
        when (val item = getItem(position)) {
            is ItemLeitura.Aguardando -> (holder as VHAguardando).txt.text = ctx.getString(R.string.leitura_aguardando_servidor, item.epc)

            is ItemLeitura.Cadastrada -> {
                val h = holder as VH
                val t = item.tag
                val semValor = ctx.getString(R.string.tag_sem_valor)

                h.txtCodigoInterno.text = ctx.getString(R.string.tag_codigo, t.codigoInterno)
                h.txtTipo.text = ctx.getString(R.string.tag_tipo, t.tipo ?: semValor)
                h.txtMarca.text = ctx.getString(R.string.tag_marca, t.marca ?: semValor)
                h.txtModelo.text = ctx.getString(R.string.tag_modelo, t.modelo ?: semValor)
                h.txtPatrimonio.text = ctx.getString(R.string.tag_patrimonio, t.patrimonio)
                h.txtSerie.text = ctx.getString(R.string.tag_serie, t.numeroSerie)
            }
        }
    }

    fun atualizar(novaLista: List<ItemLeitura>) = submitList(novaLista)

    private companion object {
        const val TIPO_CADASTRADA = 0
        const val TIPO_AGUARDANDO = 1
    }
}
