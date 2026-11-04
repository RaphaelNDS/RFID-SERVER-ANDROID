package com.example.rfid_server.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.rfid_server.R
import com.example.rfid_server.view.TagView

class TagAdapter : ListAdapter<TagView, TagAdapter.VH>(Diferenca { it.epc }) {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val txtCodigoInterno: TextView = view.findViewById(R.id.txtCodigoInterno)
        val txtTipo: TextView = view.findViewById(R.id.txtTipo)
        val txtMarca: TextView = view.findViewById(R.id.txtMarca)
        val txtModelo: TextView = view.findViewById(R.id.txtModelo)
        val txtPatrimonio: TextView = view.findViewById(R.id.txtPatrimonio)
        val txtSerie: TextView = view.findViewById(R.id.txtSerie)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tag, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = getItem(position)
        val ctx = holder.itemView.context
        val semValor = ctx.getString(R.string.tag_sem_valor)

        holder.txtCodigoInterno.text = ctx.getString(R.string.tag_codigo, t.codigoInterno)
        holder.txtTipo.text = ctx.getString(R.string.tag_tipo, t.tipo ?: semValor)
        holder.txtMarca.text = ctx.getString(R.string.tag_marca, t.marca ?: semValor)
        holder.txtModelo.text = ctx.getString(R.string.tag_modelo, t.modelo ?: semValor)
        holder.txtPatrimonio.text = ctx.getString(R.string.tag_patrimonio, t.patrimonio)
        holder.txtSerie.text = ctx.getString(R.string.tag_serie, t.numeroSerie)
    }

    fun atualizar(novaLista: List<TagView>) = submitList(novaLista)
}
