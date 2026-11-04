package com.example.rfid_server.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.rfid_server.R
import com.example.rfid_server.dados.ItemEsperado

/** Etiquetas esperadas no local que ainda não foram lidas. Tocar num item chama [aoTocar] (abre o Localizar). */
class EsperadoAdapter(private val aoTocar: (ItemEsperado) -> Unit) :
    ListAdapter<ItemEsperado, EsperadoAdapter.VH>(Diferenca { it.epc }) {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val patrimonio: TextView = view.findViewById(R.id.txtPatrimonio)
        val descricao: TextView = view.findViewById(R.id.txtDescricao)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_esperado, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)

        val ctx = holder.itemView.context
        holder.patrimonio.text = item.patrimonio?.let { ctx.getString(R.string.esperado_patrimonio, it) }
            ?: ctx.getString(R.string.esperado_sem_patrimonio, item.epc)
        holder.descricao.text = item.descricao().ifEmpty { item.codigoInterno?.let { ctx.getString(R.string.esperado_codigo, it) } ?: item.epc }
        holder.itemView.setOnClickListener { aoTocar(item) }
    }

    fun atualizar(novaLista: List<ItemEsperado>) = submitList(novaLista)
}
